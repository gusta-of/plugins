import * as vscode from 'vscode';

const MAX_LINES = 20000;

export interface ChannelDef { id: string; label: string }

/** Um canal de registo (aba): as mesmas chamadas do antigo OutputChannel. */
export class LogChannel {
  constructor(private readonly hub: LogHub, readonly def: ChannelDef) {}
  appendLine(text: string): void { this.hub.append(this.def.id, text); }
  show(preserveFocus = true): void { this.hub.reveal(this.def.id, preserveFocus); }
  clear(): void { this.hub.clear(this.def.id); }
}

/**
 * Registo embebido por baixo dos botões (vista «Logs» na mesma barra lateral), com uma aba por canal,
 * pesquisa, «seguir o fim» e limpar. O botão «Janela nova» abre uma cópia viva num painel do editor
 * (que se pode mover para outra janela); fechá-la não perde nada — a vista lateral continua lá.
 */
export class LogHub implements vscode.WebviewViewProvider, vscode.Disposable {
  static readonly viewType = 'pemWlTools.logs';
  private readonly lines = new Map<string, string[]>();
  private readonly sinks = new Set<{ webview: vscode.Webview; ready: boolean }>();
  private pending = new Map<string, string[]>();
  private timer?: NodeJS.Timeout;
  private view?: vscode.WebviewView;
  private detached?: vscode.WebviewPanel;
  private active: string;

  constructor(readonly channels: ChannelDef[]) {
    for (const c of channels) this.lines.set(c.id, []);
    this.active = channels[0].id;
  }

  channel(id: string): LogChannel {
    const def = this.channels.find((c) => c.id === id);
    if (!def) throw new Error(`canal desconhecido: ${id}`);
    return new LogChannel(this, def);
  }

  // ---- dados ----

  append(id: string, text: string): void {
    const buf = this.lines.get(id)!;
    const add = text.split(/\r?\n/);
    buf.push(...add);
    if (buf.length > MAX_LINES) buf.splice(0, buf.length - MAX_LINES);
    const p = this.pending.get(id) ?? [];
    p.push(...add);
    this.pending.set(id, p);
    if (!this.timer) this.timer = setTimeout(() => this.flush(), 80);
  }

  private flush(): void {
    this.timer = undefined;
    const batch = this.pending;
    this.pending = new Map();
    for (const [channel, lines] of batch) this.broadcast({ type: 'append', channel, lines });
  }

  private broadcast(msg: unknown): void {
    for (const s of this.sinks) if (s.ready) void s.webview.postMessage(msg);
  }

  clear(id: string): void {
    this.lines.get(id)!.length = 0;
    this.pending.delete(id);
    this.broadcast({ type: 'reset', channel: id });
  }

  reveal(id: string, preserveFocus: boolean): void {
    this.active = id;
    this.broadcast({ type: 'select', channel: id });
    if (this.view) this.view.show(preserveFocus);
    else void vscode.commands.executeCommand(`${LogHub.viewType}.focus`);
  }

  // ---- webviews ----

  resolveWebviewView(view: vscode.WebviewView): void {
    this.view = view;
    view.webview.options = { enableScripts: true };
    this.attach(view.webview, () => { if (this.view === view) this.view = undefined; }, view.onDidDispose.bind(view));
  }

  private attach(webview: vscode.Webview, onGone: () => void, onDispose: (l: () => void) => unknown): void {
    const sink = { webview, ready: false };
    this.sinks.add(sink);
    onDispose(() => { this.sinks.delete(sink); onGone(); });
    webview.onDidReceiveMessage((m) => {
      if (m?.type === 'ready') {
        sink.ready = true;
        const data: Record<string, string[]> = {};
        for (const [k, v] of this.lines) data[k] = v;
        void webview.postMessage({ type: 'init', data, active: this.active });
      } else if (m?.type === 'clear') this.clear(m.channel);
      else if (m?.type === 'active') this.active = m.channel;
      else if (m?.type === 'detach') this.openDetached();
    });
    webview.html = LogHub.html(this.channels);
  }

