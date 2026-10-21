import { ChildProcess, spawn } from 'node:child_process';
import * as readline from 'node:readline';

export type NotificationHandler = (method: string, params: any) => void;

export class RpcError extends Error {
  constructor(message: string, readonly code = 0) {
    super(message);
  }
}

/**
 * Cliente do servidor Java (ora-studio-server): uma mensagem JSON por linha em stdin/stdout.
 * Reinicia o processo sozinho no próximo pedido se ele tiver morrido.
 */
export class SidecarClient {
  private proc?: ChildProcess;
  private nextId = 1;
  private readonly pending = new Map<number, { resolve: (v: any) => void; reject: (e: Error) => void; method: string }>();
  private readonly handlers = new Set<NotificationHandler>();
  private starting?: Promise<void>;

  constructor(
    private readonly jarPath: string,
    private readonly resolveJava: () => Promise<string>,
    private readonly log: (line: string) => void = () => {},
  ) {}

  onNotification(h: NotificationHandler): { dispose(): void } {
    this.handlers.add(h);
    return { dispose: () => { this.handlers.delete(h); } };
  }

  get running(): boolean {
    return !!this.proc && this.proc.exitCode === null && !this.proc.killed;
  }

  private async ensureStarted(): Promise<void> {
    if (this.running) return;
    if (!this.starting) {
      this.starting = this.start().finally(() => { this.starting = undefined; });
    }
    return this.starting;
  }

  private async start(): Promise<void> {
    const java = await this.resolveJava();
    this.log(`A iniciar o servidor: ${java} -jar ${this.jarPath}`);
    const proc = spawn(java, ['-Doracle.jdbc.timezoneAsRegion=false', '-Xmx768m', '-jar', this.jarPath], { stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
    this.proc = proc;
    readline.createInterface({ input: proc.stdout! }).on('line', (l) => this.onLine(l));
    readline.createInterface({ input: proc.stderr! }).on('line', (l) => this.log(`[servidor] ${l}`));
    proc.on('error', (e) => { this.log(`Falha ao iniciar o servidor: ${e.message}`); this.failAll(new RpcError(`Falha ao iniciar o servidor: ${e.message}`)); });
    proc.on('exit', (code) => {
      this.log(`Servidor terminou (código ${code})`);
      if (this.proc === proc) this.proc = undefined;
      this.failAll(new RpcError('O servidor Ora Studio terminou inesperadamente.'));
    });
  }

  private failAll(err: Error): void {
    for (const p of this.pending.values()) p.reject(err);
    this.pending.clear();
  }

  private onLine(line: string): void {
    if (!line.trim()) return;
    let msg: any;
    try { msg = JSON.parse(line); } catch { this.log(`[servidor] ${line}`); return; }
    if (msg.id !== undefined && msg.id !== null) {
      const p = this.pending.get(msg.id);
      if (!p) return;
      this.pending.delete(msg.id);
      if (msg.error) p.reject(new RpcError(msg.error.message ?? 'erro', msg.error.code ?? 0));
      else p.resolve(msg.result);
    } else if (msg.method) {
      for (const h of this.handlers) {
        try { h(msg.method, msg.params); } catch (e) { this.log(`erro num handler de ${msg.method}: ${e}`); }
      }
    }
  }

  /** Envia um pedido e espera a resposta (rejeita com RpcError se o servidor devolver erro). */
  async request<T = any>(method: string, params: Record<string, unknown> = {}, timeoutMs = 0): Promise<T> {
    await this.ensureStarted();
    const id = this.nextId++;
    return new Promise<T>((resolve, reject) => {
      let timer: NodeJS.Timeout | undefined;
      const done = <A extends unknown[]>(fn: (...a: A) => void) => (...a: A) => { if (timer) clearTimeout(timer); fn(...a); };
      this.pending.set(id, { resolve: done(resolve), reject: done(reject), method });
      if (timeoutMs > 0) timer = setTimeout(() => { this.pending.delete(id); reject(new RpcError(`Tempo esgotado em ${method}`)); }, timeoutMs);
      try {
        this.proc!.stdin!.write(JSON.stringify({ id, method, params }) + '\n');
      } catch (e) {
        this.pending.delete(id);
        reject(new RpcError(`Falha ao enviar ${method}: ${e}`));
      }
    });
  }

  async stop(): Promise<void> {
    const p = this.proc;
    if (!p) return;
    try { p.stdin?.write(JSON.stringify({ method: 'shutdown' }) + '\n'); } catch { /* ignore */ }
    await new Promise<void>((resolve) => {
      const t = setTimeout(() => { try { p.kill(); } catch { /* ignore */ } resolve(); }, 3000);
      p.once('exit', () => { clearTimeout(t); resolve(); });
    });
    this.proc = undefined;
  }
}
