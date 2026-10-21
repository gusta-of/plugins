import * as path from 'node:path';
import * as vscode from 'vscode';
import { ConnectionEditor } from './connectionEditor';
import { ConsoleManager } from './consoles';
import { Core, errText } from './core';
import { findJava } from './java';
import { Bindings, DdlProvider, docUri, registerLanguageFeatures } from './language';
import { ConnDto, ObjRef, isRelation, quotedRef } from './model';
import { ResultsView } from './results';
import { SidecarClient } from './rpc';
import { StructureView } from './structure';
import { ConnectionsTree, Node } from './tree';

export async function activate(context: vscode.ExtensionContext): Promise<void> {
  const log = vscode.window.createOutputChannel('Ora Studio');
  const jar = path.join(context.extensionPath, 'server', 'orastudio-server.jar');
  const client = new SidecarClient(jar, () => findJava(vscode.workspace.getConfiguration('oraStudio').get<string>('javaPath')), (l) => log.appendLine(l));
  const core = new Core(client, log);
  const bindings = new Bindings(context.globalState);
  const ddl = new DdlProvider(core);
  const tree = new ConnectionsTree(core);
  const structure = new StructureView(core);
  const editor = new ConnectionEditor(core, () => tree.refresh());

  const storage = context.globalStorageUri;
  const results = new ResultsView(core, (uri, s, e) => void consoles.goto(uri, s, e));
  const consoles = new ConsoleManager(core, bindings, results, storage);
  const lang = registerLanguageFeatures(core, bindings, ddl);
  consoles.defsCleared = () => lang.defs.clearCache();

  const showError = (e: unknown) => vscode.window.showErrorMessage(`Ora Studio: ${errText(e).split('\n')[0]}`);
  const safe = <A extends unknown[]>(fn: (...a: A) => unknown) => async (...a: A) => { try { await fn(...a); } catch (e) { log.appendLine(`ERRO: ${errText(e)}`); showError(e); } };

  // carrega as ligações guardadas (partilhadas com o Ora Studio Swing e o plugin do IntelliJ)
  void consoles.purgeAll();
  setTimeout(() => consoles.updateStatus(vscode.window.activeTextEditor), 1500);
  void core.refreshConfig().catch((e) => { log.appendLine(`Falha a ler a configuração: ${errText(e)}`); showError(e); });

  // ---- navegação / objetos ----
  const openDdl = async (connId: string, ref: ObjRef, body = false) => {
    const uri = docUri(connId, ref, body);
    const doc = await vscode.workspace.openTextDocument(uri);
    await vscode.languages.setTextDocumentLanguage(doc, 'oraclesql');
    await vscode.window.showTextDocument(doc, { preview: true });
  };

  const openObject = async (connId: string, ref: ObjRef) => {
    // views, MVs e código abrem direto no DDL (o que as constrói); tabelas abrem a estrutura
    if (ref.type === 'TABLE') structure.open(connId, ref);
    else if (isRelation(ref.type)) { await openDdl(connId, ref); structure.open(connId, ref).reveal(vscode.ViewColumn.Beside, true); }
    else await openDdl(connId, ref);
  };

  const nodeObject = (n: Node | undefined): { connId: string; ref: ObjRef } | undefined => (n && n.kind === 'object' ? { connId: n.connId, ref: n.ref } : undefined);
  const nodeConn = (n: Node | undefined): ConnDto | undefined => {
    if (!n) return undefined;
    if (n.kind === 'conn') return n.conn;
    if ('connId' in n) return core.conn(n.connId);
    return undefined;
  };

  const pickConnection = async (): Promise<string | undefined> => {
    if (core.connections.length === 0) { await vscode.commands.executeCommand('oraStudio.newConnection'); return undefined; }
    if (core.connections.length === 1) return core.connections[0].id;
    const p = await vscode.window.showQuickPick(core.connections.map((c) => ({ label: c.name || c.id, description: core.connected.has(c.id) ? 'ligada' : '', id: c.id })), { title: 'Ligação' });
    return p?.id;
  };

  const goToObject = async () => {
    const doc = vscode.window.activeTextEditor?.document;
    const connId = (doc && bindings.get(doc.uri)) ?? (await pickConnection());
    if (!connId || !(await core.ensureConnected(connId))) return;
    const qp = vscode.window.createQuickPick<vscode.QuickPickItem & { ref: ObjRef }>();
    qp.title = `Ir para objeto — ${core.connName(connId)}`;
    qp.placeholder = 'Escreva (mín. 2 letras) o nome do objeto';
    qp.matchOnDescription = true;
    let seq = 0;
    let timer: NodeJS.Timeout | undefined;
    qp.onDidChangeValue((v) => {
      if (timer) clearTimeout(timer);
      if (v.trim().length < 2) { qp.items = []; return; }
      timer = setTimeout(async () => {
        const my = ++seq;
        qp.busy = true;
        try {
          const hits = await client.request<{ ref: ObjRef }[]>('meta.search', { id: connId, text: v.trim(), preferOwner: core.currentSchema.get(connId) });
          if (my !== seq) return;
          qp.items = hits.map((h) => ({ label: h.ref.name, description: `${h.ref.owner} — ${h.ref.type}`, ref: h.ref }));
        } catch (e) { qp.items = [{ label: errText(e).split('\n')[0], ref: undefined as unknown as ObjRef }]; }
        qp.busy = false;
      }, 220);
    });
    qp.onDidAccept(() => { const s = qp.selectedItems[0]; qp.hide(); if (s?.ref) void openObject(connId, s.ref); });
    qp.onDidHide(() => qp.dispose());
    qp.show();
  };

  const configureDrivers = async () => {
    await core.refreshConfig();
    const d = core.drivers.find((x) => x.id === 'oracle') ?? core.drivers[0];
    if (!d) return;
    const pick = await vscode.window.showQuickPick([
      { label: '$(add) Adicionar JAR…', id: 'add', description: 'escolher um ojdbc6 / ojdbc8 / ojdbc11…' },
      { label: '$(search) Detetar no ~/.m2', id: 'detect' },
      { label: '$(cloud-download) Descarregar ojdbc8 (Maven Central)', id: 'download' },
      { label: '$(trash) Remover JAR', id: 'remove', description: d.jars.length ? '' : 'nenhum' },
    ], { title: `Driver «${d.name}» — JARs atuais: ${d.jars.map((j) => path.basename(j)).join(', ') || '(nenhum)'}` });
    if (!pick) return;
    let jars = [...d.jars];
    if (pick.id === 'add') {
      const f = await vscode.window.showOpenDialog({ canSelectMany: true, filters: { 'Drivers JDBC': ['jar'] }, title: 'Escolha o(s) JAR(s) do driver' });
      if (!f) return;
      jars = [...new Set([...jars, ...f.map((x) => x.fsPath)])];
    } else if (pick.id === 'detect') {
      const found = await client.request<string[]>('driver.detect');
      if (found.length === 0) { vscode.window.showInformationMessage('Nenhum ojdbc*.jar encontrado em ~/.m2.'); return; }
      const p = await vscode.window.showQuickPick(found.map((f) => ({ label: path.basename(f), description: f, f })), { title: 'JARs encontrados (um por driver)' });
      if (!p) return;
      jars = [p.f];
    } else if (pick.id === 'download') {
      const go = await vscode.window.showInformationMessage('Descarregar o ojdbc8 (≈ 5 MB) do Maven Central para a pasta de configuração?', { modal: true }, 'Descarregar');
      if (!go) return;
      jars = [await vscode.window.withProgress({ location: vscode.ProgressLocation.Notification, title: 'A descarregar ojdbc8…' }, () => client.request<string>('driver.download', {}, 300000))];
    } else if (pick.id === 'remove') {
      const p = await vscode.window.showQuickPick(jars.map((j) => ({ label: path.basename(j), description: j, j })), { title: 'Remover qual JAR?' });
      if (!p) return;
      jars = jars.filter((x) => x !== p.j);
    }
    await client.request('driver.save', { drivers: core.drivers.map((x) => (x.id === d.id ? { ...x, jars } : x)) });
    await core.refreshConfig();
    vscode.window.showInformationMessage(`Driver «${d.name}»: ${jars.map((j) => path.basename(j)).join(', ') || '(sem JAR)'}`);
  };

  // ---- comandos ----
  const cmd = (id: string, fn: (...a: any[]) => unknown) => vscode.commands.registerCommand(id, safe(fn));
  context.subscriptions.push(
    log, consoles, ...lang.disposables,
    vscode.window.createTreeView('oraStudio.connections', { treeDataProvider: tree, showCollapseAll: true }),
    vscode.window.registerWebviewViewProvider(ResultsView.viewType, results, { webviewOptions: { retainContextWhenHidden: true } }),

    cmd('oraStudio.newConnection', () => editor.open()),
    cmd('oraStudio.editConnection', (n?: Node) => { const c = nodeConn(n); if (c) editor.open(c); }),
    cmd('oraStudio.duplicateConnection', async (n?: Node) => {
      const c = nodeConn(n);
      if (!c) return;
      await client.request('connection.save', { connection: { ...c, id: `${c.id}-copia-${Date.now()}`, name: `${c.name} (cópia)` } });
      await core.refreshConfig();
    }),
    cmd('oraStudio.deleteConnection', async (n?: Node) => {
      const c = nodeConn(n);
      if (!c) return;
      const ok = await vscode.window.showWarningMessage(`Remover a ligação «${c.name}»?`, { modal: true }, 'Remover');
      if (!ok) return;
      await client.request('connection.delete', { id: c.id });
      core.forget(c.id);
      consoles.forgetConnection(c.id);
      await core.refreshConfig();
    }),
    cmd('oraStudio.connect', async (n?: Node) => { const c = nodeConn(n); if (c) { tree.refresh(n); await vscode.commands.executeCommand('list.expand'); } }),
    cmd('oraStudio.disconnect', async (n?: Node) => { const c = nodeConn(n); if (c) { await core.disconnect(c.id); consoles.forgetConnection(c.id); lang.defs.clearCache(); } }),
    cmd('oraStudio.refresh', () => { lang.defs.clearCache(); tree.refresh(); }),

    cmd('oraStudio.newConsole', async (n?: Node) => {
      const connId = nodeConn(n)?.id ?? (await pickConnection());
      if (!connId) return;
      if (!(await core.ensureConnected(connId))) return;
      await consoles.newConsole(connId);
    }),
    cmd('oraStudio.runStatement', () => { const e = vscode.window.activeTextEditor; if (e) return consoles.run(e, 'statement'); }),
    cmd('oraStudio.runScript', () => { const e = vscode.window.activeTextEditor; if (e) return consoles.run(e, 'script'); }),
    cmd('oraStudio.cancel', () => consoles.cancel()),
    cmd('oraStudio.commit', () => consoles.commit()),
    cmd('oraStudio.rollback', () => consoles.rollback()),
    cmd('oraStudio.toggleAutoCommit', () => consoles.toggleAutoCommit()),
    cmd('oraStudio.selectConnection', async () => { const d = vscode.window.activeTextEditor?.document; if (d) await consoles.selectConnection(d); }),

    cmd('oraStudio.selectSchema', async () => { const d = vscode.window.activeTextEditor?.document; if (d) await consoles.selectSchema(d); }),
    cmd('oraStudio.goToObject', goToObject),
    cmd('oraStudio.openObject', (connId: string, ref: ObjRef) => openObject(connId, ref)),
    cmd('oraStudio.openDdl', async (a: string | Node, ref?: ObjRef) => {
      const o = typeof a === 'string' ? { connId: a, ref: ref! } : nodeObject(a);
      if (!o) return;
      await openDdl(o.connId, o.ref);
      if (o.ref.type === 'PACKAGE' || o.ref.type === 'TYPE') await openDdl(o.connId, o.ref, true);
    }),
    cmd('oraStudio.openStructure', (n?: Node) => { const o = nodeObject(n); if (o) structure.open(o.connId, o.ref); }),
    cmd('oraStudio.selectFrom', async (n?: Node) => {
      const o = nodeObject(n);
      if (!o) return;
      if (!(await core.ensureConnected(o.connId))) return;
      await consoles.newConsole(o.connId, `SELECT *\nFROM ${quotedRef(o.ref)}\nWHERE ROWNUM <= 100;\n`);
    }),
    cmd('oraStudio.copyName', (n?: Node) => { const o = nodeObject(n); if (o) return vscode.env.clipboard.writeText(`${o.ref.owner}.${o.ref.name}`); }),
    cmd('oraStudio.configureDrivers', configureDrivers),
    cmd('oraStudio.showLog', () => log.show()),

    vscode.languages.registerCodeLensProvider([{ language: 'oraclesql' }, { language: 'sql' }], consoles),
    vscode.window.onDidChangeVisibleTextEditors(() => consoles.updateStatus(vscode.window.activeTextEditor)),
    vscode.window.onDidChangeActiveTextEditor((e) => consoles.updateStatus(e)),
    vscode.workspace.onDidCloseTextDocument((d) => void (consoles.isConsole(d.uri) ? consoles.discard(d.uri) : consoles.closeSession(d.uri))),
    bindings.onChange(() => consoles.updateStatus(vscode.window.activeTextEditor)),
    { dispose: () => { void client.stop(); } },
  );
  void ddl;
}

export function deactivate(): void {}
