// Copia o CSS/fonte dos codicons para media/ (o webview carrega-os a partir da extensão).
const fs = require('node:fs');
const path = require('node:path');
const src = path.join(__dirname, '..', 'node_modules', '@vscode', 'codicons', 'dist');
const dst = path.join(__dirname, '..', 'media');
fs.mkdirSync(dst, { recursive: true });
for (const f of ['codicon.css', 'codicon.ttf']) fs.copyFileSync(path.join(src, f), path.join(dst, f));
