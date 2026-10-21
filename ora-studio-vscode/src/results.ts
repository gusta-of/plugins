import * as fs from 'node:fs';
import * as vscode from 'vscode';
import { Core, errText } from './core';
import { BASE_CSS, nonce, toCsv } from './html';

/** Painel «Resultados» (no painel inferior): uma aba por consulta + registo com erros e DBMS_OUTPUT. */
export class ResultsView implements vscode.WebviewViewProvider {
  static readonly viewType = 'oraStudio.results';
  private view?: vscode.WebviewView;
  /** mensagens da execução atual, repetidas se o webview for recriado */
  private replay: unknown[] = [];

  constructor(private readonly core: Core, private readonly onGoto: (docUri: string, start: number, end: number) => void) {}

  resolveWebviewView(view: vscode.WebviewView): void {
    this.view = view;
    view.webview.options = { enableScripts: true };
    view.webview.html = this.html();
    view.webview.onDidReceiveMessage((m) => this.onMessage(m));
    for (const m of this.replay) view.webview.postMessage(m);
  }

  private post(m: unknown): void {
    this.replay.push(m);
    if (this.replay.length > 2000) this.replay.splice(0, this.replay.length - 2000);
    this.view?.webview.postMessage(m);
  }

  reset(title: string, docUri: string): void {
    this.replay = [];
    this.post({ type: 'reset', title, docUri });
  }

  /** Mostra o painel sem roubar o foco ao editor. */
  async reveal(): Promise<void> {
    await vscode.commands.executeCommand('oraStudio.results.focus');
    await vscode.commands.executeCommand('workbench.action.focusActiveEditorGroup');
  }

  /** Recebe as notificações `run/...` do servidor. */
  onRunEvent(method: string, p: any): void {
    switch (method) {
      case 'run/start': this.post({ type: 'start', index: p.index, total: p.total }); break;
      case 'run/query': this.post({ type: 'query', ...p }); break;
      case 'run/update': this.post({ type: 'update', ...p }); break;
      case 'run/error': this.post({ type: 'error', ...p }); break;
      case 'run/output': this.post({ type: 'output', lines: p.lines }); break;
      case 'run/done': this.post({ type: 'done', cancelled: p.cancelled }); break;
    }
  }

  private async onMessage(m: any): Promise<void> {
    switch (m.type) {
      case 'more': {
        try {
          const r = await this.core.client.request<{ rows: (string | null)[][]; hasMore: boolean }>('cursor.fetch', { cursorId: m.cursorId, n: m.n });
          this.post({ type: 'rows', cursorId: m.cursorId, rows: r.rows, hasMore: r.hasMore });
        } catch (e) {
          this.post({ type: 'moreError', cursorId: m.cursorId, message: errText(e) });
        }
        break;
      }
      case 'csv': {
        const target = await vscode.window.showSaveDialog({ defaultUri: vscode.Uri.file('resultado.csv'), filters: { CSV: ['csv'] } });
        if (!target) return;
        fs.writeFileSync(target.fsPath, toCsv(m.columns, m.rows), 'utf8');
        vscode.window.showInformationMessage(`${m.rows.length} linhas exportadas para ${target.fsPath} (apenas as já carregadas)`);
        break;
      }
      case 'copy': await vscode.env.clipboard.writeText(m.text); break;
      case 'goto': this.onGoto(m.docUri, m.start, m.end); break;
    }
  }

