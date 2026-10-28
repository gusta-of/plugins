import * as fs from 'node:fs';
import { ArtifactsView } from './artifactsView';
import { LogChannel, LogHub } from './logHub';
import { WlWatcher } from './wlStatus';
import * as vscode from 'vscode';
import * as M from './modules';
import { Operations, BuildOptions, Paths } from './operations';
import { LogKind, TaskRunner } from './runner';
import { PanelProvider, PanelState, Settings } from './panel';

const SECTION = 'pemWlTools';

function cfg(): vscode.WorkspaceConfiguration {
  return vscode.workspace.getConfiguration(SECTION);
}

function readSettings(): Settings {
  const c = cfg();
  return {
    workspacePath: c.get<string>('workspacePath', ''),
    domainPath: c.get<string>('domainPath', ''),
    mavenProfile: c.get<string>('mavenProfile', ''),
    skipTests: c.get<boolean>('skipTests', false),
    localMock: c.get<boolean>('localMock', false),
    threads: M.sanitizeThreads(c.get<string>('threads', '1C')),
  };
}

const prefix: Record<LogKind, string> = { info: '', ok: '✔ ', err: '✘ ', sys: '', warn: '⚠ ' };

export function activate(context: vscode.ExtensionContext): void {
  const hub = new LogHub([{ id: 'tasks', label: 'Tarefas' }, { id: 'wl', label: 'WebLogic' }]);
  const tasksOut = hub.channel('tasks');
  const wlOut = hub.channel('wl');
  const logTo = (ch: LogChannel) => (text: string, kind: LogKind = 'info') => ch.appendLine(prefix[kind] + text);

  const runner = new TaskRunner(logTo(tasksOut));
  const wlRunner = new TaskRunner(logTo(wlOut));
  const ops = new Operations(runner, wlRunner, logTo(wlOut));

  const statusItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 50);
  statusItem.text = '$(circle-filled) WebLogic';
  statusItem.tooltip = 'WebLogic iniciado pela extensão (clica para parar)';
  statusItem.command = 'pemWlTools.stopWebLogic';
  statusItem.backgroundColor = new vscode.ThemeColor('statusBarItem.errorBackground');

  // botões "travados" (vermelhos) enquanto o processo que iniciaram corre
  const locked = new Set<string>();
  const state = (): PanelState => ({ settings: readSettings(), locked: [...locked] });

  const panel = new PanelProvider(
    context.extensionUri,
    state,
    async (key, value) => { await cfg().update(key, value, vscode.ConfigurationTarget.Global); panel.pushState(); },
    async (key) => {
      const picked = await vscode.window.showOpenDialog({
        canSelectFiles: false, canSelectFolders: true, canSelectMany: false,
        title: key === 'workspacePath' ? 'Pasta do workspace do PEM' : 'Pasta do domínio WebLogic (AdminServer)',
        defaultUri: readSettings()[key] ? vscode.Uri.file(readSettings()[key]) : undefined,
      });
      if (picked?.[0]) { await cfg().update(key, picked[0].fsPath, vscode.ConfigurationTarget.Global); panel.pushState(); }
    },
  );
  runner.onProgress = (e) => panel.pushProgress(e);
  wlRunner.onProgress = (e) => panel.pushProgress(e);

  const unlockAll = (r: TaskRunner, keys: Set<string>) => () => { keys.forEach((k) => locked.delete(k)); keys.clear(); panel.pushState(); };
  const taskKeys = new Set<string>();
  const wlKeys = new Set<string>();
  runner.onFinished = unlockAll(runner, taskKeys);
  wlRunner.onFinished = unlockAll(wlRunner, wlKeys);
  const wlWatch = new WlWatcher();
  const artifacts = new ArtifactsView(runner, wlWatch);
  ops.onWebLogicStart = (p) => wlWatch.start(p.domain);
  ops.onWebLogicLine = (l) => wlWatch.feed(l, 'out');
  ops.onWebLogicExit = () => { locked.delete('startWebLogic'); statusItem.hide(); wlWatch.stop(); panel.pushState(); };

  const err = (m: string) => { vscode.window.showErrorMessage(`PEM WL Tools: ${m}`); return undefined; };

  /** Valida os dois caminhos (pede para os preencher se faltarem). */
  const paths = (): Paths | undefined => {
    const s = readSettings();
    if (!s.workspacePath) return err('indica o caminho do workspace (campo "Workspace" do painel).');
    if (!s.domainPath) return err('indica o caminho do AdminServer / domínio WebLogic.');
    if (!fs.existsSync(s.workspacePath)) return err(`workspace inválido: ${s.workspacePath}`);
    if (!fs.existsSync(s.domainPath)) return err(`domínio WebLogic inválido: ${s.domainPath}`);
    return { workspace: s.workspacePath, domain: s.domainPath };
  };
  const workspaceOnly = (): Paths | undefined => {
    const s = readSettings();
    if (!s.workspacePath) return err('indica o caminho do workspace (campo "Workspace" do painel).');
    if (!fs.existsSync(s.workspacePath)) return err(`workspace inválido: ${s.workspacePath}`);
    return { workspace: s.workspacePath, domain: s.domainPath };
  };
  const buildOptions = (): BuildOptions => {
    const s = readSettings();
    return { profile: s.mavenProfile, skipTests: s.skipTests, localMock: s.localMock, threads: s.threads };
  };

  /** Regista um comando de tarefa: abre o canal certo, trava o botão e destrava quando acabar. */
  const task = (name: string, r: TaskRunner, out: LogChannel, keys: Set<string>, fn: () => Thenable<unknown> | Promise<unknown> | undefined) =>
    context.subscriptions.push(vscode.commands.registerCommand(`pemWlTools.${name}`, async () => {
      const before = r.busy;
      const p = fn();
      if (p === undefined) return; // validação falhou / diálogo cancelado
      if (!before) out.show(true);
      if (!before && r.busy) { locked.add(name); keys.add(name); panel.pushState(); }
      await p;
    }));

  const pickModules = async (title: string, entries: M.ModuleEntry[]): Promise<M.ModuleEntry[] | undefined> => {
    const items = entries.map((e) => ({ label: e.name, description: e.kind === 'backend' ? 'Backend' : 'Frontend', entry: e }));
    const picked = await vscode.window.showQuickPick(items, { canPickMany: true, title, placeHolder: 'Escreve para filtrar; Espaço marca; Enter confirma', matchOnDescription: true });
    return picked && picked.length ? picked.map((p) => p.entry) : undefined;
  };

  // ---- WebLogic ----
  context.subscriptions.push(vscode.commands.registerCommand('pemWlTools.startWebLogic', () => {
    const p = paths();
    if (!p) return;
    wlOut.show(true);
    if (ops.startWebLogic(p)) { locked.add('startWebLogic'); statusItem.show(); panel.pushState(); }
  }));
  task('stopWebLogic', wlRunner, wlOut, wlKeys, () => { const p = paths(); return p && ops.stopWebLogic(p); });
  task('killWebLogic', wlRunner, wlOut, wlKeys, () => { const p = paths(); return p && ops.killWebLogic(p); });

  // ---- limpeza ----
  task('clearClasspath', runner, tasksOut, taskKeys, () => {
    const p = paths();
    if (!p) return undefined;
    return (async () => {
      const ok = await vscode.window.showWarningMessage(
        'O AdminServer (e managed servers) estão PARADOS?\nVai apagar cache do domínio, todas as pastas target/ e ~/.m2/.../modules/pem (exceto fonts).',
        { modal: true }, 'Sim, apagar');
      if (ok) { tasksOut.show(true); const t = ops.clearClasspath(p); locked.add('clearClasspath'); taskKeys.add('clearClasspath'); panel.pushState(); await t; }
    })();
  });
  task('cleanTargets', runner, tasksOut, taskKeys, () => { const p = workspaceOnly(); return p && ops.cleanTargets(p); });
  task('killJars', runner, tasksOut, taskKeys, () => ops.killModuleJars());

  // ---- build ----
  task('buildBackend', runner, tasksOut, taskKeys, () => { const p = workspaceOnly(); return p && ops.buildBackend(p, buildOptions()); });
  task('buildFrontend', runner, tasksOut, taskKeys, () => { const p = workspaceOnly(); return p && ops.buildFrontend(p, buildOptions(), false); });
  task('buildFrontendFast', runner, tasksOut, taskKeys, () => { const p = workspaceOnly(); return p && ops.buildFrontend(p, buildOptions(), true); });
  task('buildModules', runner, tasksOut, taskKeys, () => {
    const p = workspaceOnly();
    if (!p) return undefined;
    return (async () => {
      const sel = await pickModules('Build isolado — escolhe os módulos', M.buildEntries());
      if (sel) { tasksOut.show(true); const t = ops.buildModules(p, sel, buildOptions()); locked.add('buildModules'); taskKeys.add('buildModules'); panel.pushState(); await t; }
    })();
  });

  // ---- deploy ----
  task('deployBackend', runner, tasksOut, taskKeys, () => {
    const p = paths();
    if (!p) return undefined;
    return (async () => {
      const sel = await pickModules('Deploy Backend — escolhe os módulos', M.backendDeploy.map((name): M.ModuleEntry => ({ name, kind: 'backend' })));
      if (sel) { tasksOut.show(true); const t = ops.deployBackend(p, sel.map((s) => s.name)); locked.add('deployBackend'); taskKeys.add('deployBackend'); panel.pushState(); await t; }
    })();
  });
  task('deployFrontend', runner, tasksOut, taskKeys, () => {
    const p = paths();
    if (!p) return undefined;
    return (async () => {
      const sel = await pickModules('Deploy Frontend — escolhe os módulos', Object.keys(M.frontendDeploy).map((name): M.ModuleEntry => ({ name, kind: 'frontend' })));
      if (sel) { tasksOut.show(true); const t = ops.deployFrontend(p, sel.map((s) => s.name)); locked.add('deployFrontend'); taskKeys.add('deployFrontend'); panel.pushState(); await t; }
    })();
  });
  task('deployModules', runner, tasksOut, taskKeys, () => {
    const p = paths();
    if (!p) return undefined;
    return (async () => {
      const sel = await pickModules('Deploy isolado — escolhe os módulos', M.deployEntries());
      if (sel) { tasksOut.show(true); const t = ops.deployModules(p, sel); locked.add('deployModules'); taskKeys.add('deployModules'); panel.pushState(); await t; }
    })();
  });

  context.subscriptions.push(
    vscode.commands.registerCommand('pemWlTools.cancel', () => { runner.cancel(); wlRunner.cancel(); }),
    vscode.window.registerWebviewViewProvider(PanelProvider.viewType, panel),
    vscode.window.registerWebviewViewProvider(ArtifactsView.viewType, artifacts, { webviewOptions: { retainContextWhenHidden: true } }),
    { dispose: () => wlWatch.stop() },
    vscode.window.registerWebviewViewProvider(LogHub.viewType, hub, { webviewOptions: { retainContextWhenHidden: true } }),
    hub,
    vscode.workspace.onDidChangeConfiguration((e) => { if (e.affectsConfiguration(SECTION)) panel.pushState(); }),
    statusItem,
  );
}

export function deactivate(): void {}
