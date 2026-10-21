import * as fs from 'node:fs';
import * as path from 'node:path';
import * as vscode from 'vscode';
import { Core, errText } from './core';
import { Bindings } from './language';
import { ResultsView } from './results';

interface ConsoleState {
  sessionId?: string;
  autoCommit: boolean;
  runId?: string;
  /** deslocamento: posição de cada instrução enviada (para marcar erros) */
  base: number;
}

/** Consolas SQL: ficheiros .sql (em globalStorage) ligados a uma ligação, uma sessão (transação) por ficheiro. */
export class ConsoleManager implements vscode.CodeLensProvider {
  private readonly _lensChange = new vscode.EventEmitter<void>();
  readonly onDidChangeCodeLenses = this._lensChange.event;
  private readonly states = new Map<string, ConsoleState>();
  private readonly diagnostics = vscode.languages.createDiagnosticCollection('oraStudio');
  private readonly status: vscode.StatusBarItem;
  private readonly schemaStatus: vscode.StatusBarItem;
  private runCounter = 0;
  /** runId → documento (uri) que o lançou */
  private readonly runDocs = new Map<string, vscode.Uri>();

  constructor(
    private readonly core: Core,
    private readonly bindings: Bindings,
    private readonly results: ResultsView,
    private readonly storage: vscode.Uri,
  ) {
    this.status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 90);
    this.status.command = 'oraStudio.selectConnection';
    this.schemaStatus = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 89);
    this.schemaStatus.command = 'oraStudio.selectSchema';
    this.updateStatus(vscode.window.activeTextEditor);
    core.client.onNotification((m, p) => this.onNotification(m, p));
    core.onConnectionState(() => this.updateStatus(vscode.window.activeTextEditor));
  }

  dispose(): void {
    this.diagnostics.dispose();
    this.status.dispose();
    this.schemaStatus.dispose();
    this._lensChange.dispose();
  }

  // ---- ligação do ficheiro ----

  /** Linha clicável no topo de cada ficheiro SQL: ligação e schema (abre listas filtráveis). */
  provideCodeLenses(doc: vscode.TextDocument): vscode.CodeLens[] {
    if ((doc.languageId !== 'oraclesql' && doc.languageId !== 'sql') || doc.uri.scheme === 'orastudio') return [];
    const connId = this.bindings.get(doc.uri);
    const top = new vscode.Range(0, 0, 0, 0);
    if (!connId) return [new vscode.CodeLens(top, { title: '$(database) Associar ligação Oracle…', command: 'oraStudio.selectConnection' })];
    const schema = this.core.docSchema.get(doc.uri.toString()) ?? this.core.currentSchema.get(connId) ?? '(ligar)';
    return [
      new vscode.CodeLens(top, { title: `$(database) ${this.core.connName(connId)}`, tooltip: 'Mudar de ligação', command: 'oraStudio.selectConnection' }),
      new vscode.CodeLens(top, { title: `$(symbol-namespace) Schema: ${schema} ▾`, tooltip: 'Escolher o schema desta consola (lista filtrável)', command: 'oraStudio.selectSchema' }),
    ];
  }

  /** Atualiza o item da barra de estado e o contexto `oraStudio.bound` para o editor ativo. */
  updateStatus(editor: vscode.TextEditor | undefined): void {
    const doc = editor?.document;
    const lang = doc?.languageId;
    const relevant = !!doc && (lang === 'oraclesql' || lang === 'sql') && doc.uri.scheme !== 'orastudio';
    const connId = relevant ? this.bindings.get(doc!.uri) : undefined;
    void vscode.commands.executeCommand('setContext', 'oraStudio.bound', !!connId);
    this._lensChange.fire();
    if (!relevant) { this.status.hide(); this.schemaStatus.hide(); return; }
    const st = this.states.get(doc!.uri.toString());
    this.status.text = connId
      ? `$(database) ${this.core.connName(connId)}${st && !st.autoCommit ? '  (manual commit)' : ''}`
      : '$(database) Associar ligação Oracle…';
    this.status.tooltip = connId ? 'Ligação Oracle deste ficheiro (clique para mudar)' : 'Escolher a ligação Oracle para executar este ficheiro';
    this.status.show();
    if (connId) {
      this.schemaStatus.text = `$(symbol-namespace) ${this.core.docSchema.get(doc!.uri.toString()) ?? this.core.currentSchema.get(connId) ?? 'schema'}`;
      this.schemaStatus.tooltip = 'Schema desta consola: nomes sem prefixo são resolvidos aqui (clique para mudar)';
      this.schemaStatus.show();
    } else this.schemaStatus.hide();
  }

  /** Escolhe o schema da consola (ALTER SESSION SET CURRENT_SCHEMA); o autocompletar de schemas não muda. */
  async selectSchema(doc: vscode.TextDocument): Promise<void> {
    const connId = this.bindings.get(doc.uri);
    if (!connId || !(await this.core.ensureConnected(connId))) return;
    const schemas = await this.core.client.request<string[]>('meta.schemas', { id: connId });
    const own = this.core.currentSchema.get(connId);
    const active = this.core.docSchema.get(doc.uri.toString()) ?? own;
    const picked = await vscode.window.showQuickPick(
      [...schemas].sort((a, b) => (a === own ? -1 : b === own ? 1 : 0)).map((s) => ({ label: s, description: s === own ? 'o meu schema' : s === active ? 'atual' : '' })),
      { title: 'Schema da consola', placeHolder: 'Escreva para filtrar' },
    );
    if (!picked) return;
    if (picked.label === own) this.core.docSchema.delete(doc.uri.toString());
    else this.core.docSchema.set(doc.uri.toString(), picked.label);
    const st = this.states.get(doc.uri.toString());
    if (st?.sessionId) await this.core.client.request('session.schema', { sessionId: st.sessionId, schema: picked.label });
    this.updateStatus(vscode.window.activeTextEditor);
    this.defsCleared?.();
  }

  /** chamado quando o schema muda, para limpar a cache de "ir para a definição" */
  defsCleared?: () => void;

  /** Escolhe a ligação (QuickPick) e associa-a ao documento. */
  async selectConnection(doc: vscode.TextDocument): Promise<string | undefined> {
    if (this.core.connections.length === 0) {
      const go = await vscode.window.showInformationMessage('Ainda não há ligações Oracle.', 'Criar ligação');
      if (go) await vscode.commands.executeCommand('oraStudio.newConnection');
      return undefined;
    }
    const picked = await vscode.window.showQuickPick(
      this.core.connections.map((c) => ({ label: c.name || c.effectiveUrl || c.id, description: this.core.connected.has(c.id) ? 'ligada' : '', detail: c.effectiveUrl, id: c.id })),
      { title: 'Ligação Oracle para este ficheiro', placeHolder: 'Escolha a ligação' },
    );
    if (!picked) return undefined;
    await this.rebind(doc.uri, picked.id);
    return picked.id;
  }

  private async rebind(uri: vscode.Uri, connId: string): Promise<void> {
    await this.closeSession(uri);
    this.core.docSchema.delete(uri.toString());
    await this.bindings.set(uri, connId);
    this.updateStatus(vscode.window.activeTextEditor);
  }

  // ---- ciclo de vida: as consolas não ficam guardadas ----

  private get dir(): string { return path.join(this.storage.fsPath, 'consoles'); }

  isConsole(uri: vscode.Uri): boolean {
    return uri.scheme === 'file' && path.dirname(uri.fsPath) === this.dir;
  }

  /** Ao fechar uma consola: termina a sessão, apaga o ficheiro e esquece ligação/schema. */
  async discard(uri: vscode.Uri): Promise<void> {
    await this.closeSession(uri);
    this.states.delete(uri.toString());
    this.core.docSchema.delete(uri.toString());
    this.diagnostics.delete(uri);
    try { fs.rmSync(uri.fsPath, { force: true }); } catch { /* ignore */ }
    await this.bindings.set(uri, undefined);
  }

  /** No arranque: apaga consolas de execuções anteriores. */
  async purgeAll(): Promise<void> {
    let names: string[] = [];
    try { names = fs.readdirSync(this.dir); } catch { return; }
    for (const n of names) {
      const f = path.join(this.dir, n);
      try { fs.rmSync(f, { force: true }); } catch { /* ignore */ }
      await this.bindings.set(vscode.Uri.file(f), undefined);
    }
  }

  // ---- criar consola ----

  async newConsole(connId: string, initial = ''): Promise<vscode.TextEditor> {
    const dir = vscode.Uri.joinPath(this.storage, 'consoles');
    fs.mkdirSync(dir.fsPath, { recursive: true });
    const base = this.core.connName(connId).replace(/[^\w.-]+/g, '_');
    let n = 1;
    let file = path.join(dir.fsPath, `${base}.sql`);
    while (fs.existsSync(file)) file = path.join(dir.fsPath, `${base}-${++n}.sql`);
    fs.writeFileSync(file, initial || '-- Ctrl+Enter executa a instrução sob o cursor; Ctrl+Shift+Enter executa o script todo\n');
    const doc = await vscode.workspace.openTextDocument(vscode.Uri.file(file));
    await vscode.languages.setTextDocumentLanguage(doc, 'oraclesql');
    await this.bindings.set(doc.uri, connId);
    const ed = await vscode.window.showTextDocument(doc, { preview: false });
    this.updateStatus(ed);
    return ed;
  }

  // ---- sessão ----

  private async ensureSession(doc: vscode.TextDocument, connId: string): Promise<{ st: ConsoleState; sessionId: string }> {
    const key = doc.uri.toString();
    let st = this.states.get(key);
    if (!st) { st = { autoCommit: this.core.conn(connId)?.autoCommit ?? true, base: 0 }; this.states.set(key, st); }
    if (!st.sessionId) {
      const r = await this.core.client.request<{ sessionId: string }>('session.open', { id: connId, autoCommit: st.autoCommit, schema: this.core.docSchema.get(doc.uri.toString()) });
      st.sessionId = r.sessionId;
    }
    return { st, sessionId: st.sessionId };
  }

  async closeSession(uri: vscode.Uri): Promise<void> {
    const st = this.states.get(uri.toString());
    if (st?.sessionId) {
      try { await this.core.client.request('session.close', { sessionId: st.sessionId }); } catch { /* ignore */ }
      st.sessionId = undefined;
    }
  }

  /** As sessões abertas numa ligação que foi desligada/editada já não existem. */
  forgetConnection(connId: string): void {
    for (const [k, st] of this.states) {
      if (this.bindings.get(vscode.Uri.parse(k)) === connId) st.sessionId = undefined;
    }
  }

  // ---- executar ----

  /** [mode] "statement": a instrução sob o cursor (ou a seleção); "script": todo o documento (ou a seleção). */
  async run(editor: vscode.TextEditor, mode: 'statement' | 'script'): Promise<void> {
    const doc = editor.document;
    let connId = this.bindings.get(doc.uri);
    if (!connId) connId = await this.selectConnection(doc);
    if (!connId) return;
    try {
      if (!(await this.core.ensureConnected(connId))) return;
    } catch (e) {
      vscode.window.showErrorMessage(`Falha a ligar: ${errText(e).split('\n')[0]}`);
      return;
    }
    const sel = editor.selection;
    const hasSel = !sel.isEmpty && doc.getText(sel).trim().length > 0;
    const text = hasSel ? doc.getText(sel) : doc.getText();
    const base = hasSel ? doc.offsetAt(sel.start) : 0;
    const caret = hasSel ? 0 : doc.offsetAt(sel.active);
    const effectiveMode = hasSel ? 'script' : mode;
    try {
      const { st, sessionId } = await this.ensureSession(doc, connId);
      const runId = `run-${++this.runCounter}`;
      st.runId = runId;
      this.runDocs.set(runId, doc.uri);
      this.diagnostics.set(doc.uri, []);
      this.results.reset(`${path.basename(doc.uri.fsPath || 'consola')} — ${this.core.connName(connId)}`, doc.uri.toString());
      void this.results.reveal();
      await this.core.client.request('session.run', {
        sessionId, runId, sql: text, mode: effectiveMode, caret, baseOffset: base,
        rowLimit: this.core.setting('rowLimit', 500), stopOnError: this.core.setting('stopOnError', true),
      });
    } catch (e) {
      vscode.window.showErrorMessage(`Executar: ${errText(e).split('\n')[0]}`);
    }
  }

  private onNotification(method: string, p: any): void {
    if (!method.startsWith('run/')) return;
    this.results.onRunEvent(method, p);
    if (method === 'run/error') this.markError(p);
  }

  /** Sublinha o erro (posição devolvida pelo driver, ou a instrução inteira). */
  private markError(p: any): void {
    const uri = this.runDocs.get(p.runId);
    const doc = uri && vscode.workspace.textDocuments.find((d) => d.uri.toString() === uri.toString());
    if (!doc) return;
    const len = doc.getText().length;
    const start = Math.max(0, Math.min(p.position >= 0 ? p.position : p.start, len));
    let end = p.position >= 0 ? start : Math.min(p.end, len);
    if (p.position >= 0) {
      const t = doc.getText();
      end = start;
      while (end < len && !/[\s,;()]/.test(t[end])) end++;
      if (end === start) end = Math.min(start + 1, len);
    }
    const range = new vscode.Range(doc.positionAt(start), doc.positionAt(end));
    const d = new vscode.Diagnostic(range, String(p.message), vscode.DiagnosticSeverity.Error);
    d.source = 'Oracle';
    this.diagnostics.set(doc.uri, [...(this.diagnostics.get(doc.uri) ?? []), d]);
  }

  async goto(docUri: string, start: number, end: number): Promise<void> {
    const doc = await vscode.workspace.openTextDocument(vscode.Uri.parse(docUri));
    const ed = await vscode.window.showTextDocument(doc, { preserveFocus: false });
    const r = new vscode.Range(doc.positionAt(start), doc.positionAt(Math.max(start, end)));
    ed.selection = new vscode.Selection(r.start, r.end);
    ed.revealRange(r, vscode.TextEditorRevealType.InCenterIfOutsideViewport);
  }

  // ---- controlo de transação ----

  private current(): { doc: vscode.TextDocument; st: ConsoleState } | undefined {
    const doc = vscode.window.activeTextEditor?.document;
    const st = doc && this.states.get(doc.uri.toString());
    return doc && st?.sessionId ? { doc, st } : undefined;
  }

  async cancel(): Promise<void> {
    const c = this.current();
    if (c) await this.core.client.request('session.cancel', { sessionId: c.st.sessionId });
  }

  async commit(): Promise<void> { await this.tx('session.commit', 'Commit concluído'); }
  async rollback(): Promise<void> { await this.tx('session.rollback', 'Rollback concluído'); }

  private async tx(method: string, ok: string): Promise<void> {
    const c = this.current();
    if (!c) { vscode.window.showInformationMessage('Execute algo primeiro (esta consola ainda não tem sessão).'); return; }
    try {
      await this.core.client.request(method, { sessionId: c.st.sessionId });
      vscode.window.setStatusBarMessage(`$(check) ${ok}`, 3000);
    } catch (e) {
      vscode.window.showErrorMessage(errText(e));
    }
  }

  async toggleAutoCommit(): Promise<void> {
    const doc = vscode.window.activeTextEditor?.document;
    if (!doc) return;
    const connId = this.bindings.get(doc.uri);
    if (!connId) return;
    const { st, sessionId } = await this.ensureSession(doc, connId);
    st.autoCommit = !st.autoCommit;
    await this.core.client.request('session.autoCommit', { sessionId, value: st.autoCommit });
    this.updateStatus(vscode.window.activeTextEditor);
    vscode.window.setStatusBarMessage(`Auto-commit ${st.autoCommit ? 'ligado' : 'desligado (use Commit/Rollback)'}`, 3000);
  }
}
