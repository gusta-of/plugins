import * as vscode from 'vscode';
import * as M from './modules';
import { ProgressEvent } from './runner';

export interface Settings {
  workspacePath: string;
  domainPath: string;
  mavenProfile: string;
  skipTests: boolean;
  localMock: boolean;
  threads: string;
}

export interface PanelState {
  settings: Settings;
  /** botões "travados" (vermelhos): comandos a correr. */
  locked: string[];
}

/** Painel lateral (webview) com os campos e os botões compactos agrupados por categoria. */
export class PanelProvider implements vscode.WebviewViewProvider {
  static readonly viewType = 'pemWlTools.panel';
  private view?: vscode.WebviewView;
  private lastProgress?: ProgressEvent;

  constructor(
    private readonly ext: vscode.Uri,
    private readonly getState: () => PanelState,
    private readonly onSet: (key: keyof Settings, value: string | boolean) => Thenable<void> | void,
    private readonly onBrowse: (key: 'workspacePath' | 'domainPath') => Thenable<void> | void,
  ) {}

  resolveWebviewView(view: vscode.WebviewView): void {
    this.view = view;
    view.webview.options = { enableScripts: true, localResourceRoots: [vscode.Uri.joinPath(this.ext, 'media')] };
    view.webview.html = this.html(view.webview);
    view.webview.onDidReceiveMessage(async (m: { type: string; [k: string]: unknown }) => {
      switch (m.type) {
        case 'ready': this.pushState(); if (this.lastProgress) this.pushProgress(this.lastProgress); break;
        case 'set': await this.onSet(m.key as keyof Settings, m.value as string | boolean); break;
        case 'browse': await this.onBrowse(m.key as 'workspacePath' | 'domainPath'); break;
        case 'cmd': await vscode.commands.executeCommand(`pemWlTools.${m.name}`); break;
      }
    });
    view.onDidChangeVisibility(() => { if (view.visible) { this.pushState(); if (this.lastProgress) this.pushProgress(this.lastProgress); } });
  }

  pushState(): void {
    this.view?.webview.postMessage({ type: 'state', ...this.getState() });
  }

  pushProgress(e: ProgressEvent): void {
    this.lastProgress = e;
    this.view?.webview.postMessage({ type: 'progress', ...e });
  }

