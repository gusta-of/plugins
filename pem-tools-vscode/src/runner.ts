import { ChildProcess, spawn } from 'node:child_process';
import * as os from 'node:os';
import * as fs from 'node:fs';

export type LogKind = 'info' | 'ok' | 'err' | 'sys' | 'warn';
export type Log = (text: string, kind?: LogKind) => void;

export const isWindows = process.platform === 'win32';

const DEFAULT_JAVA_HOME = '/opt/jdk1.8.0_202';
const DEFAULT_MAVEN_BIN = '/opt/apache-maven-3.9.9/bin';

/** O VS Code lançado pelo desktop (Linux) nem sempre herda o .bashrc: garante JAVA_HOME e mvn. */
export function javaEnv(base: NodeJS.ProcessEnv = process.env): NodeJS.ProcessEnv {
  const env: NodeJS.ProcessEnv = { ...base };
  if (isWindows) return env;
  const javaHome = base.JAVA_HOME?.trim() || (fs.existsSync(DEFAULT_JAVA_HOME) ? DEFAULT_JAVA_HOME : undefined);
  const extra: string[] = [];
  if (javaHome) {
    env.JAVA_HOME = javaHome;
    extra.push(`${javaHome}/bin`);
  }
  if (fs.existsSync(DEFAULT_MAVEN_BIN)) extra.push(DEFAULT_MAVEN_BIN);
  env.PATH = [...extra, base.PATH ?? ''].filter(Boolean).join(':');
  return env;
}

/** Linux/macOS: bash -lc; Windows: cmd /c (o mvn resolve para mvn.cmd no PATH). */
export function shellSpawn(command: string, cwd: string): ChildProcess {
  const env = javaEnv();
  if (isWindows) return spawn('cmd', ['/d', '/s', '/c', command], { cwd, env, windowsVerbatimArguments: true, windowsHide: true });
  return spawn('bash', ['-lc', command], { cwd, env, detached: true });
}

/** Termina um processo e os seus filhos (o stop gracioso do npm/mvn não chega a quem lança JVMs). */
export function killTree(child: ChildProcess): void {
  if (child.pid === undefined) return;
  try {
    if (isWindows) spawn('taskkill', ['/PID', String(child.pid), '/T', '/F'], { windowsHide: true });
    else process.kill(-child.pid, 'SIGTERM');
  } catch {
    try { child.kill('SIGKILL'); } catch { /* já terminou */ }
  }
}

export interface ProgressEvent {
  done: number;
  total: number;
  label: string;
  /** true quando a tarefa terminou (a barra mostra "concluído" e esconde-se depois). */
  finished: boolean;
}

/** Executa sequências de passos; nunca para à primeira falha (como o build-backend.sh) e imprime o resumo. */
export class TaskRunner {
  busy = false;
  private cancelled = false;
  private current?: ChildProcess;
  onProgress: (e: ProgressEvent) => void = () => {};
  onFinished: () => void = () => {};

  constructor(readonly log: Log) {}

  cancel(): void {
    this.cancelled = true;
    if (this.current) killTree(this.current);
  }

  get isCancelled(): boolean {
    return this.cancelled;
  }

  /** Corre [body]; devolve false se já havia uma tarefa em curso. */
  async run(title: string, total: number, body: (s: Steps) => Promise<void>): Promise<boolean> {
    if (this.busy) {
      this.log('Já existe uma tarefa em curso; cancela-a primeiro.', 'err');
      return false;
    }
    this.busy = true;
    this.cancelled = false;
    const steps = new Steps(this, total);
    this.log(`=== ${title} ===`, 'sys');
    this.onProgress({ done: 0, total, label: title, finished: false });
    try {
      await body(steps);
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      this.log(`ERRO: ${msg}`, 'err');
      steps.failed.push(msg);
    } finally {
      this.busy = false;
      this.current = undefined;
    }
    if (this.cancelled) this.log('Cancelado.', 'err');
    else if (steps.failed.length === 0) this.log(`=== ${title}: concluído com sucesso ===`, 'sys');
    else {
      this.log(`=== ${title}: concluído com falhas ===`, 'err');
      steps.failed.forEach((f) => this.log(` - ${f}`, 'err'));
    }
    this.onProgress({ done: this.cancelled || steps.failed.length ? steps.done : total, total, label: '', finished: true });
    this.onFinished();
    return true;
  }

  /** Corre um comando de shell; resolve com o exit code (-1 em timeout). */
  exec(command: string, cwd: string, timeoutSec = 0): Promise<number> {
    return new Promise((resolve) => {
      const child = shellSpawn(command, cwd);
      this.current = child;
      let timedOut = false;
      const timer = timeoutSec > 0 ? setTimeout(() => {
        timedOut = true;
        this.log(`Timeout de ${timeoutSec}s: a terminar o comando.`, 'err');
        killTree(child);
      }, timeoutSec * 1000) : undefined;
      const out = (chunk: Buffer) => this.log(stripAnsi(chunk.toString('utf8')).replace(/\r?\n$/, ''));
      child.stdout?.on('data', out);
      child.stderr?.on('data', (c: Buffer) => this.log(stripAnsi(c.toString('utf8')).replace(/\r?\n$/, ''), 'err'));
      child.on('error', (e) => { this.log(`Falha a iniciar: ${e.message}`, 'err'); if (timer) clearTimeout(timer); resolve(-2); });
      child.on('close', (code) => { if (timer) clearTimeout(timer); resolve(timedOut ? -1 : (code ?? -1)); });
    });
  }
}

export class Steps {
  readonly failed: string[] = [];
  done = 0;

  constructor(private readonly runner: TaskRunner, private readonly total: number) {}

  private finished(label: string): void {
    this.done++;
    this.runner.onProgress({ done: this.done, total: this.total, label, finished: false });
  }

  /** Passo em TypeScript (cópias, apagar pastas, ...). */
  async step(label: string, action: () => Promise<void> | void): Promise<void> {
    if (this.runner.isCancelled) return;
    this.runner.log(`==> ${label}`);
    try {
      await action();
      this.runner.log(`OK: ${label}`, 'ok');
    } catch (e) {
      this.runner.log(`FAILED: ${label} (${e instanceof Error ? e.message : e})`, 'err');
      this.failed.push(label);
    }
    this.finished(label);
  }

  /** Comando de shell numa pasta. */
  async shell(label: string, cwd: string, command: string, opts: { timeoutSec?: number; allowFail?: boolean } = {}): Promise<void> {
    if (this.runner.isCancelled) return;
    this.runner.log(`==> ${label}`);
    if (!fs.existsSync(cwd) || !fs.statSync(cwd).isDirectory()) {
      this.runner.log(`FAILED: ${label} (pasta inexistente: ${cwd})`, 'err');
      this.failed.push(label);
      this.finished(label);
      return;
    }
    const code = await this.runner.exec(command, cwd, opts.timeoutSec ?? 0);
    if (code === 0) this.runner.log(`OK: ${label}`, 'ok');
    else if (!this.runner.isCancelled) {
      this.runner.log(`${opts.allowFail ? 'AVISO' : 'FAILED'}: ${label} (exit code ${code})`, 'err');
      if (!opts.allowFail) this.failed.push(`${label} (${code})`);
    }
    if (!this.runner.isCancelled) this.finished(label);
  }
}

export function stripAnsi(s: string): string {
  // eslint-disable-next-line no-control-regex
  return s.replace(/\u001b\[[0-9;]*[A-Za-z]/g, '');
}

export function homeDir(): string {
  return os.homedir();
}
