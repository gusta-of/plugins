import * as vscode from 'vscode';
import { formatPayload } from './payload';
import { editorHtml } from './rulesEditor';

const MAX_LINES = 20000;
const PAYLOAD_SCHEME = 'pemwl-payload';

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
  private editorPanel?: vscode.WebviewPanel;
  private payloadSeq = 0;
  private readonly payloads = new Map<string, string>();
  private readonly payloadDocs: vscode.Disposable[];
  private active: string;
  private rules: unknown[] | undefined;
  private fontSize = 12;

  constructor(readonly channels: ChannelDef[], private readonly ext: vscode.Uri, private readonly store?: vscode.Memento) {
    for (const c of channels) this.lines.set(c.id, []);
    this.active = channels[0].id;
    this.payloadDocs = [
      // alterações feitas noutra janela do VS Code (ou no settings.json) chegam aqui em direto
      vscode.workspace.onDidChangeConfiguration((e) => {
        if (e.affectsConfiguration('pemWlTools.highlightRules')) {
          const r = vscode.workspace.getConfiguration('pemWlTools').get<unknown[] | null>('highlightRules');
          this.rules = Array.isArray(r) ? r : undefined;
          this.broadcast({ type: 'rules', rules: this.rules });
        }
        if (e.affectsConfiguration('pemWlTools.logFontSize')) {
          this.fontSize = Math.min(28, Math.max(8, vscode.workspace.getConfiguration('pemWlTools').get<number>('logFontSize', 12)));
          this.broadcast({ type: 'font', size: this.fontSize });
        }
      }),
      vscode.workspace.registerTextDocumentContentProvider(PAYLOAD_SCHEME, { provideTextDocumentContent: (u) => this.payloads.get(u.toString()) ?? '' }),
      vscode.workspace.onDidCloseTextDocument((d) => { if (d.uri.scheme === PAYLOAD_SCHEME) this.payloads.delete(d.uri.toString()); }),
    ];
    // as definições do utilizador (settings.json) são a fonte de verdade: persistem entre reinstalações e janelas.
    // O globalState (versões antigas) só serve de migração.
    const cfg = vscode.workspace.getConfiguration('pemWlTools');
    const fromCfg = cfg.get<unknown[] | null>('highlightRules');
    this.rules = Array.isArray(fromCfg) ? fromCfg : store?.get<unknown[]>('highlightRules');
    const sizeCfg = cfg.inspect<number>('logFontSize')?.globalValue;
    this.fontSize = Math.min(28, Math.max(8, sizeCfg ?? store?.get<number>('logFontSize', 12) ?? 12));
    if (!Array.isArray(fromCfg) && Array.isArray(this.rules)) void cfg.update('highlightRules', this.rules, vscode.ConfigurationTarget.Global);
    if (sizeCfg === undefined && this.fontSize !== 12) void cfg.update('logFontSize', this.fontSize, vscode.ConfigurationTarget.Global);
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
    view.webview.options = { enableScripts: true, localResourceRoots: [vscode.Uri.joinPath(this.ext, 'media')] };
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
        void webview.postMessage({ type: 'init', data, active: this.active, rules: this.rules, fontSize: this.fontSize });
      } else if (m?.type === 'clear') this.clear(m.channel);
      else if (m?.type === 'active') this.active = m.channel;
      else if (m?.type === 'detach') this.openDetached();
      else if (m?.type === 'saveFont') this.saveFont(m.size);
      else if (m?.type === 'saveRules') this.saveRules(m.rules);
      else if (m?.type === 'openEditor') this.openEditor();
      else if (m?.type === 'copyText') void vscode.env.clipboard.writeText(String(m.text));
      else if (m?.type === 'openPayload') void this.openPayload(String(m.kind), String(m.text));
    });
    webview.html = LogHub.html(this.channels, webview, this.ext);
  }

  /** Abre o JSON/SOAP formatado num editor do VS Code (com cores, pesquisa e dobragem). */
  private async openPayload(kind: string, text: string): Promise<void> {
    // documento virtual (esquema próprio): só de leitura, nunca vira ficheiro no workspace nem pede para guardar
    const uri = vscode.Uri.from({ scheme: PAYLOAD_SCHEME, path: `/payload-${++this.payloadSeq}.${kind === 'json' ? 'json' : 'xml'}` });
    this.payloads.set(uri.toString(), formatPayload(kind, text));
    const doc = await vscode.workspace.openTextDocument(uri);
    await vscode.window.showTextDocument(doc, { viewColumn: vscode.ViewColumn.Beside, preview: true });
  }

  private saveFont(size: unknown): void {
    this.fontSize = Math.min(28, Math.max(8, Number(size) || 12));
    void vscode.workspace.getConfiguration('pemWlTools').update('logFontSize', this.fontSize, vscode.ConfigurationTarget.Global);
    this.broadcast({ type: 'font', size: this.fontSize });
  }

  private saveRules(rules: unknown): void {
    this.rules = Array.isArray(rules) ? rules : undefined;
    void vscode.workspace.getConfiguration('pemWlTools').update('highlightRules', this.rules ?? null, vscode.ConfigurationTarget.Global);
    this.broadcast({ type: 'rules', rules: this.rules });
  }

  /** Editor de regras e de fonte numa caixa (painel) própria, fora da vista estreita. */
  private openEditor(): void {
    if (this.editorPanel) { this.editorPanel.reveal(undefined, false); return; }
    const panel = vscode.window.createWebviewPanel('pemWlTools.highlight', 'PEM WL — Realce dos logs', vscode.ViewColumn.Active, { enableScripts: true, localResourceRoots: [vscode.Uri.joinPath(this.ext, 'media')] });
    this.editorPanel = panel;
    panel.onDidDispose(() => { if (this.editorPanel === panel) this.editorPanel = undefined; });
    panel.webview.onDidReceiveMessage((m) => {
      if (m?.type === 'ready') void panel.webview.postMessage({ type: 'state', rules: this.rules, fontSize: this.fontSize });
      else if (m?.type === 'saveRules') this.saveRules(m.rules);
      else if (m?.type === 'saveFont') this.saveFont(m.size);
      else if (m?.type === 'close') panel.dispose();
      else if (m?.type === 'confirmReset') {
        void vscode.window.showWarningMessage(
          'Repor as predefinições?\n\nTodas as regras desta lista serão substituídas pelas 5 de origem (as suas regras, como as de SOAP e JSON, deixam de estar na lista). Só fica definitivo ao carregar em «Guardar».',
          { modal: true }, 'Repor predefinições',
        ).then((r) => { if (r === 'Repor predefinições') void panel.webview.postMessage({ type: 'reset' }); });
      }
    });
    panel.webview.html = editorHtml(panel.webview, this.ext);
  }

  /** Cópia viva num painel do editor; já abre numa janela independente. */
  private openDetached(): void {
    if (this.detached) { this.detached.reveal(undefined, false); return; }
    const panel = vscode.window.createWebviewPanel('pemWlTools.logWindow', 'PEM WL — Logs', vscode.ViewColumn.Active, { enableScripts: true, retainContextWhenHidden: true, localResourceRoots: [vscode.Uri.joinPath(this.ext, 'media')] });
    this.detached = panel;
    this.attach(panel.webview, () => { if (this.detached === panel) this.detached = undefined; }, panel.onDidDispose.bind(panel));
    // marca "janela" para esconder o botão de destacar
    setTimeout(() => { void panel.webview.postMessage({ type: 'windowed' }); }, 400);
    setTimeout(() => { void vscode.commands.executeCommand('workbench.action.moveEditorToNewWindow'); }, 250);
  }

  dispose(): void {
    if (this.timer) clearTimeout(this.timer);
    this.detached?.dispose();
    this.editorPanel?.dispose();
    this.payloadDocs.forEach((d) => d.dispose());
  }

  private static html(channels: ChannelDef[], webview: vscode.Webview, ext: vscode.Uri): string {
    const hl = webview.asWebviewUri(vscode.Uri.joinPath(ext, 'media', 'highlight.js'));
    const pl = webview.asWebviewUri(vscode.Uri.joinPath(ext, 'media', 'payload.js'));
    const nonce = Math.random().toString(36).slice(2) + Date.now().toString(36);
    return `<!DOCTYPE html><html><head><meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-${nonce}' ${webview.cspSource};">
<style>
  html, body { height: 100%; margin: 0; } body { display: flex; flex-direction: column; font-family: var(--vscode-editor-font-family, monospace); font-size: 12px; color: var(--vscode-foreground); background: var(--vscode-sideBar-background, var(--vscode-editor-background)); }
  #tabs { display: flex; gap: 2px; padding: 3px 6px 0; font-family: var(--vscode-font-family); } .tab { padding: 3px 10px; cursor: pointer; opacity: .65; border-bottom: 2px solid transparent; } .tab.active { opacity: 1; border-bottom-color: var(--vscode-focusBorder, #07f); }
  #bar { display: flex; gap: 5px; align-items: center; padding: 3px 6px; font-family: var(--vscode-font-family); flex-wrap: wrap; }
  #q { width: 130px; flex: 0 1 150px; background: var(--vscode-input-background); color: var(--vscode-input-foreground); border: 1px solid var(--vscode-input-border, #555); padding: 1px 5px; font-size: 12px; }
  button { background: var(--vscode-button-secondaryBackground); color: var(--vscode-button-secondaryForeground); border: 0; padding: 1px 7px; cursor: pointer; } button:hover { filter: brightness(1.2); }
  .logs { flex: 1; min-height: 0; position: relative; --fs: 12px; } .log { position: absolute; inset: 0; overflow: auto; padding: 2px 6px; white-space: pre-wrap; word-break: break-all; display: none; font-size: var(--fs); } .log.active { display: block; }
  .l { min-height: 1.2em; } .err { color: var(--vscode-errorForeground, #f48771); } .ok { color: #89d185; } .warn { color: var(--vscode-editorWarning-foreground, #cca700); }
  mark { background: var(--vscode-editor-findMatchHighlightBackground, #ea5c0055); color: inherit; } mark.cur { background: var(--vscode-editor-findMatchBackground, #515c6a); outline: 1px solid var(--vscode-focusBorder, #fa0); }
  .dim { opacity: .65; } label { white-space: nowrap; }
  #pv { display: none; position: absolute; inset: 0; z-index: 8; flex-direction: column; background: var(--vscode-editor-background); font-family: var(--vscode-font-family); }
  #pv.open { display: flex; } #pvh { display: flex; gap: 6px; align-items: center; padding: 4px 8px; border-bottom: 1px solid var(--vscode-panel-border, #444); } #pvh b { flex: 1; }
  #pvt { flex: 1; margin: 0; padding: 6px 10px; overflow: auto; font-family: var(--vscode-editor-font-family, monospace); font-size: 12px; white-space: pre; tab-size: 2; }
  .fl { white-space: pre; min-height: 1.3em; } .fg { display: inline-block; width: 1.5em; text-align: center; cursor: pointer; opacity: .65; user-select: none; } .fg:hover { opacity: 1; }
  .fs { display: none; cursor: pointer; opacity: .6; } .fl.col .fs { display: inline; } .fl.col .fs:hover { opacity: 1; text-decoration: underline; }
  .tg { color: var(--vscode-symbolIcon-classForeground, #4ec9b0); } .ky { color: var(--vscode-symbolIcon-propertyForeground, #9cdcfe); } .st { color: var(--vscode-symbolIcon-stringForeground, #ce9178); }
  .plw { display: block; margin: 3px 0 1px; }
  .pl { margin-left: 0; font-size: 10px; padding: 0 6px; border-radius: 2px; user-select: none; vertical-align: baseline; }
  #ed { font-size: 12px; display: none; position: absolute; inset: 0; z-index: 5; overflow: auto; padding: 6px 8px; background: var(--vscode-sideBar-background, var(--vscode-editor-background)); font-family: var(--vscode-font-family); }
  #ed.open { display: block; } #ed table { border-collapse: collapse; width: 100%; } #ed td, #ed th { padding: 2px 3px; text-align: left; font-weight: normal; } #ed th { opacity: .7; font-size: 11px; }
  #ed input[type=text] { width: 100%; box-sizing: border-box; background: var(--vscode-input-background); color: var(--vscode-input-foreground); border: 1px solid var(--vscode-input-border, #555); padding: 1px 4px; font-size: 11px; }
  #ed select { background: var(--vscode-dropdown-background); color: var(--vscode-dropdown-foreground); border: 1px solid var(--vscode-dropdown-border, #555); font-size: 11px; }
  #ed .pat { display: flex; align-items: center; gap: 2px; } #ed .wc { color: var(--vscode-textLink-foreground, #4af); font-weight: bold; min-width: 6px; text-align: center; }
  #ed input[type=color] { width: 30px; height: 20px; padding: 0; border: 1px solid #666; background: none; } #ed .none { opacity: .5; } #ed .acts { margin: 6px 0; display: flex; gap: 6px; flex-wrap: wrap; } #ed p { margin: 4px 0; opacity: .75; font-size: 11px; }
</style></head><body>
<div id="tabs">${channels.map((c) => `<div class="tab" data-id="${c.id}">${c.label}</div>`).join('')}</div>
<div id="bar"><input id="q" type="text" placeholder="Pesquisar (Enter)"><button id="prev" title="Anterior (Shift+Enter)">▲</button><button id="next" title="Seguinte (Enter)">▼</button><span id="cnt" class="dim"></span>
<label><input type="checkbox" id="follow" checked> Seguir</label><button id="clear">Limpar</button><button id="detach" title="Abrir numa janela independente">⧉ Janela nova</button><button id="hl" title="Realce de cores: regras expressão → cor (blocos incluídos)">🎨 Realce</button></div>
<div class="logs" id="logs"><div id="pv"><div id="pvh"><b id="pvk"></b><button id="pvcopy">Copiar</button><button id="pvcol" title="Recolher todos os blocos (mantém a raiz aberta)">⊟ Recolher tudo</button><button id="pvexp" title="Expandir todos os blocos">⊞ Expandir tudo</button><button id="pved">Abrir no editor</button><button id="pvx">Fechar</button></div><div id="pvt"></div></div>${channels.map((c) => `<div class="log" id="log-${c.id}"></div>`).join('')}</div>
<script nonce="${nonce}" src="${hl}"></script>
<script nonce="${nonce}" src="${pl}"></script>
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
  let rules = PemHighlight.DEFAULT_RULES();
  const engines = {};
  ids.forEach((id) => { engines[id] = new PemHighlight.Engine(rules); });
  const styleCss = (st) => (st.background ? 'background:' + st.background + ';' : '') + (st.foreground ? 'color:' + st.foreground + ';' : '') + (st.bold ? 'font-weight:bold;' : '');
  function marked(t) {
    if (!query) return esc(t);
    const lo = t.toLowerCase(), q = query.toLowerCase();
    let i = 0, out = '', at;
    while ((at = lo.indexOf(q, i)) >= 0) { out += esc(t.slice(i, at)) + '<mark>' + esc(t.slice(at, at + q.length)) + '</mark>'; i = at + q.length; }
    return out + esc(t.slice(i));
  }
  const detectors = {};
  ids.forEach((id) => { detectors[id] = new PemPayload.PayloadDetector(); });
  function badge(div) {
    const b = document.createElement('button');
    b.className = 'pl'; b.textContent = div._payload.kind === 'json' ? '{ } JSON' : '</> SOAP/XML'; b.title = 'Abrir formatado num editor';
    b.addEventListener('click', (e) => { e.stopPropagation(); showPayload(div._payload.kind, div._payload.text); });
    // numa linha própria, logo abaixo do trecho (com linhas gigantes o botão no fim do texto ficava a meio do ecrã)
    const w = document.createElement('div');
    w.className = 'plw'; w.appendChild(b);
    div.appendChild(w);
  }
  let pvCur = null;
  function colorize(kind, t) {
    const e = esc(t);
    if (kind === 'json') return e.replace(/("(?:\\\\.|[^"\\\\])*")(\\s*:)?/g, (m, s, c) => c ? '<span class="ky">' + s + '</span>' + c : '<span class="st">' + s + '</span>');
    return e.replace(/(&lt;\\/?)([\\w:.-]+)/g, '$1<span class="tg">$2</span>');
  }
  let pvLines = [], pvEnds = [], pvCol = new Set();
  function applyFold() {
    const rows = $('pvt').children;
    let until = -1;
    for (let i = 0; i < rows.length; i++) {
      const r = rows[i];
      if (i <= until) { r.style.display = 'none'; continue; }
      r.style.display = '';
      const c = pvCol.has(i);
      r.classList.toggle('col', c);
      if (pvEnds[i] >= 0) r.firstChild.textContent = c ? '▸' : '▾';
      if (c) until = pvEnds[i];
    }
  }
  function toggleFold(i) { if (pvCol.has(i)) pvCol.delete(i); else pvCol.add(i); applyFold(); }
  function showPayload(kind, text) {
    pvCur = { kind, text };
    $('pvk').textContent = kind === 'json' ? 'JSON' : 'SOAP / XML';
    pvLines = PemPayload.format(kind, text).split('\\n');
    pvEnds = PemPayload.foldRegions(pvLines);
    pvCol = new Set();
    const box = $('pvt');
    box.textContent = '';
    pvLines.forEach((ln, i) => {
      const row = document.createElement('div'), g = document.createElement('span'), t = document.createElement('span'), f = document.createElement('span');
      row.className = 'fl'; g.className = 'fg'; f.className = 'fs';
      if (pvEnds[i] >= 0) {
        g.textContent = '▾'; g.title = 'Recolher / expandir';
        g.addEventListener('click', () => toggleFold(i));
        f.textContent = ' … ' + PemPayload.foldTail(pvLines, i, pvEnds[i]); f.title = 'Expandir';
        f.addEventListener('click', () => toggleFold(i));
      }
      t.innerHTML = colorize(kind, ln);
      row.append(g, t, f);
      box.appendChild(row);
    });
    $('pv').classList.add('open');
  }
  $('pvcol').addEventListener('click', () => { pvCol = new Set(); pvEnds.forEach((e, i) => { if (e >= 0 && /^\\s/.test(pvLines[i])) pvCol.add(i); }); applyFold(); });
  $('pvexp').addEventListener('click', () => { pvCol = new Set(); applyFold(); });
  $('pvx').addEventListener('click', () => $('pv').classList.remove('open'));
  $('pvcopy').addEventListener('click', () => { if (pvCur) vscode.postMessage({ type: 'copyText', text: PemPayload.format(pvCur.kind, pvCur.text) }); });
  $('pved').addEventListener('click', () => { if (pvCur) vscode.postMessage({ type: 'openPayload', kind: pvCur.kind, text: pvCur.text }); });
  window.addEventListener('keydown', (e) => { if (e.key === 'Escape') $('pv').classList.remove('open'); });
  function paint(div) {
    paintText(div);
    if (div._payload) badge(div);
  }
  function paintText(div) {
    const segs = div._segs || [{ text: div.dataset.t, style: null }];
    div.style.cssText = segs.length === 1 && segs[0].whole && segs[0].style ? styleCss(segs[0].style) : '';
    if (segs.length === 1) { if (!query) div.textContent = segs[0].text; else div.innerHTML = marked(segs[0].text); return; }
    div.innerHTML = segs.map((g) => g.style ? '<span style="' + styleCss(g.style) + '">' + marked(g.text) + '</span>' : marked(g.text)).join('');
  }
  function addLine(id, raw) {
    const el = logEl(id), d = document.createElement('div'), t = raw.replace(ansi, '');
    d.className = 'l ' + kind(t); d.dataset.t = t; d._segs = engines[id].segments(t); el.appendChild(d);
    for (const ev of detectors[id].push(t, d)) { ev.ref._payload = { kind: ev.kind, text: ev.text }; paint(ev.ref); }
    paint(d);
    if (el.childElementCount > MAX) el.removeChild(el.firstChild);
  }
  function applyRules(r) {
    rules = Array.isArray(r) ? r : PemHighlight.DEFAULT_RULES();
    ids.forEach((id) => { const en = engines[id]; en.setRules(rules); logEl(id).childNodes.forEach((d) => { d._segs = en.segments(d.dataset.t); paint(d); }); });
    collect();
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
  let fontSize = 12;
  function setFont(n) { fontSize = n; $('logs').style.setProperty('--fs', n + 'px'); }
  $('hl').addEventListener('click', () => vscode.postMessage({ type: 'openEditor' }));
  window.addEventListener('keydown', (e) => { if ((e.ctrlKey || e.metaKey) && e.key === 'f') { e.preventDefault(); $('q').focus(); $('q').select(); } });
  window.addEventListener('message', (e) => {
    const m = e.data;
    if (m.type === 'init') { if (m.fontSize) setFont(m.fontSize); if (m.rules) { rules = m.rules; ids.forEach((id) => engines[id].setRules(rules)); } else { ids.forEach((id) => engines[id].setRules(rules)); } ids.forEach((id) => { engines[id].reset(); detectors[id].reset(); logEl(id).textContent = ''; (m.data[id] || []).forEach((l) => addLine(id, l)); }); select(m.active || ids[0]); if ($('ed').classList.contains('open')) editor(); }
    else if (m.type === 'font') setFont(m.size);
    else if (m.type === 'rules') applyRules(m.rules);
    else if (m.type === 'append') { m.lines.forEach((l) => addLine(m.channel, l)); if (query && m.channel === active) { const k = cur; collect(); cur = Math.min(k, marks.length - 1); } stick(); }
    else if (m.type === 'reset') { logEl(m.channel).textContent = ''; engines[m.channel].reset(); detectors[m.channel].reset(); if (m.channel === active) collect(); }
    else if (m.type === 'select') select(m.channel);
    else if (m.type === 'windowed') $('detach').style.display = 'none';
  });
  vscode.postMessage({ type: 'ready' });
</script></body></html>`;
  }
}
