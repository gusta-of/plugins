import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import Module from 'node:module';

// ---- `vscode` falso, só com o que a extensão usa ----
const outputs = new Map<string, string[]>();
const commands = new Map<string, (...a: unknown[]) => unknown>();
const settings = new Map<string, unknown>();
const posted: any[] = [];
const dialogs: { quickPick?: (items: any[]) => any; warning?: string; error: string[]; open?: string } = { error: [] };
let viewProvider: any;
let msgHandler: ((m: any) => unknown) | undefined;

const fake = {
  StatusBarAlignment: { Left: 1 },
  ConfigurationTarget: { Global: 1 },
  EventEmitter: class { private ls: ((e: any) => void)[] = []; event = (f: (e: any) => void) => { this.ls.push(f); return { dispose() {} }; }; fire(e: any) { this.ls.forEach((f) => f(e)); } dispose() {} },
  ViewColumn: { Active: -1, Beside: -2 },
  ThemeIcon: class { constructor(public id: string) {} },
  ThemeColor: class { constructor(public id: string) {} },
  Uri: { file: (p: string) => ({ fsPath: p }), joinPath: (b: any, ...p: string[]) => ({ fsPath: path.join(b.fsPath, ...p), toString: () => path.join(b.fsPath, ...p) }) },
  window: {
    createWebviewPanel: (_t: string, title: string) => {
      const l: string[] = [];
      outputs.set(title, l);
      let handler: ((m: any) => void) | undefined;
      return {
        reveal() {}, dispose() {}, onDidDispose: () => ({ dispose() {} }),
        webview: {
          set html(_v: string) { setTimeout(() => handler?.({ type: 'ready' }), 0); },
          onDidReceiveMessage: (h: (m: any) => void) => { handler = h; return { dispose() {} }; },
          postMessage: async (m: any) => { if (m.type === 'init') { l.length = 0; l.push(...m.lines); } else if (m.type === 'append') l.push(...m.lines); return true; },
        },
      };
    },
    createStatusBarItem: () => ({ show() {}, hide() {}, dispose() {} }),
    showErrorMessage: async (m: string) => { dialogs.error.push(m); },
    showWarningMessage: async () => dialogs.warning,
    showQuickPick: async (items: any[]) => dialogs.quickPick?.(items),
    showOpenDialog: async () => (dialogs.open ? [{ fsPath: dialogs.open }] : undefined),
    registerWebviewViewProvider: (_t: string, p: unknown) => { viewProvider = p; return { dispose() {} }; },
  },
  commands: {
    registerCommand: (id: string, fn: (...a: unknown[]) => unknown) => { commands.set(id, fn); return { dispose() {} }; },
    executeCommand: async (id: string, ...a: unknown[]) => commands.get(id)?.(...a),
  },
  workspace: {
    getConfiguration: () => ({
      get: (k: string, d: unknown) => (settings.has(k) ? settings.get(k) : d),
      update: async (k: string, v: unknown) => { settings.set(k, v); },
    }),
    onDidChangeConfiguration: () => ({ dispose() {} }),
  },
};
const origLoad = (Module as any)._load;
(Module as any)._load = function (request: string, ...rest: unknown[]) {
  return request === 'vscode' ? fake : origLoad.call(this, request, ...rest);
};

const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), 'pemwl-glue-'));
// NUNCA tocar no ~/.m2 real: a limpeza do classpath apaga ~/.m2/.../modules/pem
process.env.PEMWL_M2_PEM_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'pemwl-m2-'));
const touch = (p: string) => { fs.mkdirSync(path.dirname(p), { recursive: true }); fs.writeFileSync(p, 'x'); };
const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));

