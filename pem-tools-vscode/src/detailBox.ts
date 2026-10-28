import * as vscode from 'vscode';

const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

/** Painel com um texto longo (erro/saída) numa caixa só de leitura, com botão «Copiar tudo». */
export function showTextBox(title: string, text: string): void {
  const p = vscode.window.createWebviewPanel('pemWlTools.detail', title, { viewColumn: vscode.ViewColumn.Active }, { enableScripts: true });
  const nonce = Math.random().toString(36).slice(2) + Date.now().toString(36);
  p.webview.onDidReceiveMessage(async (m) => {
    if (m?.type === 'copy') { await vscode.env.clipboard.writeText(text); void vscode.window.setStatusBarMessage('$(check) Copiado', 2500); }
  });
  p.webview.html = `<!DOCTYPE html><html><head><meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-${nonce}';">
<style>body { padding: 12px 18px; font-family: var(--vscode-font-family); color: var(--vscode-foreground); } h3 { margin: 0 0 8px; color: var(--vscode-errorForeground); }
 textarea { width: 100%; height: calc(100vh - 100px); box-sizing: border-box; font-family: var(--vscode-editor-font-family, monospace); font-size: 12px; white-space: pre; resize: none; background: var(--vscode-input-background); color: var(--vscode-input-foreground); border: 1px solid var(--vscode-input-border, #555); }
 button { margin: 0 0 8px; background: var(--vscode-button-background); color: var(--vscode-button-foreground); border: 0; padding: 3px 12px; cursor: pointer; }</style></head><body>
<h3>${esc(title)}</h3><button id="c">Copiar tudo</button> <span id="o"></span>
<textarea readonly spellcheck="false">${esc(text)}</textarea>
<script nonce="${nonce}">const v = acquireVsCodeApi(); document.getElementById('c').addEventListener('click', () => { v.postMessage({ type: 'copy' }); document.getElementById('o').textContent = 'Copiado'; });</script></body></html>`;
}
