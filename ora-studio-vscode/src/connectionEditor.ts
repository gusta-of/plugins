import * as vscode from 'vscode';
import { Core, errText } from './core';
import { BASE_CSS, esc, nonce } from './html';
import { ConnDto } from './model';

/** URL JDBC gerada a partir dos campos (igual à do Ora Studio Swing). */
export function generatedUrl(type: string, host: string, port: number, database: string): string {
  if (type === 'SERVICE_NAME') return `jdbc:oracle:thin:@//${host}:${port}/${database}`;
  if (type === 'SID') return `jdbc:oracle:thin:@${host}:${port}:${database}`;
  return '';
}

export function blankConnection(): ConnDto {
  return {
    id: '', name: 'Nova ligação', driverId: 'oracle', type: 'SID', host: 'localhost', port: 1521, database: 'XE', user: '',
    savePassword: true, url: null, autoCommit: true, rowLimit: 500, connectTimeoutSec: 15, initSql: null, hasPassword: false,
  };
}

/** Formulário de ligação (como o "Data Sources" do IntelliJ): campos, URL, "Testar ligação" e Guardar. */
export class ConnectionEditor {
  private panel?: vscode.WebviewPanel;

  constructor(private readonly core: Core, private readonly onSaved: (id: string) => void) {}

  open(initial?: ConnDto): void {
    const conn = initial ?? blankConnection();
    this.panel?.dispose();
    const panel = vscode.window.createWebviewPanel('oraStudioConnection', conn.id ? `Ligação: ${conn.name}` : 'Nova ligação', vscode.ViewColumn.Active, { enableScripts: true });
    this.panel = panel;
    panel.onDidDispose(() => { if (this.panel === panel) this.panel = undefined; });
    panel.webview.html = this.html(conn);
    panel.webview.onDidReceiveMessage(async (m) => {
      try {
        if (m.type === 'test') {
          const r = await this.core.client.request<{ version: string; driver: string }>('connection.test', { connection: m.connection, password: m.password || undefined }, 60000);
          panel.webview.postMessage({ type: 'testResult', ok: true, text: `OK — ${r.version} (driver ${r.driver})` });
        } else if (m.type === 'save') {
          const params: Record<string, unknown> = { connection: m.connection };
          if (m.password) params.password = m.password;
          await this.core.client.request('connection.save', params);
          await this.core.refreshConfig();
          this.core.forget(m.connection.id || '');
          const saved = this.core.connections.find((c) => c.id === m.connection.id) ?? this.core.connections[this.core.connections.length - 1];
          this.onSaved(saved.id);
          panel.dispose();
        } else if (m.type === 'drivers') {
          await vscode.commands.executeCommand('oraStudio.configureDrivers');
          await this.core.refreshConfig();
          panel.webview.postMessage({ type: 'drivers', drivers: this.core.drivers });
        }
      } catch (e) {
        panel.webview.postMessage({ type: 'testResult', ok: false, text: errText(e).split('\n')[0] });
      }
    });
  }

