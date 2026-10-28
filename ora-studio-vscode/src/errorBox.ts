import * as vscode from 'vscode';
import { BASE_CSS, esc, nonce } from './html';
import { RpcError } from './rpc';

/** Texto completo do erro (mensagem + pilha de excepção do servidor), para mostrar e copiar. */
export function fullErrorText(e: unknown): string {
  if (e instanceof RpcError) return e.stackTrace ? `${e.message}\n\n${e.stackTrace}` : e.message;
  return e instanceof Error ? `${e.message}\n\n${e.stack ?? ''}`.trim() : String(e);
}

let panel: vscode.WebviewPanel | undefined;

/** Caixa (painel) com o erro completo e botão «Copiar». */
export function showErrorBox(title: string, text: string): void {
  panel?.dispose();
  const p = vscode.window.createWebviewPanel('oraStudioError', 'Erro Oracle', { viewColumn: vscode.ViewColumn.Active }, { enableScripts: true });
  panel = p;
  p.onDidDispose(() => { if (panel === p) panel = undefined; });
  p.webview.onDidReceiveMessage(async (m) => {
    if (m?.type === 'copy') { await vscode.env.clipboard.writeText(text); void vscode.window.setStatusBarMessage('$(check) Erro copiado', 2500); }
  });
  const n = nonce();
  p.webview.html = `<!DOCTYPE html><html><head><meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-${n}';">
<style>${BASE_CSS} body { padding: 14px 20px; } h2 { margin: 0 0 8px; color: var(--vscode-errorForeground); }
  textarea { width: 100%; height: calc(100vh - 110px); box-sizing: border-box; font-family: var(--vscode-editor-font-family, monospace); font-size: 12px; white-space: pre; resize: none; }
  button { margin: 8px 0; }</style></head><body>
<h2>${esc(title)}</h2><button id="copy">Copiar tudo</button> <span id="ok" class="ok"></span>
<textarea id="t" readonly spellcheck="false">${esc(text)}</textarea>
<script nonce="${n}">const v = acquireVsCodeApi(); document.getElementById('copy').addEventListener('click', () => { v.postMessage({ type: 'copy' }); document.getElementById('t').select(); document.getElementById('ok').textContent = 'Copiado'; });</script>
</body></html>`;
}
