import * as fs from 'node:fs';
import * as vscode from 'vscode';
import { Core, errText } from './core';
import { BASE_CSS, nonce, toCsv } from './html';
import { ObjRef, TYPE_LABEL, isRelation, refKey } from './model';

/** Tabs por tipo de objeto (cada uma carrega só quando é aberta). */
export function tabsFor(type: string): string[] {
  if (type === 'TABLE') return ['Colunas', 'Constraints', 'Índices', 'Triggers', 'Dados', 'Dependências'];
  if (type === 'VIEW') return ['Colunas', 'Triggers', 'Dados', 'Dependências'];
  if (type === 'MATERIALIZED VIEW') return ['Colunas', 'Índices', 'Dados', 'Dependências'];
  return ['Dependências'];
}

/** Estrutura de um objeto (colunas, constraints, índices, triggers, dados, dependências) com links para outros objetos. */
export class StructureView {
  private readonly panels = new Map<string, vscode.WebviewPanel>();

  constructor(private readonly core: Core) {}

  open(connId: string, ref: ObjRef): vscode.WebviewPanel {
    const key = `${connId}|${refKey(ref)}`;
    const existing = this.panels.get(key);
    if (existing) { existing.reveal(); return existing; }
    const panel = vscode.window.createWebviewPanel('oraStudioStructure', ref.name, vscode.ViewColumn.Active, { enableScripts: true, retainContextWhenHidden: true });
    this.panels.set(key, panel);
    panel.onDidDispose(() => this.panels.delete(key));
    panel.webview.html = this.html(ref);
    panel.webview.onDidReceiveMessage((m) => this.onMessage(panel, connId, ref, m));
    return panel;
  }

  private async onMessage(panel: vscode.WebviewPanel, connId: string, ref: ObjRef, m: any): Promise<void> {
    const reply = (msg: unknown) => panel.webview.postMessage(msg);
    try {
      switch (m.type) {
        case 'load': reply({ type: 'data', tab: m.tab, data: await this.load(connId, ref, m.tab, m) }); break;
        case 'more': {
          const r = await this.core.client.request<{ rows: (string | null)[][]; hasMore: boolean }>('cursor.fetch', { cursorId: m.cursorId, n: m.n });
          reply({ type: 'rows', ...r });
          break;
        }
        case 'open': await vscode.commands.executeCommand('oraStudio.openObject', connId, m.ref); break;
        case 'ddl': await vscode.commands.executeCommand('oraStudio.openDdl', connId, ref); break;
        case 'console': await vscode.commands.executeCommand('oraStudio.selectFrom', { kind: 'object', connId, ref }); break;
        case 'csv': {
          const target = await vscode.window.showSaveDialog({ defaultUri: vscode.Uri.file(`${ref.name}.csv`), filters: { CSV: ['csv'] } });
          if (target) fs.writeFileSync(target.fsPath, toCsv(m.columns, m.rows), 'utf8');
          break;
        }
        case 'reload': panel.webview.html = this.html(ref); break;
      }
    } catch (e) {
      reply({ type: 'error', tab: m.tab, message: errText(e) });
    }
  }

  /** Carrega uma aba (nome em PT-PT, igual ao do Ora Studio Swing). */
  async load(connId: string, ref: ObjRef, tab: string, m: { where?: string; limit?: number } = {}): Promise<unknown> {
    const id = connId;
    const c = this.core.client;
    switch (tab) {
      case 'Colunas': return c.request('meta.columns', { id, ref });
      case 'Constraints': return c.request('meta.constraints', { id, ref });
      case 'Índices': return c.request('meta.indexes', { id, ref });
      case 'Triggers': return c.request('meta.triggers', { id, ref });
      case 'Dados': return c.request('table.data', { id, ref, where: m.where ?? '', limit: m.limit ?? 500 });
      case 'Dependências': {
        const [uses, usedBy] = await Promise.all([c.request('meta.uses', { id, ref }), c.request('meta.usedBy', { id, ref })]);
        return { uses, usedBy };
      }
      default: throw new Error(`aba desconhecida: ${tab}`);
    }
  }