  private html(c: ConnDto): string {
    const n = nonce();
    const id = c.id || (globalThis.crypto?.randomUUID?.() ?? `conn-${Date.now()}`);
    const drivers = this.core.drivers.map((d) => `<option value="${esc(d.id)}"${d.id === c.driverId ? ' selected' : ''}>${esc(d.name)}${d.jars[0] ? '' : ' (sem JAR!)'}</option>`).join('');
    return `<!DOCTYPE html><html><head><meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-${n}';">
<style>${BASE_CSS}
  body { padding: 14px 20px; max-width: 760px; } h2 { margin-top: 0; }
  .f { display: grid; grid-template-columns: 150px 1fr; gap: 8px 10px; align-items: center; margin-bottom: 8px; }
  .f label { opacity: .85; } .f input[type=text], .f input[type=password], .f input[type=number], .f select, .f textarea { width: 100%; }
  .row2 { display: flex; gap: 8px; } .actions { display: flex; gap: 8px; align-items: center; margin-top: 14px; }
  button.primary { background: var(--vscode-button-background); color: var(--vscode-button-foreground); }
  #res { min-height: 1.4em; } small { opacity: .6; }
</style></head><body>
<h2>${c.id ? 'Editar ligação' : 'Nova ligação'}</h2>
<div class="f"><label>Nome</label><input type="text" id="name" value="${esc(c.name)}">
<label>Driver</label><div class="row2"><select id="driver">${drivers}</select><button id="drv">Drivers…</button></div>
<label>Tipo de ligação</label><select id="type"><option value="SID">SID</option><option value="SERVICE_NAME">Service name</option><option value="URL">URL</option></select>
<label>Host</label><div class="row2"><input type="text" id="host" value="${esc(c.host)}"><input type="number" id="port" value="${c.port}" style="width:90px"></div>
<label id="dblabel">SID</label><input type="text" id="database" value="${esc(c.database)}">
<label>Utilizador</label><input type="text" id="user" value="${esc(c.user)}">
<label>Palavra-passe</label><div class="row2"><input type="password" id="password" placeholder="${c.hasPassword ? '<guardada>' : ''}"><select id="save"><option value="1">Guardar</option><option value="0">Perguntar sempre</option></select></div>
<label>URL</label><div><input type="text" id="url"><small>O URL sobrepõe-se aos campos acima se for editado.</small></div>
<label>Auto-commit</label><div><input type="checkbox" id="autocommit"> nas consolas</div>
<label>Linhas por página</label><input type="number" id="rowlimit" value="${c.rowLimit}">
<label>Timeout de ligação (s)</label><input type="number" id="timeout" value="${c.connectTimeoutSec}">
<label>SQL ao ligar</label><textarea id="init" rows="3">${esc(c.initSql ?? '')}</textarea></div>
<div class="actions"><button id="test">Testar ligação</button><button class="primary" id="saveBtn">Guardar</button><span id="res"></span></div>
<script nonce="${n}">
  const vscode = acquireVsCodeApi(); const $ = (i) => document.getElementById(i);
  const orig = ${JSON.stringify({ type: c.type, url: c.url })};
  $('type').value = orig.type; $('save').value = ${c.savePassword ? '"1"' : '"0"'}; $('autocommit').checked = ${c.autoCommit}; let urlEdited = !!orig.url;
  const gen = () => { const t = $('type').value, h = $('host').value.trim(), p = $('port').value || 1521, d = $('database').value.trim();
    return t === 'SERVICE_NAME' ? 'jdbc:oracle:thin:@//' + h + ':' + p + '/' + d : t === 'SID' ? 'jdbc:oracle:thin:@' + h + ':' + p + ':' + d : ''; };
  const sync = () => { $('dblabel').textContent = $('type').value === 'SERVICE_NAME' ? 'Service name' : $('type').value === 'SID' ? 'SID' : 'Base de dados';
    for (const i of ['host','port','database']) $(i).disabled = $('type').value === 'URL'; if (!urlEdited && $('type').value !== 'URL') $('url').value = gen(); };
  $('url').value = orig.url || gen(); sync();
  for (const i of ['type','host','port','database']) $(i).addEventListener('input', sync);
  $('url').addEventListener('input', () => { urlEdited = $('url').value.trim() !== gen(); });
  const conn = () => { const typed = $('url').value.trim(); const t = $('type').value;
    return { id: ${JSON.stringify(id)}, name: $('name').value.trim() || typed, driverId: $('driver').value, type: t, host: $('host').value.trim(), port: Number($('port').value) || 1521, database: $('database').value.trim(), user: $('user').value.trim(),
      savePassword: $('save').value === '1', url: t === 'URL' ? typed : (typed && typed !== gen() ? typed : null), autoCommit: $('autocommit').checked, rowLimit: Number($('rowlimit').value) || 500, connectTimeoutSec: Number($('timeout').value) || 15, initSql: $('init').value.trim() || null }; };
  $('test').addEventListener('click', () => { $('res').className = 'dim'; $('res').textContent = 'A testar…'; vscode.postMessage({ type: 'test', connection: conn(), password: $('password').value }); });
  $('saveBtn').addEventListener('click', () => vscode.postMessage({ type: 'save', connection: conn(), password: $('password').value }));
  $('drv').addEventListener('click', () => vscode.postMessage({ type: 'drivers' }));
  window.addEventListener('message', (e) => { const m = e.data;
    if (m.type === 'testResult') { $('res').className = m.ok ? 'ok' : 'err'; $('res').textContent = m.text; }
    if (m.type === 'drivers') { $('driver').innerHTML = m.drivers.map((d) => '<option value="' + d.id + '">' + d.name + (d.jars.length ? '' : ' (sem JAR!)') + '</option>').join(''); } });
</script></body></html>`;
  }
}