  /** Cópia viva num painel do editor; já abre numa janela independente. */
  private openDetached(): void {
    if (this.detached) { this.detached.reveal(undefined, false); return; }
    const panel = vscode.window.createWebviewPanel('pemWlTools.logWindow', 'PEM WL — Logs', vscode.ViewColumn.Active, { enableScripts: true, retainContextWhenHidden: true });
    this.detached = panel;
    this.attach(panel.webview, () => { if (this.detached === panel) this.detached = undefined; }, panel.onDidDispose.bind(panel));
    // marca "janela" para esconder o botão de destacar
    setTimeout(() => { void panel.webview.postMessage({ type: 'windowed' }); }, 400);
    setTimeout(() => { void vscode.commands.executeCommand('workbench.action.moveEditorToNewWindow'); }, 250);
  }

  dispose(): void {
    if (this.timer) clearTimeout(this.timer);
    this.detached?.dispose();
  }

  private static html(channels: ChannelDef[]): string {
    const nonce = Math.random().toString(36).slice(2) + Date.now().toString(36);
    return `<!DOCTYPE html><html><head><meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-${nonce}';">
<style>
  html, body { height: 100%; margin: 0; } body { display: flex; flex-direction: column; font-family: var(--vscode-editor-font-family, monospace); font-size: 12px; color: var(--vscode-foreground); background: var(--vscode-sideBar-background, var(--vscode-editor-background)); }
  #tabs { display: flex; gap: 2px; padding: 3px 6px 0; font-family: var(--vscode-font-family); } .tab { padding: 3px 10px; cursor: pointer; opacity: .65; border-bottom: 2px solid transparent; } .tab.active { opacity: 1; border-bottom-color: var(--vscode-focusBorder, #07f); }
  #bar { display: flex; gap: 5px; align-items: center; padding: 3px 6px; font-family: var(--vscode-font-family); flex-wrap: wrap; }
  #q { width: 130px; flex: 0 1 150px; background: var(--vscode-input-background); color: var(--vscode-input-foreground); border: 1px solid var(--vscode-input-border, #555); padding: 1px 5px; font-size: 12px; }
  button { background: var(--vscode-button-secondaryBackground); color: var(--vscode-button-secondaryForeground); border: 0; padding: 1px 7px; cursor: pointer; } button:hover { filter: brightness(1.2); }
  .logs { flex: 1; min-height: 0; position: relative; } .log { position: absolute; inset: 0; overflow: auto; padding: 2px 6px; white-space: pre-wrap; word-break: break-all; display: none; } .log.active { display: block; }
  .l { min-height: 1.2em; } .err { color: var(--vscode-errorForeground, #f48771); } .ok { color: #89d185; } .warn { color: var(--vscode-editorWarning-foreground, #cca700); }
  mark { background: var(--vscode-editor-findMatchHighlightBackground, #ea5c0055); color: inherit; } mark.cur { background: var(--vscode-editor-findMatchBackground, #515c6a); outline: 1px solid var(--vscode-focusBorder, #fa0); }
  .dim { opacity: .65; } label { white-space: nowrap; }
</style></head><body>
<div id="tabs">${channels.map((c) => `<div class="tab" data-id="${c.id}">${c.label}</div>`).join('')}</div>
<div id="bar"><input id="q" type="text" placeholder="Pesquisar (Enter)"><button id="prev" title="Anterior (Shift+Enter)">▲</button><button id="next" title="Seguinte (Enter)">▼</button><span id="cnt" class="dim"></span>
<label><input type="checkbox" id="follow" checked> Seguir</label><button id="clear">Limpar</button><button id="detach" title="Abrir numa janela independente">⧉ Janela nova</button></div>
<div class="logs" id="logs">${channels.map((c) => `<div class="log" id="log-${c.id}"></div>`).join('')}</div>
<script nonce="${nonce}">
  const vscode = acquireVsCodeApi();
  const $ = (i) => document.getElementById(i);
  const ids = ${JSON.stringify(channels.map((c) => c.id))};
  const ansi = /\\x1b\\[[0-9;]*[A-Za-z]/g;
  const MAX = ${MAX_LINES};
  let query = '', marks = [], cur = -1, active = ids[0];
  const logEl = (id) => $('log-' + id);
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
  function addLine(id, raw) {
    const el = logEl(id), d = document.createElement('div'), t = raw.replace(ansi, '');
    d.className = 'l ' + kind(t); d.dataset.t = t; paint(d); el.appendChild(d);
    if (el.childElementCount > MAX) el.removeChild(el.firstChild);
  }
  const atBottom = (el) => el.scrollHeight - el.scrollTop - el.clientHeight < 24;
  function stick() { if ($('follow').checked) { const e = logEl(active); e.scrollTop = e.scrollHeight; } }
  function collect() { marks = Array.from(logEl(active).querySelectorAll('mark')); $('cnt').textContent = query ? (marks.length ? (cur + 1) + '/' + marks.length : '0') : ''; }
  function goto(delta) {
    if (!marks.length) return;
    if (cur >= 0 && marks[cur]) marks[cur].classList.remove('cur');
    cur = (cur + delta + marks.length) % marks.length;
    marks[cur].classList.add('cur'); $('follow').checked = false;
    marks[cur].scrollIntoView({ block: 'center' }); $('cnt').textContent = (cur + 1) + '/' + marks.length;
  }
  function research() { query = $('q').value; ids.forEach((id) => logEl(id).childNodes.forEach(paint)); cur = -1; collect(); if (marks.length) { cur = marks.length - 1; goto(1); } }
  function select(id) {
    active = id; cur = -1;
    document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('active', t.dataset.id === id));
    ids.forEach((x) => logEl(x).classList.toggle('active', x === id));
    $('follow').checked = true; collect(); stick(); vscode.postMessage({ type: 'active', channel: id });
  }
  document.querySelectorAll('.tab').forEach((t) => t.addEventListener('click', () => select(t.dataset.id)));
  let tm; $('q').addEventListener('input', () => { clearTimeout(tm); tm = setTimeout(research, 150); });
  $('q').addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); if (query !== $('q').value) research(); else goto(e.shiftKey ? -1 : 1); } });
  $('next').addEventListener('click', () => goto(1)); $('prev').addEventListener('click', () => goto(-1));
  $('clear').addEventListener('click', () => vscode.postMessage({ type: 'clear', channel: active }));
  $('detach').addEventListener('click', () => vscode.postMessage({ type: 'detach' }));
  $('follow').addEventListener('change', stick);
  ids.forEach((id) => logEl(id).addEventListener('scroll', () => { if (id === active) $('follow').checked = atBottom(logEl(id)); }));
  window.addEventListener('keydown', (e) => { if ((e.ctrlKey || e.metaKey) && e.key === 'f') { e.preventDefault(); $('q').focus(); $('q').select(); } });
  window.addEventListener('message', (e) => {
    const m = e.data;
    if (m.type === 'init') { ids.forEach((id) => { logEl(id).textContent = ''; (m.data[id] || []).forEach((l) => addLine(id, l)); }); select(m.active || ids[0]); }
    else if (m.type === 'append') { m.lines.forEach((l) => addLine(m.channel, l)); if (query && m.channel === active) { const k = cur; collect(); cur = Math.min(k, marks.length - 1); } stick(); }
    else if (m.type === 'reset') { logEl(m.channel).textContent = ''; if (m.channel === active) collect(); }
    else if (m.type === 'select') select(m.channel);
    else if (m.type === 'windowed') $('detach').style.display = 'none';
  });
  vscode.postMessage({ type: 'ready' });
</script></body></html>`;
  }
}
