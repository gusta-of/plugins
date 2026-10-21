/** Utilitários de HTML para os webviews (puros, sem vscode: testáveis). */

export const esc = (s: unknown): string =>
  String(s ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

export function nonce(): string {
  let s = '';
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  for (let i = 0; i < 32; i++) s += chars[Math.floor(Math.random() * chars.length)];
  return s;
}

/** CSS comum aos webviews (usa as variáveis do tema do VS Code). */
export const BASE_CSS = `
  body { padding: 0; margin: 0; font-family: var(--vscode-font-family); font-size: var(--vscode-font-size); color: var(--vscode-foreground); background: var(--vscode-editor-background); }
  .tabs { display: flex; gap: 2px; border-bottom: 1px solid var(--vscode-panel-border, #444); padding: 0 6px; overflow-x: auto; white-space: nowrap; }
  .tab { padding: 5px 10px; cursor: pointer; opacity: .75; border-bottom: 2px solid transparent; }
  .tab.active { opacity: 1; border-bottom-color: var(--vscode-focusBorder, #3794ff); }
  .tab:hover { opacity: 1; }
  .bar { display: flex; gap: 8px; align-items: center; padding: 4px 8px; flex-wrap: wrap; }
  .bar .info { opacity: .75; }
  button { background: var(--vscode-button-secondaryBackground); color: var(--vscode-button-secondaryForeground); border: none; padding: 3px 9px; cursor: pointer; border-radius: 2px; }
  button:hover { background: var(--vscode-button-secondaryHoverBackground); }
  input[type=text], select, textarea { background: var(--vscode-input-background); color: var(--vscode-input-foreground); border: 1px solid var(--vscode-input-border, transparent); padding: 3px 5px; box-sizing: border-box; font-family: inherit; }
  .grid { overflow: auto; }
  table { border-collapse: collapse; width: max-content; min-width: 100%; }
  th, td { border: 1px solid var(--vscode-panel-border, #3c3c3c); padding: 2px 8px; text-align: left; max-width: 480px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  th { position: sticky; top: 0; background: var(--vscode-editorGroupHeader-tabsBackground, #252526); z-index: 1; font-weight: 600; }
  td.num { text-align: right; } td.rn { text-align: right; opacity: .5; } td.null { opacity: .5; font-style: italic; }
  tr:hover td { background: var(--vscode-list-hoverBackground); }
  a.link { color: var(--vscode-textLink-foreground); text-decoration: underline; cursor: pointer; }
  .err { color: var(--vscode-errorForeground); } .ok { color: var(--vscode-testing-iconPassed, #4caf50); } .warn { color: var(--vscode-editorWarning-foreground, #ffa726); } .dim { opacity: .65; }
  pre.log { margin: 0; padding: 6px 10px; font-family: var(--vscode-editor-font-family); white-space: pre-wrap; }
  .empty { padding: 14px; opacity: .6; text-align: center; }
`;

export function cell(v: string | null | undefined, numeric = false): string {
  if (v === null || v === undefined) return '<td class="null">[NULL]</td>';
  const flat = v.length > 2000 ? v.slice(0, 2000) : v;
  return `<td class="${numeric ? 'num' : ''}" title="${esc(v.slice(0, 600))}">${esc(flat.replace(/\r?\n/g, ' ↵ '))}</td>`;
}

/** CSV (separador ;) compatível com o Excel pt, com BOM. */
export function toCsv(columns: string[], rows: (string | null)[][]): string {
  const q = (s: string | null) => (s === null ? '' : /[;"\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s);
  return '﻿' + [columns.map(q).join(';'), ...rows.map((r) => r.map(q).join(';'))].join('\r\n') + '\r\n';
}