  private html(webview: vscode.Webview): string {
    const css = webview.asWebviewUri(vscode.Uri.joinPath(this.ext, 'media', 'codicon.css'));
    const nonce = Math.random().toString(36).slice(2);
    const btn = (cmd: string, icon: string, tip: string, text = '') =>
      `<button class="ib" data-cmd="${cmd}" title="${tip}"><i class="codicon codicon-${icon}"></i>${text ? `<span>${text}</span>` : ''}</button>`;
    const group = (title: string, ...buttons: string[]) => `<div class="grp"><span class="gt">${title}</span>${buttons.join('')}</div>`;
    const threads = M.threadOptions.map((t) => `<option value="${t}">${t}</option>`).join('');
    return `<!DOCTYPE html>
<html lang="pt"><head><meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src ${webview.cspSource} 'unsafe-inline'; font-src ${webview.cspSource}; script-src 'nonce-${nonce}';">
<link href="${css}" rel="stylesheet">
<style>
  body { padding: 6px 8px; font-size: var(--vscode-font-size); color: var(--vscode-foreground); }
  .row { display: flex; align-items: center; gap: 4px; margin-bottom: 5px; }
  .row label { width: 78px; flex: none; opacity: .85; }
  input[type=text], select { flex: 1; min-width: 0; background: var(--vscode-input-background); color: var(--vscode-input-foreground);
    border: 1px solid var(--vscode-input-border, transparent); padding: 3px 5px; height: 24px; box-sizing: border-box; }
  .opts { display: flex; flex-wrap: wrap; gap: 4px 12px; align-items: center; margin: 2px 0 8px; }
  .opts label { display: flex; align-items: center; gap: 3px; }
  .grp { display: flex; flex-wrap: wrap; align-items: center; gap: 3px; margin-bottom: 4px; }
  .gt { font-weight: 600; opacity: .7; margin-right: 3px; min-width: 52px; }
  button.ib { display: inline-flex; align-items: center; gap: 3px; height: 26px; min-width: 28px; padding: 0 6px; justify-content: center;
    background: var(--vscode-button-secondaryBackground); color: var(--vscode-button-secondaryForeground); border: 1px solid transparent; cursor: pointer; border-radius: 2px; }
  button.ib:hover { background: var(--vscode-button-secondaryHoverBackground); }
  button.ib.locked { background: #b71c1c; color: #fff; cursor: not-allowed; }
  button.sm { height: 24px; padding: 0 6px; background: var(--vscode-button-secondaryBackground); color: var(--vscode-button-secondaryForeground); border: none; cursor: pointer; }
  #bar { height: 4px; background: var(--vscode-progressBar-background); width: 0%; transition: width .2s; margin-top: 8px; }
  #barBox { background: var(--vscode-editorWidget-border, #333); height: 4px; display: none; }
  #ptxt { opacity: .8; font-size: 11px; margin-top: 3px; display: none; }
  .hint { opacity: .6; margin-top: 10px; font-size: 11px; }
</style></head>
<body>
  <div class="row"><label>Workspace</label><input id="workspacePath" type="text" placeholder="Pasta do workspace (PEM_Aplicacao)"><button class="sm" data-browse="workspacePath" title="Escolher pasta">…</button></div>
  <div class="row"><label>AdminServer</label><input id="domainPath" type="text" placeholder="Domínio WebLogic (…/domains/PEM)"><button class="sm" data-browse="domainPath" title="Escolher pasta">…</button></div>
  <div class="row"><label>Perfil Maven</label><input id="mavenProfile" type="text" list="profiles" placeholder="ex.: dev"><datalist id="profiles"><option value="dev"><option value="qua"><option value="fast-build"><option value="prd.priv1"><option value="prd.priv2"><option value="prd1"><option value="prd2"></datalist></div>
  <div class="opts">
    <label title="Acrescenta -Dmaven.test.skip=true aos builds"><input id="skipTests" type="checkbox">Pular testes</label>
    <label title="Acrescenta -PlocalMock ao build do módulo pem-common-domain (e só a ele)"><input id="localMock" type="checkbox">localMock</label>
    <label title="Threads do Build Frontend rápido (mvn -T)">Threads (-T) <select id="threads" style="flex:none;width:64px">${threads}</select></label>
  </div>
  ${group('WebLogic',
    btn('startWebLogic', 'play', 'WebLogic: Start (startWebLogic)'),
    btn('stopWebLogic', 'debug-stop', 'WebLogic: Stop — gracioso e depois força a paragem'),
    btn('killWebLogic', 'close', 'WebLogic: Kill — termina já todos os processos do domínio'))}
  ${group('Limpeza',
    btn('clearClasspath', 'trash', 'Limpar cache/classpath (clear_classpath.sh): cache WL, targets, ~/.m2 pem. O servidor tem de estar PARADO'),
    btn('cleanTargets', 'clear-all', 'Limpar pastas target/ (clean-targets.sh)'),
    btn('killJars', 'debug-disconnect', 'Matar processos java dos módulos (kill-module-jars.sh)'))}
  ${group('Build',
    btn('buildBackend', 'tools', 'Build Backend (build-backend.sh)', 'BE'),
    btn('buildFrontend', 'tools', 'Build Frontend (mvn clean install em spms-plugins)', 'FE'),
    btn('buildFrontendFast', 'tools', 'Build Frontend rápido (usa o campo Threads -T; padrão 1C)', 'FE⚡'),
    btn('buildModules', 'symbol-module', 'Build de módulos isolados (vários; backend e/ou frontend)…'))}
  ${group('Deploy',
    btn('deployBackend', 'cloud-upload', 'Deploy Backend (vários módulos)…', 'BE'),
    btn('deployFrontend', 'cloud-upload', 'Deploy Frontend (vários módulos)…', 'FE'),
    btn('deployModules', 'symbol-module', 'Deploy de módulos isolados (vários; backend e/ou frontend)…'))}
  ${group('Tarefa', btn('cancel', 'stop-circle', 'Cancelar a tarefa em curso'))}
  <div id="barBox"><div id="bar"></div></div>
  <div id="ptxt"></div>
  <div class="hint">Os logs aparecem na vista «Logs», por baixo (separadores Tarefas e WebLogic, com pesquisa); «⧉ Janela nova» abre-os numa janela independente. Também há comandos na paleta (<code>PEM WL:</code>).</div>
<script nonce="${nonce}">
  const vscode = acquireVsCodeApi();
  const $ = (id) => document.getElementById(id);
  const fields = ['workspacePath', 'domainPath', 'mavenProfile'];
  let hideTimer;
  for (const f of fields) $(f).addEventListener('change', () => vscode.postMessage({ type: 'set', key: f, value: $(f).value.trim() }));
  for (const f of ['skipTests', 'localMock']) $(f).addEventListener('change', () => vscode.postMessage({ type: 'set', key: f, value: $(f).checked }));
  $('threads').addEventListener('change', () => vscode.postMessage({ type: 'set', key: 'threads', value: $('threads').value }));
  document.querySelectorAll('[data-browse]').forEach((b) => b.addEventListener('click', () => vscode.postMessage({ type: 'browse', key: b.dataset.browse })));
  document.querySelectorAll('button.ib').forEach((b) => b.addEventListener('click', () => {
    if (b.classList.contains('locked')) return;
    vscode.postMessage({ type: 'cmd', name: b.dataset.cmd });
  }));
  window.addEventListener('message', (e) => {
    const m = e.data;
    if (m.type === 'state') {
      const s = m.settings;
      for (const f of fields) if (document.activeElement !== $(f)) $(f).value = s[f] || '';
      $('skipTests').checked = !!s.skipTests; $('localMock').checked = !!s.localMock; $('threads').value = s.threads || '1C';
      document.querySelectorAll('button.ib').forEach((b) => b.classList.toggle('locked', m.locked.includes(b.dataset.cmd)));
    } else if (m.type === 'progress') {
      clearTimeout(hideTimer);
      $('barBox').style.display = 'block'; $('ptxt').style.display = 'block';
      const pct = m.total ? Math.round((m.done * 100) / m.total) : 0;
      $('bar').style.width = pct + '%';
      $('ptxt').textContent = m.finished ? pct + '%  (' + m.done + '/' + m.total + ') concluído' : pct + '%  (' + m.done + '/' + m.total + ')  ' + m.label;
      if (m.finished) hideTimer = setTimeout(() => { $('barBox').style.display = 'none'; $('ptxt').style.display = 'none'; }, 5000);
    }
  });
  vscode.postMessage({ type: 'ready' });
</script></body></html>`;
  }
}
