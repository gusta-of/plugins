import * as vscode from 'vscode';
import { Core, errText } from './core';
import { SCHEME, buildDocPath, parseDocPath } from './docs';
import { CompleteResult, ObjRef } from './model';
import { SQL_WORDS, identifierAt, inLineCommentOrString } from './sqlText';

/** Associação documento ↔ ligação (persistida por ficheiro; as consolas são ficheiros próprios). */
export class Bindings {
  private readonly map = new Map<string, string>();
  private readonly _onChange = new vscode.EventEmitter<void>();
  readonly onChange = this._onChange.event;

  constructor(private readonly state: vscode.Memento) {
    const saved = state.get<Record<string, string>>('bindings', {});
    for (const [k, v] of Object.entries(saved)) this.map.set(k, v);
  }

  get(uri: vscode.Uri): string | undefined {
    if (uri.scheme === SCHEME) return parseDocPath(uri.path, uri.query)?.connId;
    return this.map.get(uri.toString());
  }

  async set(uri: vscode.Uri, connId: string | undefined): Promise<void> {
    if (connId) this.map.set(uri.toString(), connId);
    else this.map.delete(uri.toString());
    await this.state.update('bindings', Object.fromEntries(this.map));
    this._onChange.fire();
  }
}

export function docUri(connId: string, ref: ObjRef, body = false): vscode.Uri {
  const q = body ? 'body=1' : '';
  return vscode.Uri.from({ scheme: SCHEME, path: buildDocPath(connId, ref, body).split('?')[0], query: q });
}

/** Conteúdo (DDL / código-fonte) dos documentos orastudio: */
export class DdlProvider implements vscode.TextDocumentContentProvider {
  private readonly _onDidChange = new vscode.EventEmitter<vscode.Uri>();
  readonly onDidChange = this._onDidChange.event;

  constructor(private readonly core: Core) {}

  invalidate(uri: vscode.Uri): void {
    this._onDidChange.fire(uri);
  }

  async provideTextDocumentContent(uri: vscode.Uri): Promise<string> {
    const d = parseDocPath(uri.path, uri.query);
    if (!d) return '-- URI inválido';
    try {
      if (!(await this.core.ensureConnected(d.connId))) return '-- Ligação cancelada';
      const text = await this.core.client.request<string>('meta.ddl', { id: d.connId, ref: d.ref, body: d.body });
      return text.endsWith('\n') ? text : text + '\n';
    } catch (e) {
      return `-- Não foi possível obter ${d.ref.owner}.${d.ref.name}:\n-- ${errText(e).split('\n').join('\n-- ')}\n`;
    }
  }
}

const SELECTOR: vscode.DocumentSelector = [{ language: 'oraclesql' }, { language: 'sql' }, { scheme: SCHEME }];

/** "Ir para a definição" (Ctrl+clique) sobre nomes de objetos da BD. */
export class DefinitionProviderImpl implements vscode.DefinitionProvider {
  private readonly cache = new Map<string, { at: number; refs: ObjRef[] }>();

  constructor(private readonly core: Core, private readonly bindings: Bindings) {}

  /** Schemas por omissão para nomes sem qualificador. */
  ownersFor(uri: vscode.Uri, connId: string): string[] {
    const out: string[] = [];
    if (uri.scheme === SCHEME) {
      const d = parseDocPath(uri.path, uri.query);
      if (d) out.push(d.ref.owner);
    }
    for (const cur of [this.core.docSchema.get(uri.toString()), this.core.currentSchema.get(connId)]) {
      if (cur && !out.includes(cur)) out.push(cur);
    }
    return out;
  }

