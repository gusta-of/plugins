import * as vscode from 'vscode';

/** Página do editor de regras de realce e do tamanho da fonte (numa caixa própria). */
export function editorHtml(webview: vscode.Webview, ext: vscode.Uri): string {
  const hl = webview.asWebviewUri(vscode.Uri.joinPath(ext, 'media', 'highlight.js'));
  const nonce = Math.random().toString(36).slice(2) + Date.now().toString(36);
  return `<!DOCTYPE html><html><head><meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-${nonce}' ${webview.cspSource};">
<style>
  body { margin: 0; padding: 12px 18px; font-family: var(--vscode-font-family); font-size: 13px; color: var(--vscode-foreground); background: var(--vscode-editor-background); }
  h2 { margin: 0 0 6px; font-weight: 600; } table { border-collapse: collapse; width: 100%; } td, th { padding: 3px 4px; text-align: left; font-weight: normal; } th { opacity: .7; font-size: 11px; }
  input[type=text] { width: 100%; box-sizing: border-box; background: var(--vscode-input-background); color: var(--vscode-input-foreground); border: 1px solid var(--vscode-input-border, #555); padding: 2px 5px; font-size: 12px; }
  select { background: var(--vscode-dropdown-background); color: var(--vscode-dropdown-foreground); border: 1px solid var(--vscode-dropdown-border, #555); font-size: 12px; }
  button { background: var(--vscode-button-secondaryBackground); color: var(--vscode-button-secondaryForeground); border: 0; padding: 2px 9px; cursor: pointer; } button:hover { filter: brightness(1.2); }
  #esave { background: var(--vscode-button-background); color: var(--vscode-button-foreground); }
  .pat { display: flex; align-items: center; gap: 2px; } .wc { color: var(--vscode-textLink-foreground, #4af); font-weight: bold; min-width: 6px; text-align: center; }
  input[type=color] { width: 30px; height: 20px; padding: 0; border: 1px solid #666; background: none; } .none { opacity: .6; } .acts { margin: 8px 0; display: flex; gap: 8px; flex-wrap: wrap; align-items: center; } p { margin: 4px 0; opacity: .8; font-size: 12px; }
</style></head><body>
<h2>Realce dos logs</h2>
<div id="ed"></div>
<script nonce="${nonce}" src="${hl}"></script>
<script nonce="${nonce}">
  const vscode = acquireVsCodeApi();
  const $ = (i) => document.getElementById(i);
  const esc = (s) => String(s == null ? '' : s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
  let rules = PemHighlight.DEFAULT_RULES();
  let draft = [];
  let fontSize = 12;
  const FONT_SIZES = [8, 9, 10, 11, 12, 13, 14, 16, 18, 20, 22, 24, 28];
  function field(r, i, k, type) {
    if (type === 'chk') return '<input type="checkbox" data-i="' + i + '" data-k="' + k + '"' + (r[k] ? ' checked' : '') + '>';
    if (type === 'col') return '<input type="color" data-i="' + i + '" data-k="' + k + '" value="' + (r[k] || '#000000') + '"><button data-i="' + i + '" data-clr="' + k + '" title="sem cor">×</button>' + (r[k] ? '' : ' <span class="none">—</span>');
    return '<input type="text" data-i="' + i + '" data-k="' + k + '" value="' + esc(r[k] || '').replace(/"/g, '&quot;') + '">';
  }
  const MODES = [['contains', 'Contém'], ['starts', 'Começa com'], ['ends', 'Termina com'], ['equals', 'Igual a'], ['regex', 'Regex']];
  const modeOf = (r) => r.mode || (r.regex === false ? 'contains' : 'regex');
  const modeSel = (r, i) => '<select data-i="' + i + '" data-mode="1">' + MODES.map((m) => '<option value="' + m[0] + '"' + (modeOf(r) === m[0] ? ' selected' : '') + '>' + m[1] + '</option>').join('') + '</select>';
  const wc = (r, side) => { const m = modeOf(r); return (m === 'contains' || (side === 'l' && m === 'ends') || (side === 'r' && m === 'starts')) ? '*' : ''; };
  const patternCell = (r, i) => '<span class="pat"><span class="wc" data-wl="' + i + '">' + wc(r, 'l') + '</span>' + field(r, i, 'pattern') + '<span class="wc" data-wr="' + i + '">' + wc(r, 'r') + '</span></span>';
  function editor() {
    const ed = $('ed');
    ed.innerHTML = '<p><b>Modo</b>: Contém / Começa com / Termina com / Igual a (em texto, <b>*</b> é curinga) ou Regex. Primeira regra que casar com a linha ganha. <b>Fim do bloco</b>: pinta da linha que casa até à que casa com esta 2.ª expressão. Sem <b>Linha toda</b>, só o trecho que casa. Aplica-se também ao texto já mostrado.</p>'
      + '<div class="acts"><label>Tamanho da fonte do terminal <select id="fsz">' + (FONT_SIZES.includes(fontSize) ? FONT_SIZES : FONT_SIZES.concat([fontSize]).sort((a, b) => a - b)).map((n) => '<option value="' + n + '"' + (n === fontSize ? ' selected' : '') + '>' + n + ' px</option>').join('') + '</select></label></div>'
      + '<div class="acts"><button id="eadd">+ Regra</button><button id="eres">Repor predefinições</button><button id="esave">Guardar</button><button id="eclose">Fechar</button><span id="saved" class="none"></span></div>'
      + '<table><tr><th></th><th>Nome</th><th>Expressão</th><th>Modo</th><th>Aa</th><th>Linha toda</th><th>Fim do bloco</th><th>Fundo</th><th>Texto</th><th>Negrito</th><th></th></tr>'
      + draft.map((r, i) => '<tr><td>' + field(r, i, 'enabled', 'chk') + '</td><td>' + field(r, i, 'name') + '</td><td>' + patternCell(r, i) + '</td><td>' + modeSel(r, i) + '</td><td>' + field(r, i, 'caseSensitive', 'chk') + '</td><td>' + field(r, i, 'wholeLine', 'chk') + '</td><td>' + field(r, i, 'blockEnd') + '</td><td>' + field(r, i, 'background', 'col') + '</td><td>' + field(r, i, 'foreground', 'col') + '</td><td>' + field(r, i, 'bold', 'chk') + '</td>'
        + '<td><button data-up="' + i + '">↑</button><button data-dn="' + i + '">↓</button><button data-del="' + i + '">✕</button></td></tr>').join('') + '</table>';
    ed.querySelectorAll('[data-k]').forEach((el) => el.addEventListener('input', () => { const r = draft[Number(el.dataset.i)]; r[el.dataset.k] = el.type === 'checkbox' ? el.checked : el.value; }));
    ed.querySelectorAll('[data-mode]').forEach((el) => el.addEventListener('change', () => { const r = draft[Number(el.dataset.i)]; r.mode = el.value; r.regex = el.value === 'regex'; const i = el.dataset.i; ed.querySelector('[data-wl="' + i + '"]').textContent = wc(r, 'l'); ed.querySelector('[data-wr="' + i + '"]').textContent = wc(r, 'r'); }));
    ed.querySelectorAll('[data-clr]').forEach((el) => el.addEventListener('click', () => { draft[Number(el.dataset.i)][el.dataset.clr] = ''; editor(); }));
    ed.querySelectorAll('[data-del]').forEach((el) => el.addEventListener('click', () => { draft.splice(Number(el.dataset.del), 1); editor(); }));
    ed.querySelectorAll('[data-up]').forEach((el) => el.addEventListener('click', () => { const i = Number(el.dataset.up); if (i > 0) { draft.splice(i - 1, 0, draft.splice(i, 1)[0]); editor(); } }));
    ed.querySelectorAll('[data-dn]').forEach((el) => el.addEventListener('click', () => { const i = Number(el.dataset.dn); if (i < draft.length - 1) { draft.splice(i + 1, 0, draft.splice(i, 1)[0]); editor(); } }));
    $('fsz').addEventListener('change', () => { fontSize = Number($('fsz').value); vscode.postMessage({ type: 'saveFont', size: fontSize }); });
    $('eadd').addEventListener('click', () => { draft.push({ enabled: true, name: 'Nova regra', pattern: '', mode: 'contains', regex: false, caseSensitive: false, wholeLine: true, blockEnd: '', background: '#7A5C00', foreground: '#FFFFFF', bold: false }); editor(); });
    // os webviews não têm confirm(): a confirmação é uma caixa modal do VS Code (pedida ao host)
    $('eres').addEventListener('click', () => vscode.postMessage({ type: 'confirmReset' }));
    $('esave').addEventListener('click', () => { const keep = draft.filter((r) => r.pattern); vscode.postMessage({ type: 'saveRules', rules: keep }); rules = keep; $('saved').textContent = 'Guardado ✓'; setTimeout(() => { $('saved').textContent = ''; }, 2000); });
    $('eclose').addEventListener('click', () => vscode.postMessage({ type: 'close' }));
  }

  window.addEventListener('message', (e) => {
    const m = e.data;
    if (m.type === 'reset') { draft = PemHighlight.DEFAULT_RULES(); editor(); }
    else if (m.type === 'state') { rules = Array.isArray(m.rules) ? m.rules : PemHighlight.DEFAULT_RULES(); if (m.fontSize) fontSize = m.fontSize; draft = JSON.parse(JSON.stringify(rules)); editor(); }
  });
  vscode.postMessage({ type: 'ready' });
</script></body></html>`;
}
