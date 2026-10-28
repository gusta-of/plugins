import * as fs from 'node:fs';
import * as path from 'node:path';

export type AppState = 'pending' | 'loading' | 'ready' | 'failed';

export interface WlApp {
  name: string;
  state: AppState;
  /** último estado de deployment visto (ex.: "STATE_PREPARED") */
  detail?: string;
  error?: string;
}

const MAX_ERROR_LINES = 150;
const HEADER = /^(?:####)?<\d{1,2}\/[^>]+>/;

/** Artefactos (app-deployment) declarados em config/config.xml do domínio. */
export function expectedApps(domain: string): string[] {
  try {
    const xml = fs.readFileSync(path.join(domain, 'config', 'config.xml'), 'utf8');
    const out: string[] = [];
    for (const m of xml.matchAll(/<app-deployment>([\s\S]*?)<\/app-deployment>/g)) {
      const n = /<name>([^<]+)<\/name>/.exec(m[1]);
      if (n) out.push(n[1].trim());
    }
    return out;
  } catch {
    return [];
  }
}

/**
 * Acompanha o arranque do WebLogic: o estado do servidor e de cada artefacto (a carregar / pronto / falhou),
 * a partir do stdout do startWebLogic e do ficheiro de log do servidor (os deployments em INFO só aí aparecem).
 */
export class WlWatcher {
  readonly apps = new Map<string, WlApp>();
  serverState = '';
  active = false;
  onChange: () => void = () => {};

  private timer?: NodeJS.Timeout;
  private file = '';
  private pos = 0;
  private rest = '';
  private capturing = new Map<'out' | 'file', { app: string; lines: string[] } | undefined>();

  start(domain: string, serverName = 'AdminServer'): void {
    this.stop();
    this.active = true;
    this.apps.clear();
    this.serverState = 'STARTING';
    for (const name of expectedApps(domain)) this.apps.set(name, { name, state: 'pending' });
    this.file = path.join(domain, 'servers', serverName, 'logs', `${serverName}.log`);
    try { this.pos = fs.statSync(this.file).size; } catch { this.pos = 0; }
    this.rest = '';
    this.capturing.clear();
    this.timer = setInterval(() => this.poll(), 1000);
    this.onChange();
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = undefined;
    this.flushCapture('out');
    this.flushCapture('file');
    if (this.active) { this.active = false; this.serverState = 'PARADO'; this.onChange(); }
  }

  private poll(): void {
    let size: number;
    try { size = fs.statSync(this.file).size; } catch { return; }
    if (size < this.pos) { this.pos = 0; this.rest = ''; } // rodou (rotação do log)
    if (size === this.pos) return;
    try {
      const fd = fs.openSync(this.file, 'r');
      try {
        const buf = Buffer.alloc(size - this.pos);
        fs.readSync(fd, buf, 0, buf.length, this.pos);
        this.pos = size;
        const text = this.rest + buf.toString('utf8');
        const parts = text.split(/\r?\n/);
        this.rest = parts.pop() ?? '';
        for (const l of parts) this.feed(l, 'file');
      } finally { fs.closeSync(fd); }
    } catch { /* ficheiro em rotação: tenta de novo */ }
  }

  private app(name: string): WlApp {
    let a = this.apps.get(name);
    if (!a) { a = { name, state: 'pending' }; this.apps.set(name, a); }
    return a;
  }

  private flushCapture(src: 'out' | 'file'): void {
    const c = this.capturing.get(src);
    if (!c) return;
    this.capturing.set(src, undefined);
    const a = this.app(c.app);
    const text = c.lines.join('\n');
    if (!a.error || text.length > a.error.length) a.error = text;
    this.onChange();
  }

  /** Uma linha do stdout (`out`) ou do ficheiro de log (`file`). */
  feed(line: string, src: 'out' | 'file' = 'out'): void {
    const cap = this.capturing.get(src);
    if (cap) {
      if (HEADER.test(line)) this.flushCapture(src);
      else { if (cap.lines.length < MAX_ERROR_LINES) cap.lines.push(line); return; }
    }

    const st = /Server state changed to ([A-Z_]+)/.exec(line);
    if (st) { this.serverState = st[1]; this.onChange(); return; }

    const tr = /Module (\S+) of application (\S+) (?:\[[^\]]*\] )?(?:successfully )?transitioned from STATE_(\w+) to STATE_(\w+)/.exec(line);
    if (tr) {
      const a = this.app(tr[2]);
      a.detail = `STATE_${tr[4]}`;
      if (a.state !== 'failed') a.state = tr[4] === 'ACTIVE' ? 'ready' : 'loading';
      this.onChange();
      return;
    }

    const ok = /Successfully completed deployment task: .*?(?:deploy|redeploy|start) application (\S+?) on /i.exec(line);
    if (ok) { const a = this.app(ok[1]); if (a.state !== 'failed') a.state = 'ready'; this.onChange(); return; }

    const fail = /Failed to (?:initialize|deploy|prepare|activate|start) the application "([^"]+)"/.exec(line);
    if (fail) {
      const a = this.app(fail[1]);
      a.state = 'failed';
      a.error = undefined;
      this.capturing.set(src, { app: fail[1], lines: [line] });
      this.onChange();
    }
  }

  counts(): { ready: number; loading: number; failed: number; total: number } {
    let ready = 0, loading = 0, failed = 0;
    for (const a of this.apps.values()) {
      if (a.state === 'ready') ready++; else if (a.state === 'failed') failed++; else loading++;
    }
    return { ready, loading, failed, total: this.apps.size };
  }
}
