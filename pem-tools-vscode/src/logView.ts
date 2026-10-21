import * as vscode from 'vscode';

const MAX_LINES = 20000;

/**
 * Registo apresentado num painel (webview) do editor: segue sempre o fim, tem pesquisa (Enter / Shift+Enter
 * para navegar), limpar e pode ser arrastado para uma janela independente do VS Code.
 * O histórico fica no lado da extensão e é reenviado sempre que o painel (re)abre.
 */
export class LogView implements vscode.Disposable {
  private readonly lines: string[] = [];
  private panel?: vscode.WebviewPanel;
  private ready = false;
  private pending: string[] = [];
  private timer?: NodeJS.Timeout;

  constructor(private readonly title: string) {}

  appendLine(text: string): void {
    for (const l of text.split(/\r?\n/)) {
      this.lines.push(l);
      if (this.ready) this.pending.push(l);
    }
    if (this.lines.length > MAX_LINES) this.lines.splice(0, this.lines.length - MAX_LINES);
    if (this.ready && !this.timer) this.timer = setTimeout(() => this.flush(), 80);
  }

  private flush(): void {
    this.timer = undefined;
    if (!this.ready || this.pending.length === 0) return;
    const batch = this.pending;
    this.pending = [];
    void this.panel?.webview.postMessage({ type: 'append', lines: batch });
  }

  /** Mostra (criando se preciso) o painel. */
  show(preserveFocus = true): void {
    if (this.panel) { this.panel.reveal(undefined, preserveFocus); return; }
    const panel = vscode.window.createWebviewPanel('pemwl.log', this.title, { viewColumn: vscode.ViewColumn.Active, preserveFocus }, { enableScripts: true, retainContextWhenHidden: true });
    this.panel = panel;
    this.ready = false;
    panel.iconPath = new vscode.ThemeIcon('output') as unknown as vscode.Uri;
    panel.onDidDispose(() => { if (this.panel === panel) { this.panel = undefined; this.ready = false; this.pending = []; } });
    panel.webview.onDidReceiveMessage((m) => {
      if (m?.type === 'ready') {
        // (re)inicia: o webview pode ter sido recriado (ex.: movido para outra janela)
        this.pending = [];
        this.ready = true;
        void panel.webview.postMessage({ type: 'init', lines: this.lines });
      } else if (m?.type === 'clear') {
        this.lines.length = 0;
      }
    });
    panel.webview.html = LogView.html(this.title);
  }

  clear(): void {
    this.lines.length = 0;
    this.pending = [];
    void this.panel?.webview.postMessage({ type: 'init', lines: [] });
  }

  dispose(): void {
    if (this.timer) clearTimeout(this.timer);
    this.panel?.dispose();
  }