  private html(): string {
    const n = nonce();
    return `<!DOCTYPE html><html><head><meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-${n}';">
<style>${BASE_CSS}
  html, body { height: 100%; } body { display: flex; flex-direction: column; }
  #title { padding: 3px 8px; opacity: .8; } #content { flex: 1; min-height: 0; display: flex; flex-direction: column; } .pane { display: none; flex: 1; min-height: 0; flex-direction: column; } .pane.active { display: flex; }
  .grid { flex: 1; min-height: 0; }
</style></head><body>
<div id="title" class="dim">Execute uma instrução (Ctrl+Enter) para ver o resultado aqui.</div>
<div class="tabs" id="tabs"></div>
<div id="content"></div>
<script nonce="${n}">
  const vscode = acquireVsCodeApi();
  const $ = (id) => document.getElementById(id);
  let results = [];   // {id, title, columns, rows, cursorId, hasMore, elapsed}
  let logLines = [];
  let docUri = '';
  let active = 'log';
  const esc = (s) => String(s == null ? '' : s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');

  function log(text, cls, goto) { logLines.push({ text, cls, goto }); if (active === 'log') renderPane(); renderTabs(); }

  function renderTabs() {
    const t = results.map((r) => '<div class="tab' + (active === r.id ? ' active' : '') + '" data-tab="' + r.id + '">' + esc(r.title) + '</div>').join('');
    $('tabs').innerHTML = t + '<div class="tab' + (active === 'log' ? ' active' : '') + '" data-tab="log">Registo</div>';
    document.querySelectorAll('.tab').forEach((el) => el.addEventListener('click', () => { active = el.dataset.tab; renderTabs(); renderPane(); }));
  }

  function tableHtml(r) {
    const head = '<tr><th class="rn">#</th>' + r.columns.map((c) => '<th title="' + esc(c.typeName) + '">' + esc(c.name) + '</th>').join('') + '</tr>';
    const body = r.rows.map((row, i) => '<tr><td class="rn">' + (i + 1) + '</td>' + row.map((v, j) => v === null
      ? '<td class="null">[NULL]</td>'
      : '<td class="' + (r.columns[j].numeric ? 'num' : '') + '" title="' + esc(String(v).slice(0, 600)) + '">' + esc(String(v).slice(0, 2000).replace(/\\r?\\n/g, ' ↵ ')) + '</td>').join('') + '</tr>').join('');
    return '<table>' + head + body + '</table>';
  }

  function renderPane() {
    const c = $('content');
    if (active === 'log') {
      c.innerHTML = '<div class="pane active grid"><pre class="log">' + logLines.map((l, i) => '<div class="' + (l.cls || '') + '"' + (l.goto ? ' data-goto="' + i + '" style="cursor:pointer"' : '') + '>' + esc(l.text) + '</div>').join('') + '</pre></div>';
      c.querySelectorAll('[data-goto]').forEach((el) => el.addEventListener('click', () => { const g = logLines[Number(el.dataset.goto)].goto; vscode.postMessage({ type: 'goto', docUri, start: g.start, end: g.end }); }));
      c.firstChild.scrollTop = c.firstChild.scrollHeight;
      return;
    }
    const r = results.find((x) => x.id === active);
    if (!r) return;
    c.innerHTML = '<div class="bar"><span class="info">' + r.rows.length + ' linha(s)' + (r.hasMore ? ' (há mais)' : '') + ' • ' + r.columns.length + ' coluna(s) • ' + r.elapsed + ' ms</span>'
      + '<button id="more"' + (r.hasMore ? '' : ' disabled') + '>Carregar mais</button><button id="all"' + (r.hasMore ? '' : ' disabled') + '>Carregar tudo</button>'
      + '<button id="csv">Exportar CSV…</button><button id="copy">Copiar tudo</button></div>'
      + '<div class="pane active grid">' + tableHtml(r) + '</div>';
    $('more').addEventListener('click', () => { r.loading = true; vscode.postMessage({ type: 'more', cursorId: r.cursorId, n: 500 }); });
    $('all').addEventListener('click', () => { r.loading = true; vscode.postMessage({ type: 'more', cursorId: r.cursorId, n: 1000000 }); });
    $('csv').addEventListener('click', () => vscode.postMessage({ type: 'csv', columns: r.columns.map((c) => c.name), rows: r.rows }));
    $('copy').addEventListener('click', () => vscode.postMessage({ type: 'copy', text: [r.columns.map((c) => c.name).join('\\t')].concat(r.rows.map((x) => x.map((v) => v == null ? '' : v).join('\\t'))).join('\\n') }));
  }

  window.addEventListener('message', (e) => {
    const m = e.data;
    switch (m.type) {
      case 'reset': results = []; logLines = []; docUri = m.docUri; active = 'log'; $('title').textContent = m.title; log('▶ A executar…', 'dim'); renderTabs(); renderPane(); break;
      case 'start': $('title').textContent = 'A executar ' + (m.index + 1) + '/' + m.total + '…'; break;
      case 'query': {
        const id = 'r' + (results.length + 1);
        results.push({ id, title: '#' + (results.length + 1) + '  ' + (m.text || '').trim().split(/\\r?\\n/)[0].slice(0, 24), columns: m.columns, rows: m.rows, cursorId: m.cursorId, hasMore: m.hasMore, elapsed: m.elapsedMs });
        log('✔ ' + (m.text || '').trim().split(/\\r?\\n/)[0].slice(0, 90) + '  → ' + m.rows.length + (m.hasMore ? '+' : '') + ' linha(s) em ' + m.elapsedMs + ' ms', 'ok');
        active = id; renderTabs(); renderPane(); break;
      }
      case 'update': log('✔ ' + (m.text || '').trim().split(/\\r?\\n/)[0].slice(0, 90) + '  → ' + m.message + ' (' + m.elapsedMs + ' ms)', 'ok'); (m.notes || []).forEach((n) => log('   ' + n, 'warn')); break;
      case 'error': log('✘ ' + (m.text || '').trim().split(/\\r?\\n/)[0].slice(0, 90), 'err', { start: m.position >= 0 ? m.position : m.start, end: m.position >= 0 ? m.position + 1 : m.end }); log('   ' + m.message, 'err'); active = 'log'; renderTabs(); renderPane(); break;
      case 'output': log('— DBMS_OUTPUT —', 'dim'); m.lines.forEach((l) => log(l, 'dim')); break;
      case 'done': $('title').textContent = (m.cancelled ? '■ Cancelado' : 'Concluído') + ' — ' + results.length + ' resultado(s)'; log(m.cancelled ? '■ Cancelado.' : 'Concluído.', m.cancelled ? 'warn' : 'ok'); break;
      case 'rows': { const r = results.find((x) => x.cursorId === m.cursorId); if (r) { r.rows = r.rows.concat(m.rows); r.hasMore = m.hasMore; if (active === r.id) renderPane(); } break; }
      case 'moreError': log('Carregar mais: ' + m.message, 'err'); break;
    }
  });
  renderTabs(); renderPane();
</script></body></html>`;
  }
}