  private html(ref: ObjRef): string {
    const n = nonce();
    const tabs = tabsFor(ref.type);
    const label = TYPE_LABEL[ref.type] ?? ref.type;
    return `<!DOCTYPE html><html><head><meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-${n}';">
<style>${BASE_CSS}
  .head { padding: 8px 12px; display: flex; gap: 10px; align-items: baseline; flex-wrap: wrap; } .head h2 { margin: 0; font-size: 1.25em; }
  #content { padding: 0 6px 12px; } .grid { max-height: calc(100vh - 150px); }
</style></head><body>
<div class="head"><h2>${ref.owner}.${ref.name}</h2><span class="dim">${label}</span>
  <button id="ddl">Abrir DDL / código</button>${isRelation(ref.type) ? '<button id="sel">SELECT * numa consola</button>' : ''}<button id="reload">Atualizar</button></div>
<div class="tabs" id="tabs">${tabs.map((t, i) => `<div class="tab${i === 0 ? ' active' : ''}" data-tab="${t}">${t}</div>`).join('')}</div>
<div id="content"><div class="empty">A carregar…</div></div>
<script nonce="${n}">
  const vscode = acquireVsCodeApi();
  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s == null ? '' : s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
  const loaded = {}; let active = ${JSON.stringify(tabs[0])}; let dataState = null;
  const link = (r) => '<a class="link" data-ref="' + esc(JSON.stringify(r)) + '">' + esc(r.owner + '.' + r.name) + '</a>';
  const table = (heads, rows) => rows.length ? '<div class="grid"><table><tr>' + heads.map((h) => '<th>' + esc(h) + '</th>').join('') + '</tr>' + rows.map((r) => '<tr>' + r.map((c) => '<td>' + c + '</td>').join('') + '</tr>').join('') + '</table></div>' : '<div class="empty">Sem registos</div>';

  function wire() { document.querySelectorAll('a.link').forEach((a) => a.addEventListener('click', () => vscode.postMessage({ type: 'open', ref: JSON.parse(a.dataset.ref) }))); }

  function render(tab, d) {
    const c = $('content');
    if (tab === 'Colunas') c.innerHTML = table(['#', 'Coluna', 'Tipo', 'Nulo?', 'Default', 'Comentário'], d.map((x) => [x.id, esc(x.name), esc(x.type), x.nullable ? 'SIM' : 'NÃO', esc(x.default), esc(x.comment)]));
    else if (tab === 'Constraints') c.innerHTML = table(['Nome', 'Tipo', 'Colunas', 'Referência', 'Colunas ref.', 'On delete', 'Condição', 'Estado'], d.map((x) => [esc(x.name), esc(x.type), esc(x.columns), x.refTable ? link(x.refTable) : '', esc(x.refColumns), esc(x.deleteRule), esc(x.condition), esc(x.status)]));
    else if (tab === 'Índices') c.innerHTML = table(['Nome', 'Tipo', 'Único', 'Colunas', 'Estado'], d.map((x) => [esc(x.name), esc(x.type), x.unique ? 'SIM' : 'NÃO', esc(x.columns), esc(x.status)]));
    else if (tab === 'Triggers') c.innerHTML = table(['Trigger', 'Evento', 'Tipo', 'Estado'], d.map((x) => [link(x.ref), esc(x.event), esc(x.type), esc(x.status)]));
    else if (tab === 'Dependências') c.innerHTML = '<h3>Usa (' + d.uses.length + ')</h3>' + table(['Objeto', 'Tipo'], d.uses.map((x) => [link(x.ref), esc(x.ref.type)])) + '<h3>Usado por (' + d.usedBy.length + ')</h3>' + table(['Objeto', 'Tipo'], d.usedBy.map((x) => [link(x.ref), esc(x.ref.type)]));
    else if (tab === 'Dados') renderData(d);
    wire();
  }

  function dataTable() {
    const d = dataState;
    return '<div class="bar"><span class="info">' + d.rows.length + ' linha(s)' + (d.hasMore ? ' (há mais)' : '') + ' • ' + d.elapsedMs + ' ms</span><button id="more"' + (d.hasMore ? '' : ' disabled') + '>Carregar mais</button><button id="csv">Exportar CSV…</button></div>'
      + '<div class="grid"><table><tr><th>#</th>' + d.columns.map((c) => '<th title="' + esc(c.typeName) + '">' + esc(c.name) + '</th>').join('') + '</tr>'
      + d.rows.map((r, i) => '<tr><td class="rn">' + (i + 1) + '</td>' + r.map((v, j) => v === null ? '<td class="null">[NULL]</td>' : '<td class="' + (d.columns[j].numeric ? 'num' : '') + '" title="' + esc(String(v).slice(0, 600)) + '">' + esc(String(v).slice(0, 2000).replace(/\\r?\\n/g, ' ↵ ')) + '</td>').join('') + '</tr>').join('') + '</table></div>';
  }

  function renderData(d) {
    if (d) dataState = d;
    $('content').innerHTML = '<div class="bar"><span>WHERE / ORDER BY:</span><input type="text" id="where" size="40" placeholder="ex.: ID > 10 ORDER BY NOME"><select id="limit"><option>100</option><option selected>500</option><option>1000</option><option>5000</option></select><button id="go">Carregar</button></div><div id="datares">' + (dataState ? dataTable() : '<div class="empty">A carregar…</div>') + '</div>';
    const run = () => { $('datares').innerHTML = '<div class="empty">A carregar…</div>'; vscode.postMessage({ type: 'load', tab: 'Dados', where: $('where').value, limit: Number($('limit').value) }); };
    $('go').addEventListener('click', run); $('where').addEventListener('keydown', (e) => { if (e.key === 'Enter') run(); });
    if (dataState) bindData();
  }

  function bindData() {
    const more = $('more'); if (more) more.addEventListener('click', () => vscode.postMessage({ type: 'more', cursorId: dataState.cursorId, n: 500 }));
    const csv = $('csv'); if (csv) csv.addEventListener('click', () => vscode.postMessage({ type: 'csv', columns: dataState.columns.map((c) => c.name), rows: dataState.rows }));
  }

  function load(tab) { active = tab; document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('active', t.dataset.tab === tab)); if (loaded[tab] !== undefined && tab !== 'Dados') { render(tab, loaded[tab]); return; } if (tab === 'Dados' && dataState) { renderData(); return; } $('content').innerHTML = '<div class="empty">A carregar…</div>'; vscode.postMessage({ type: 'load', tab }); }
  document.querySelectorAll('.tab').forEach((t) => t.addEventListener('click', () => load(t.dataset.tab)));
  $('ddl').addEventListener('click', () => vscode.postMessage({ type: 'ddl' }));
  $('reload').addEventListener('click', () => vscode.postMessage({ type: 'reload' }));
  if ($('sel')) $('sel').addEventListener('click', () => vscode.postMessage({ type: 'console' }));

  window.addEventListener('message', (e) => {
    const m = e.data;
    if (m.type === 'data') {
      if (m.tab === 'Dados') { dataState = m.data; if (active === 'Dados') { $('datares') ? ($('datares').innerHTML = dataTable(), bindData()) : renderData(); } }
      else { loaded[m.tab] = m.data; if (active === m.tab) render(m.tab, m.data); }
    } else if (m.type === 'rows') {
      dataState.rows = dataState.rows.concat(m.rows); dataState.hasMore = m.hasMore; if (active === 'Dados') { $('datares').innerHTML = dataTable(); bindData(); }
    } else if (m.type === 'error') {
      if (m.tab === active) $('content').innerHTML = '<div class="empty err">' + esc(m.message) + '</div>';
    }
  });
  load(active);
</script></body></html>`;
  }
}
