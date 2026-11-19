import * as vscode from 'vscode';
import * as M from './modules';

let current: vscode.WebviewPanel | undefined;

/** Ecrã com os módulos que têm build e um campo de parâmetros Maven extra para cada um (e para todos). */
export function openMavenParams(ext: vscode.Uri, read: () => M.MavenArgs, save: (a: M.MavenArgs) => Thenable<void> | void): void {
  if (current) { current.reveal(undefined, false); return; }
  const panel = vscode.window.createWebviewPanel('pemWlTools.mavenParams', 'PEM WL — Parâmetros Maven', vscode.ViewColumn.Active, { enableScripts: true });
  current = panel;
  panel.onDidDispose(() => { if (current === panel) current = undefined; });
  panel.webview.onDidReceiveMessage(async (m) => {
    if (m?.type === 'ready') void panel.webview.postMessage({ type: 'state', args: read(), modules: M.buildPomModules() });
    else if (m?.type === 'save') {
      const out: M.MavenArgs = {};
      for (const [k, v] of Object.entries((m.args ?? {}) as Record<string, unknown>)) { const a = M.cleanArg(v); if (a) out[k] = a; }
      await save(out);
      void panel.webview.postMessage({ type: 'saved' });
    } else if (m?.type === 'close') panel.dispose();
  });
  panel.webview.html = mavenParamsHtml();
}

export function mavenParamsHtml(): string {
  const nonce = Math.random().toString(36).slice(2) + Date.now().toString(36);
  return `<!DOCTYPE html><html><head><meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-${nonce}';">
<style>
  body { margin: 0; padding: 12px 18px; font-family: var(--vscode-font-family); font-size: 13px; color: var(--vscode-foreground); background: var(--vscode-editor-background); }
  h2 { margin: 0 0 6px; font-weight: 600; } p { margin: 4px 0 10px; opacity: .8; }
  table { border-collapse: collapse; width: 100%; } td, th { padding: 3px 4px; text-align: left; font-weight: normal; } th { opacity: .7; font-size: 11px; }
  td.n { white-space: nowrap; } td.d { opacity: .55; white-space: nowrap; font-size: 11px; }
  input[type=text] { width: 100%; box-sizing: border-box; background: var(--vscode-input-background); color: var(--vscode-input-foreground); border: 1px solid var(--vscode-input-border, #555); padding: 2px 5px; font-family: var(--vscode-editor-font-family, monospace); font-size: 12px; }
  button { background: var(--vscode-button-secondaryBackground); color: var(--vscode-button-secondaryForeground); border: 0; padding: 2px 9px; cursor: pointer; } button:hover { filter: brightness(1.2); }
  #save { background: var(--vscode-button-background); color: var(--vscode-button-foreground); }
  .acts { margin: 8px 0; display: flex; gap: 8px; flex-wrap: wrap; align-items: center; } .none { opacity: .6; }
  tr.all td { padding-bottom: 8px; border-bottom: 1px solid var(--vscode-panel-border, #444); } tr.set td.n { font-weight: 600; }
  .chip { border-radius: 10px; padding: 1px 9px; font-family: var(--vscode-editor-font-family, monospace); font-size: 11px; }
</style></head><body>
<h2>Parâmetros Maven por módulo</h2>
<p>O que escreveres é acrescentado ao fim do <code>mvn …</code> do build desse módulo (depois do perfil e dos parâmetros de testes). A linha <b>Todos os módulos</b> vale para qualquer build; o campo do módulo junta-se a ela.
  Ex.: <code>-Dmaven.gitcommitid.skip=true</code> ignora o git-commit-id-plugin só no build local. Ficam guardados nas definições do utilizador (<code>pemWlTools.mavenArgs</code>).</p>
<div class="acts"><input type="text" id="q" placeholder="Filtrar módulos…" style="max-width:260px">
  <span class="none">Sugestões (para «Todos»):</span><button class="chip" data-add="-Dmaven.gitcommitid.skip=true">+ -Dmaven.gitcommitid.skip=true</button><button class="chip" data-add="-o">+ -o (offline)</button><button class="chip" data-add="-U">+ -U</button>
  <button id="save">Guardar</button><button id="close">Fechar</button><span id="saved" class="none"></span></div>
<table id="t"></table>
<script nonce="${nonce}">
  const vscode = acquireVsCodeApi();
  const $ = (i) => document.getElementById(i);
  const esc = (s) => String(s == null ? '' : s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
  let mods = [], args = {};
  function render() {
    const f = $('q').value.trim().toLowerCase();
    const row = (key, name, dir, cls) => '<tr class="' + cls + (args[key] ? ' set' : '') + '"><td class="n">' + esc(name) + '</td><td class="d">' + esc(dir) + '</td><td><input type="text" data-k="' + esc(key) + '" value="' + esc(args[key] || '') + '" placeholder="parâmetros extra, ex.: -Dmaven.gitcommitid.skip=true"></td></tr>';
    $('t').innerHTML = '<tr><th>Módulo</th><th>Pasta</th><th>Parâmetros extra</th></tr>' + row('*', 'Todos os módulos', '', 'all')
      + mods.filter((m) => !f || m.name.toLowerCase().includes(f)).map((m) => row(m.name, m.name, m.kind === 'backend' ? 'backend · ' + m.dir : 'frontend · ' + m.dir, '')).join('');
    $('t').querySelectorAll('input[data-k]').forEach((el) => el.addEventListener('input', () => { args[el.dataset.k] = el.value; el.closest('tr').classList.toggle('set', !!el.value.trim()); }));
  }
  $('q').addEventListener('input', render);
  document.querySelectorAll('[data-add]').forEach((b) => b.addEventListener('click', () => {
    const a = b.dataset.add, cur = (args['*'] || '').trim();
    if (!cur.split(/\\s+/).includes(a)) args['*'] = (cur ? cur + ' ' : '') + a;
    render();
  }));
  $('save').addEventListener('click', () => vscode.postMessage({ type: 'save', args }));
  $('close').addEventListener('click', () => vscode.postMessage({ type: 'close' }));
  window.addEventListener('message', (e) => {
    const m = e.data;
    if (m.type === 'state') { mods = m.modules; args = Object.assign({}, m.args); render(); }
    else if (m.type === 'saved') { $('saved').textContent = 'Guardado ✓'; setTimeout(() => { $('saved').textContent = ''; }, 2000); }
  });
  vscode.postMessage({ type: 'ready' });
</script></body></html>`;
}