  async provideDefinition(doc: vscode.TextDocument, pos: vscode.Position): Promise<vscode.Definition | vscode.DefinitionLink[] | undefined> {
    const connId = this.bindings.get(doc.uri);
    if (!connId || !this.core.connected.has(connId)) return undefined;
    const line = doc.lineAt(pos.line).text;
    if (inLineCommentOrString(line, pos.character)) return undefined;
    const id = identifierAt(line, pos.character);
    if (!id || (SQL_WORDS.has(id.name) && !id.qualifier)) return undefined;
    const owners = this.ownersFor(doc.uri, connId);
    const key = `${connId}|${owners.join(',')}|${id.qualifier ?? ''}|${id.name}`;
    const hit = this.cache.get(key);
    let refs: ObjRef[];
    if (hit && Date.now() - hit.at < 60_000) refs = hit.refs;
    else {
      try {
        const r = await this.core.client.request<ObjRef[][]>('resolve', { id: connId, owners, items: [{ qualifier: id.qualifier, name: id.name }] });
        refs = r[0] ?? [];
      } catch {
        return undefined;
      }
      this.cache.set(key, { at: Date.now(), refs });
    }
    if (refs.length === 0) return undefined;
    const range = new vscode.Range(pos.line, id.start, pos.line, id.end);
    // várias declarações (ex.: MV e a sua tabela gémea): o VS Code mostra a lista para escolher
    return refs.map((r) => ({ targetUri: docUri(connId, r), targetRange: new vscode.Range(0, 0, 0, 0), originSelectionRange: range } as vscode.LocationLink));
  }

  clearCache(): void {
    this.cache.clear();
  }
}

const KIND: Record<string, vscode.CompletionItemKind> = {
  COLUMN: vscode.CompletionItemKind.Field,
  OBJECT: vscode.CompletionItemKind.Class,
  SCHEMA: vscode.CompletionItemKind.Module,
  MEMBER: vscode.CompletionItemKind.Method,
  KEYWORD: vscode.CompletionItemKind.Keyword,
};

const OBJECT_KIND: Record<string, vscode.CompletionItemKind> = {
  TABLE: vscode.CompletionItemKind.Struct,
  VIEW: vscode.CompletionItemKind.Interface,
  'MATERIALIZED VIEW': vscode.CompletionItemKind.Interface,
  PROCEDURE: vscode.CompletionItemKind.Method,
  FUNCTION: vscode.CompletionItemKind.Function,
  PACKAGE: vscode.CompletionItemKind.Module,
  TRIGGER: vscode.CompletionItemKind.Event,
  TYPE: vscode.CompletionItemKind.Class,
  SEQUENCE: vscode.CompletionItemKind.Value,
  SYNONYM: vscode.CompletionItemKind.Reference,
};

/** Autocompletar: objetos, colunas (por alias), membros de packages e palavras-chave. */
export class CompletionProviderImpl implements vscode.CompletionItemProvider {
  constructor(private readonly core: Core, private readonly bindings: Bindings, private readonly defs: DefinitionProviderImpl) {}

  async provideCompletionItems(doc: vscode.TextDocument, pos: vscode.Position): Promise<vscode.CompletionItem[] | undefined> {
    if (doc.uri.scheme === SCHEME) return undefined; // DDL só de leitura
    const connId = this.bindings.get(doc.uri);
    if (!connId || !this.core.connected.has(connId)) return undefined;
    const sql = doc.getText();
    const caret = doc.offsetAt(pos);
    let r: CompleteResult;
    try {
      r = await this.core.client.request<CompleteResult>('complete', { id: connId, sql, caret, owners: this.defs.ownersFor(doc.uri, connId) }, 8000);
    } catch {
      return undefined;
    }
    const range = new vscode.Range(doc.positionAt(r.prefixStart), pos);
    return r.items.map((it, i) => {
      const kind = it.kind === 'OBJECT' && it.type ? OBJECT_KIND[it.type] ?? KIND.OBJECT : KIND[it.kind];
      const ci = new vscode.CompletionItem({ label: it.text, description: it.right || undefined, detail: it.detail ? ` ${it.detail}` : undefined }, kind);
      ci.insertText = it.text;
      ci.filterText = it.text.replace(/^"|"$/g, '');
      ci.range = range;
      ci.sortText = String(i).padStart(5, '0');
      return ci;
    });
  }
}

export function registerLanguageFeatures(core: Core, bindings: Bindings, ddl: DdlProvider): { disposables: vscode.Disposable[]; defs: DefinitionProviderImpl } {
  const defs = new DefinitionProviderImpl(core, bindings);
  const comp = new CompletionProviderImpl(core, bindings, defs);
  return {
    defs,
    disposables: [
      vscode.workspace.registerTextDocumentContentProvider(SCHEME, ddl),
      vscode.languages.registerDefinitionProvider(SELECTOR, defs),
      vscode.languages.registerCompletionItemProvider(SELECTOR, comp, '.'),
    ],
  };
}