  private static html(title: string): string {
    const nonce = Math.random().toString(36).slice(2) + Date.now().toString(36);
    return `<!DOCTYPE html><html><head><meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-${nonce}';">
<style>
  html, body { height: 100%; margin: 0; } body { display: flex; flex-direction: column; font-family: var(--vscode-editor-font-family, monospace); font-size: var(--vscode-editor-font-size, 13px); color: var(--vscode-editor-foreground); background: var(--vscode-editor-background); }
  #bar { display: flex; gap: 6px; align-items: center; padding: 4px 8px; border-bottom: 1px solid var(--vscode-panel-border, #444); font-family: var(--vscode-font-family); font-size: 12px; flex-wrap: wrap; }
  #q { width: 180px; background: var(--vscode-input-background); color: var(--vscode-input-foreground); border: 1px solid var(--vscode-input-border, #555); padding: 2px 6px; }
  button { background: var(--vscode-button-secondaryBackground); color: var(--vscode-button-secondaryForeground); border: 0; padding: 2px 8px; cursor: pointer; } button:hover { filter: brightness(1.2); }
  #log { flex: 1; overflow: auto; padding: 4px 8px; white-space: pre-wrap; word-break: break-all; }
  .l { min-height: 1.2em; } .err { color: var(--vscode-errorForeground, #f48771); } .ok { color: #89d185; } .warn { color: var(--vscode-editorWarning-foreground, #cca700); }
  mark { background: var(--vscode-editor-findMatchHighlightBackground, #ea5c0055); color: inherit; } mark.cur { background: var(--vscode-editor-findMatchBackground, #515c6a); outline: 1px solid var(--vscode-focusBorder, #fa0); }
  .dim { opacity: .65; }
</style></head><body>
<div id="bar"><input id="q" type="text" placeholder="Pesquisar (Enter: seguinte)"><button id="prev" title="Anterior (Shift+Enter)">▲</button><button id="next" title="Seguinte (Enter)">▼</button>
<span id="cnt" class="dim"></span><label><input type="checkbox" id="follow" checked> Seguir o fim</label><button id="clear">Limpar</button><span class="dim" id="info"></span></div>
<div id="log"></div>
<script nonce="${nonce}">
  const vscode = acquireVsCodeApi();
  const $ = (i) => document.getElementById(i);
  const log = $('log');
  const ansi = /\\x1b\\[[0-9;]*[A-Za-z]/g;
  const MAX = ${MAX_LINES};
  let query = '', marks = [], cur = -1;
  const esc = (s) => s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
  const kind = (t) => t.startsWith('✘') ? 'err' : t.startsWith('✔') ? 'ok' : t.startsWith('⚠') ? 'warn' : '';
  function paint(div) {
    const t = div.dataset.t;
    if (!query) { div.textContent = t; return; }
    const lo = t.toLowerCase(), q = query.toLowerCase();
    let i = 0, out = '', at;
    while ((at = lo.indexOf(q, i)) >= 0) { out += esc(t.slice(i, at)) + '<mark>' + esc(t.slice(at, at + q.length)) + '</mark>'; i = at + q.length; }
    div.innerHTML = out + esc(t.slice(i));
  }
  function addLine(raw) {
    const d = document.createElement('div');
    const t = raw.replace(ansi, '');
    d.className = 'l ' + kind(t); d.dataset.t = t;
    paint(d); log.appendChild(d);
  }
  const atBottom = () => log.scrollHeight - log.scrollTop - log.clientHeight < 24;
  function stick() { if ($('follow').checked) log.scrollTop = log.scrollHeight; }
  function trim() { while (log.childElementCount > MAX) log.removeChild(log.firstChild); }
  function collect() { marks = Array.from(log.querySelectorAll('mark')); $('cnt').textContent = query ? (marks.length ? (cur + 1) + '/' + marks.length : '0') : ''; }
  function goto(delta) {
    if (!marks.length) return;
    if (cur >= 0 && marks[cur]) marks[cur].classList.remove('cur');
    cur = (cur + delta + marks.length) % marks.length;
    marks[cur].classList.add('cur'); $('follow').checked = false;
    marks[cur].scrollIntoView({ block: 'center' }); $('cnt').textContent = (cur + 1) + '/' + marks.length;
  }
  function research() { query = $('q').value; log.childNodes.forEach(paint); cur = -1; collect(); if (marks.length) { cur = marks.length - 1; goto(1); } }
  let tm; $('q').addEventListener('input', () => { clearTimeout(tm); tm = setTimeout(research, 150); });
  $('q').addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); if (query !== $('q').value) research(); else goto(e.shiftKey ? -1 : 1); } });
  $('next').addEventListener('click', () => goto(1)); $('prev').addEventListener('click', () => goto(-1));
  $('clear').addEventListener('click', () => { log.textContent = ''; marks = []; cur = -1; collect(); vscode.postMessage({ type: 'clear' }); });
  // seguir o fim enquanto estiver no fundo; ao subir para ler, pára de saltar; ao voltar ao fundo, retoma
  log.addEventListener('scroll', () => { $('follow').checked = atBottom(); });
  $('follow').addEventListener('change', stick);
  window.addEventListener('keydown', (e) => { if ((e.ctrlKey || e.metaKey) && e.key === 'f') { e.preventDefault(); $('q').focus(); $('q').select(); } });
  window.addEventListener('message', (e) => {
    const m = e.data;
    if (m.type === 'init') { log.textContent = ''; m.lines.forEach(addLine); trim(); if (query) collect(); $('follow').checked = true; stick(); }
    else if (m.type === 'append') { m.lines.forEach(addLine); trim(); if (query) { const keep = cur; collect(); cur = Math.min(keep, marks.length - 1); } stick(); }
    $('info').textContent = log.childElementCount + ' linhas';
  });
  vscode.postMessage({ type: 'ready' });
</script></body></html>`;
  }
}