test('extensão: ativa, regista comandos/painel e o fluxo de deploy com botão travado e progresso', async () => {
  const ext = await import('../src/extension');
  const ctx: any = { subscriptions: [], extensionUri: { fsPath: path.resolve(__dirname, '..') } };
  ext.activate(ctx);

  const pkg = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'package.json'), 'utf8'));
  for (const c of pkg.contributes.commands) assert.ok(commands.has(c.command), `comando registado: ${c.command}`);
  assert.ok(viewProvider, 'painel (webview) registado');

  // painel: HTML com CSP, botões e campos
  const view: any = {
    webview: {
      options: undefined, html: '', cspSource: 'vscode-resource:', asWebviewUri: (u: any) => u,
      onDidReceiveMessage: (cb: any) => { msgHandler = cb; return { dispose() {} }; },
      postMessage: (m: any) => { posted.push(m); return Promise.resolve(true); },
    },
    visible: true, onDidChangeVisibility: () => ({ dispose() {} }),
  };
  viewProvider.resolveWebviewView(view);
  assert.match(view.webview.html, /Content-Security-Policy/);
  for (const cmd of ['startWebLogic', 'stopWebLogic', 'killWebLogic', 'clearClasspath', 'cleanTargets', 'killJars', 'buildBackend', 'buildFrontend', 'buildFrontendFast', 'buildModules', 'deployBackend', 'deployFrontend', 'deployModules', 'cancel'])
    assert.ok(view.webview.html.includes(`data-cmd="${cmd}"`), `botão ${cmd}`);
  await msgHandler!({ type: 'ready' });
  assert.equal(posted.at(-1).type, 'state');
  assert.equal(posted.at(-1).settings.workspacePath, '', 'campos vêm vazios por omissão');

  // sem caminhos: erro claro e nada corre
  await msgHandler!({ type: 'cmd', name: 'deployBackend' });
  assert.ok(dialogs.error.some((e) => e.includes('workspace')), dialogs.error.join('|'));

  // guardar os campos a partir do painel
  const ws = tmp(); const dom = tmp();
  touch(path.join(ws, 'pem-fh-app/target/pem-fh-app-2.5.0.war'));
  touch(path.join(ws, 'pem-dm-app/target/pem-dm-app-2.5.0.war'));
  await msgHandler!({ type: 'set', key: 'workspacePath', value: ws });
  await msgHandler!({ type: 'set', key: 'domainPath', value: dom });
  await msgHandler!({ type: 'set', key: 'skipTests', value: true });
  assert.equal(settings.get('workspacePath'), ws);
  assert.equal(posted.at(-1).settings.skipTests, true);

  // deploy de 2 módulos: o QuickPick devolve a seleção
  dialogs.quickPick = (items) => items.filter((i) => ['pem-fh-app', 'pem-dm-app'].includes(i.label));
  const done = msgHandler!({ type: 'cmd', name: 'deployBackend' });
  await done;
  assert.ok(fs.existsSync(path.join(dom, 'servers/AdminServer/upload/pem-fh-app-2.5.0.war')));
  assert.ok(fs.existsSync(path.join(dom, 'servers/AdminServer/upload/pem-dm-app-2.5.0.war')));
  const progress = posted.filter((m) => m.type === 'progress');
  assert.equal(progress.at(-1).finished, true);
  assert.equal(progress.at(-1).done, 2);
  const states = posted.filter((m) => m.type === 'state');
  assert.ok(states.some((s) => s.locked.includes('deployBackend')), 'o botão ficou travado durante a tarefa');
  assert.deepEqual(states.at(-1).locked, [], 'e destravou no fim');
  await new Promise((r) => setTimeout(r, 200)); // o registo é enviado em lotes
  assert.ok(outputs.get('PEM WL — Tarefas')!.some((l) => l.includes('Deploy Backend: concluído com sucesso')));

  // limpeza com confirmação recusada não apaga nada
  touch(path.join(ws, 'x/target/a.class'));
  dialogs.warning = undefined;
  await msgHandler!({ type: 'cmd', name: 'clearClasspath' });
  assert.ok(fs.existsSync(path.join(ws, 'x/target/a.class')), 'sem confirmação não apaga');
  dialogs.warning = 'Sim, apagar';
  await msgHandler!({ type: 'cmd', name: 'clearClasspath' });
  assert.ok(!fs.existsSync(path.join(ws, 'x/target')), 'confirmado: apagou');

  // browse
  dialogs.open = '/tmp';
  await msgHandler!({ type: 'browse', key: 'domainPath' });
  assert.equal(settings.get('domainPath'), '/tmp');
});

test('extensão: o cancelar para uma tarefa longa e o botão destrava', async () => {
  const ws = tmp(); const dom = tmp();
  settings.set('workspacePath', ws); settings.set('domainPath', dom);
  // build com um "mvn" falso que dorme: pasta pem-common-beans com um mvn no PATH
  const bin = tmp();
  fs.writeFileSync(path.join(bin, 'mvn'), '#!/bin/bash\nsleep 30\n', { mode: 0o755 });
  process.env.PATH = `${bin}:${process.env.PATH}`;
  fs.mkdirSync(path.join(ws, 'pem-common-beans'), { recursive: true });
  const before = posted.length;
  const run = msgHandler!({ type: 'cmd', name: 'buildBackend' });
  await wait(1200);
  assert.ok(posted.slice(before).some((m) => m.type === 'state' && m.locked.includes('buildBackend')), 'botão vermelho durante o build');
  await msgHandler!({ type: 'cmd', name: 'cancel' });
  const t0 = Date.now();
  await run;
  assert.ok(Date.now() - t0 < 6000);
  assert.deepEqual(posted.filter((m) => m.type === 'state').at(-1).locked, []);
});
