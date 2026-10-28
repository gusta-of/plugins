import * as vscode from 'vscode';
import { showTextBox } from './detailBox';
import { StepResult, TaskRunner } from './runner';
import { WlWatcher } from './wlStatus';

/**
 * Vista «Artefactos»: resultado por módulo do último build/deploy (✔/✘ + «Ver erro») e, ao subir o
 * WebLogic, o estado de cada artefacto (a carregar / pronto / falhou).
 */
export class ArtifactsView implements vscode.WebviewViewProvider {
  static readonly viewType = 'pemWlTools.artifacts';
  private view?: vscode.WebviewView;
  private timer?: NodeJS.Timeout;

  constructor(private readonly runner: TaskRunner, private readonly wl: WlWatcher) {
    runner.onResults = () => this.refresh();
    wl.onChange = () => this.refresh();
  }

  resolveWebviewView(view: vscode.WebviewView): void {
    this.view = view;
    view.webview.options = { enableScripts: true };
    view.webview.html = ArtifactsView.html();
    view.webview.onDidReceiveMessage((m) => {
      if (m?.type === 'ready') this.push();
      else if (m?.type === 'buildError') this.showBuildError(Number(m.id));
      else if (m?.type === 'wlError') this.showWlError(String(m.name));
    });
    view.onDidDispose(() => { if (this.view === view) this.view = undefined; });
    view.onDidChangeVisibility(() => { if (view.visible) this.push(); });
  }

  /** agrupa as notificações (um build gera centenas de eventos) */
  refresh(): void {
    if (this.timer) return;
    this.timer = setTimeout(() => { this.timer = undefined; this.push(); }, 150);
  }

  state() {
    const r = this.runner;
    const c = this.wl.counts();
    return {
      build: { title: r.resultsTitle, running: r.busy, items: r.results.map((x: StepResult) => ({ id: x.id, label: x.label, status: x.status, hasOutput: x.status !== 'ok' && !!x.output })) },
      wl: {
        active: this.wl.active, server: this.wl.serverState, counts: c,
        apps: [...this.wl.apps.values()].sort((a, b) => a.name.localeCompare(b.name)).map((a) => ({ name: a.name, state: a.state, detail: a.detail ?? '', hasError: a.state === 'failed' })),
      },
    };
  }

  private push(): void {
    void this.view?.webview.postMessage({ type: 'state', ...this.state() });
  }

  private showBuildError(id: number): void {
    const r = this.runner.results.find((x) => x.id === id);
    if (r) showTextBox(`Erro: ${r.label}`, r.output || '(sem saída)');
  }

  private showWlError(name: string): void {
    const a = this.wl.apps.get(name);
    if (a) showTextBox(`Falha ao carregar: ${a.name}`, a.error || '(o WebLogic não deu mais detalhes — veja o separador WebLogic dos Logs)');
  }

  private static html(): string {
    const nonce = Math.random().toString(36).slice(2) + Date.now().toString(36);
    return `<!DOCTYPE html><html><head><meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-${nonce}';">
<style>
  body { padding: 4px 8px; font-size: var(--vscode-font-size); font-family: var(--vscode-font-family); color: var(--vscode-foreground); }
  h4 { margin: 8px 0 3px; display: flex; gap: 6px; align-items: baseline; } h4 small { font-weight: normal; opacity: .7; }
  .row { display: flex; align-items: center; gap: 6px; padding: 1px 0; } .name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; } .dim { opacity: .65; }
  .ok { color: #89d185; } .fail { color: var(--vscode-errorForeground, #f48771); } .warn { color: var(--vscode-editorWarning-foreground, #cca700); }
  .ic { width: 14px; text-align: center; } .spin { display: inline-block; animation: sp 1s linear infinite; } @keyframes sp { to { transform: rotate(360deg); } }
  button { background: var(--vscode-button-secondaryBackground); color: var(--vscode-button-secondaryForeground); border: 0; padding: 0 7px; cursor: pointer; font-size: 11px; }
  .empty { opacity: .6; padding: 2px 0; } .list { max-height: 38vh; overflow: auto; }
</style></head><body>
<h4>Build <small id="bt"></small></h4><div id="build" class="list"><div class="empty">Sem builds ainda.</div></div>
<h4>WebLogic <small id="wt"></small></h4><div id="wl" class="list"><div class="empty">Inicie o WebLogic para ver os artefactos.</div></div>
<script nonce="${nonce}">
  const vscode = acquireVsCodeApi(); const $ = (i) => document.getElementById(i);
  const esc = (s) => String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
  const icon = (s) => s === 'ok' || s === 'ready' ? '<span class="ic ok">✔</span>' : s === 'fail' || s === 'failed' ? '<span class="ic fail">✘</span>' : s === 'warn' ? '<span class="ic warn">⚠</span>'
    : s === 'pending' ? '<span class="ic dim">○</span>' : '<span class="ic spin">⟳</span>';
  function render(m) {
    const b = m.build;
    $('bt').textContent = b.title ? b.title + (b.running ? ' — a correr' : '') : '';
    $('build').innerHTML = b.items.length ? b.items.map((x) => '<div class="row">' + icon(x.status) + '<span class="name" title="' + esc(x.label) + '">' + esc(x.label) + '</span>' + (x.hasOutput ? '<button data-b="' + x.id + '">Ver erro</button>' : '') + '</div>').join('') : '<div class="empty">Sem builds ainda.</div>';
    const w = m.wl, c = w.counts;
    $('wt').textContent = w.server ? w.server + (c.total ? ' — ' + c.ready + '/' + c.total + ' prontos' + (c.failed ? ', ' + c.failed + ' com erro' : '') : '') : '';
    $('wl').innerHTML = w.apps.length ? w.apps.map((a) => '<div class="row">' + icon(a.state) + '<span class="name" title="' + esc(a.detail) + '">' + esc(a.name) + '</span>'
      + '<span class="dim">' + (a.state === 'ready' ? 'pronto' : a.state === 'failed' ? 'falhou' : a.state === 'loading' ? 'a carregar' : 'em espera') + '</span>' + (a.hasError ? '<button data-w="' + esc(a.name) + '">Ver erro</button>' : '') + '</div>').join('')
      : (w.active ? '<div class="empty">A aguardar os deployments…</div>' : '<div class="empty">Inicie o WebLogic para ver os artefactos.</div>');
    document.querySelectorAll('[data-b]').forEach((e) => e.addEventListener('click', () => vscode.postMessage({ type: 'buildError', id: e.dataset.b })));
    document.querySelectorAll('[data-w]').forEach((e) => e.addEventListener('click', () => vscode.postMessage({ type: 'wlError', name: e.dataset.w })));
  }
  window.addEventListener('message', (e) => { if (e.data.type === 'state') render(e.data); });
  vscode.postMessage({ type: 'ready' });
</script></body></html>`;
  }
}
