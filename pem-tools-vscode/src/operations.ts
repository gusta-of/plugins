import { ChildProcess, execFile, spawn } from 'node:child_process';
import * as fs from 'node:fs';
import * as path from 'node:path';
import * as os from 'node:os';
import * as M from './modules';
import { TaskRunner, Steps, Log, isWindows, javaEnv, killTree } from './runner';

export interface Paths {
  workspace: string;
  domain: string;
}

export interface BuildOptions {
  profile: string;
  skipTests: boolean;
  localMock: boolean;
  threads: string;
}

const LOCAL_MOCK_MODULE = 'pem-common-domain';
const LOCAL_MOCK_PROFILE = 'localMock';

// ---------------------------------------------------------------- helpers de ficheiros ----

export function rmrf(p: string): void {
  fs.rmSync(p, { recursive: true, force: true });
}

/** Apaga o conteúdo de [dir] (mantém a pasta), poupando os nomes em [keep]. */
export function clearDir(dir: string, keep: Set<string> = new Set()): void {
  if (!fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) return;
  for (const name of fs.readdirSync(dir)) if (!keep.has(name)) rmrf(path.join(dir, name));
}

/** Apaga todas as pastas target/ (ignora node_modules e .git). Devolve os caminhos apagados. */
export function deleteTargets(root: string, onRemove: (p: string) => void = () => {}): string[] {
  const removed: string[] = [];
  const skip = new Set(['node_modules', '.git']);
  const walk = (dir: string) => {
    let entries: fs.Dirent[];
    try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return; }
    for (const e of entries) {
      if (!e.isDirectory() || skip.has(e.name)) continue;
      const full = path.join(dir, e.name);
      if (e.name === 'target') {
        onRemove(full);
        rmrf(full);
        removed.push(full);
      } else walk(full);
    }
  };
  walk(root);
  return removed;
}

/** O mais recente `<modulo>-*.<ext>` em target/, ignorando sources/javadoc/tests. */
export function findArtifact(target: string, module: string, ext: string): string {
  const files = fs.existsSync(target)
    ? fs.readdirSync(target).filter((n) => n.startsWith(`${module}-`) && n.endsWith(`.${ext}`) && !['sources', 'javadoc', 'tests'].some((x) => n.includes(x)))
    : [];
  if (files.length === 0) throw new Error(`${module}: nenhum .${ext} em ${target} (corre o build primeiro)`);
  files.sort((a, b) => fs.statSync(path.join(target, b)).mtimeMs - fs.statSync(path.join(target, a)).mtimeMs);
  return path.join(target, files[0]);
}

export function copyInto(src: string, dstDir: string): void {
  fs.mkdirSync(dstDir, { recursive: true });
  fs.copyFileSync(src, path.join(dstDir, path.basename(src)));
}

export function m2PemDir(): string {
  // PEMWL_M2_PEM_DIR permite apontar para outra pasta (usado nos testes, para nunca tocar no ~/.m2 real)
  return process.env.PEMWL_M2_PEM_DIR || path.join(os.homedir(), '.m2', 'repository', 'pt', 'min-saude', 'spms', 'modules', 'pem');
}

// ---------------------------------------------------------------- processos ----

export interface ProcInfo {
  pid: number;
  ppid: number;
  cmd: string;
}

/** Lista de processos (Linux/macOS: ps; Windows: PowerShell/CIM). */
export function listProcesses(): Promise<ProcInfo[]> {
  return new Promise((resolve) => {
    if (isWindows) {
      execFile('powershell', ['-NoProfile', '-Command', 'Get-CimInstance Win32_Process | Select-Object ProcessId,ParentProcessId,CommandLine | ConvertTo-Json -Compress'],
        { maxBuffer: 64 * 1024 * 1024, windowsHide: true }, (err, stdout) => {
          if (err) return resolve([]);
          try {
            const raw = JSON.parse(stdout);
            const arr = Array.isArray(raw) ? raw : [raw];
            resolve(arr.map((x: { ProcessId: number; ParentProcessId: number; CommandLine?: string }) => ({ pid: x.ProcessId, ppid: x.ParentProcessId, cmd: x.CommandLine ?? '' })));
          } catch { resolve([]); }
        });
    } else {
      execFile('ps', ['-eo', 'pid=,ppid=,args='], { maxBuffer: 64 * 1024 * 1024 }, (err, stdout) => {
        if (err) return resolve([]);
        resolve(parsePs(stdout));
      });
    }
  });
}

export function parsePs(out: string): ProcInfo[] {
  const res: ProcInfo[] = [];
  for (const line of out.split('\n')) {
    const m = /^\s*(\d+)\s+(\d+)\s+(.*)$/.exec(line);
    if (m) res.push({ pid: Number(m[1]), ppid: Number(m[2]), cmd: m[3] });
  }
  return res;
}

/** Processos java dos módulos: linha de comando com `java` e `<modulo>...*.jar` (como o kill-module-jars.sh). */
export function matchModuleJars(procs: ProcInfo[], selfPid = process.pid): ProcInfo[] {
  return procs.filter((p) => p.pid !== selfPid && p.cmd.includes('java') &&
    M.backendDirs.some((d) => new RegExp(`${escapeRe(d)}.*\\.jar`).test(p.cmd)));
}

/** Processos do WebLogic deste domínio: `weblogic.Server` + caminho do domínio. */
export function matchWebLogic(procs: ProcInfo[], domain: string, selfPid = process.pid): ProcInfo[] {
  const norm = (s: string) => s.toLowerCase().replace(/\\/g, '/');
  const needle = norm(domain);
  return procs.filter((p) => p.pid !== selfPid && norm(p.cmd).includes('weblogic.server') && norm(p.cmd).includes(needle));
}

/** Os próprios processos mais todos os descendentes. */
export function withDescendants(roots: ProcInfo[], all: ProcInfo[]): ProcInfo[] {
  const out = new Map<number, ProcInfo>();
  const visit = (p: ProcInfo) => {
    if (out.has(p.pid)) return;
    out.set(p.pid, p);
    all.filter((c) => c.ppid === p.pid).forEach(visit);
  };
  roots.forEach(visit);
  return [...out.values()];
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

function isAlive(pid: number): boolean {
  try { process.kill(pid, 0); return true; } catch { return false; }
}

/** Termina os processos (SIGTERM / taskkill) e força quem não sair em [graceMs]. */
export async function terminate(procs: ProcInfo[], log: Log, graceMs = 10000): Promise<void> {
  for (const p of procs) {
    log(`Terminando PID ${p.pid}`);
    try {
      if (isWindows) spawn('taskkill', ['/PID', String(p.pid), '/F'], { windowsHide: true });
      else process.kill(p.pid, 'SIGTERM');
    } catch { /* já terminou */ }
  }
  const end = Date.now() + graceMs;
  while (Date.now() < end && procs.some((p) => isAlive(p.pid))) await sleep(250);
  for (const p of procs.filter((x) => isAlive(x.pid))) {
    log(`Forçando PID ${p.pid}`, 'warn');
    try { process.kill(p.pid, 'SIGKILL'); } catch { /* ignore */ }
  }
}

// ---------------------------------------------------------------- operações ----

/** Equivalentes dos scripts de .vscode/scripts. Dois executores: um para tarefas, outro (independente) para o WebLogic. */
export class Operations {
  private wl?: ChildProcess;
  onWebLogicExit: () => void = () => {};
  /** arranque do WebLogic (para acompanhar os artefactos) e cada linha que ele escreve */
  onWebLogicStart: (p: Paths) => void = () => {};
  onWebLogicLine: (line: string) => void = () => {};

  constructor(readonly runner: TaskRunner, readonly wlRunner: TaskRunner, private readonly wlLog: Log) {}

  get weblogicRunning(): boolean {
    return !!this.wl && this.wl.exitCode === null && !this.wl.killed;
  }

  // ---- limpeza ----

  cleanTargets(p: Paths) {
    return this.runner.run('Limpar pastas target/', 1, async (s) => {
      await s.step(`Apagar target/ em ${p.workspace}`, () => { deleteTargets(p.workspace, (x) => this.runner.log(`Removendo: ${x}`)); });
    });
  }

  clearClasspath(p: Paths, m2Dir = m2PemDir()) {
    return this.runner.run('Limpar cache / classpath', 7, async (s) => {
      const d = p.domain;
      if (!fs.existsSync(d)) {
        await s.step('Validar domínio', () => { throw new Error(`domínio não encontrado em ${d}`); });
        return;
      }
      await s.step('Staging explodido (_WL_user, _WL_internal)', () => {
        clearDir(path.join(d, 'servers/AdminServer/tmp/_WL_user'));
        clearDir(path.join(d, 'servers/AdminServer/tmp/_WL_internal'));
      });
      await s.step('Cache interno do servidor', () => clearDir(path.join(d, 'servers/AdminServer/cache')));
      await s.step(`Autodeploy (mantém ${[...M.autodeployKeep].join(', ')})`, () => {
        const ad = path.join(d, 'autodeploy');
        if (!fs.existsSync(ad)) return;
        for (const n of fs.readdirSync(ad)) if (!n.startsWith('.') && !M.autodeployKeep.has(n)) rmrf(path.join(ad, n));
      });
      await s.step('liferay/deploy', () => clearDir(path.join(d, 'liferay/deploy')));
      await s.step('liferay/data/osgi', () => clearDir(path.join(d, 'liferay/data/osgi')));
      await s.step(`Apagar target/ em ${p.workspace}`, () => { deleteTargets(p.workspace, (x) => this.runner.log(`Removendo: ${x}`)); });
      await s.step(`~/.m2/.../modules/pem (mantém ${[...M.m2Keep].join(', ')})`, () => clearDir(m2Dir, M.m2Keep));
      this.runner.log('Dados não tocados: liferay/data/hsql, jackrabbit, lucene.');
    });
  }

  killModuleJars() {
    return this.runner.run('Encerrar processos java dos módulos', 1, async (s) => {
      await s.step('Procurar processos', async () => {
        const all = await listProcesses();
        const found = matchModuleJars(all);
        if (found.length === 0) this.runner.log('Nenhum processo dos módulos em execução.');
        found.forEach((f) => this.runner.log(`Encerrando processo ${f.pid}: ${f.cmd.slice(0, 160)}`));
        await terminate(found, this.runner.log, 2000);
      });
    });
  }

  // ---- build ----

  buildCommand(step: M.BuildStep, o: BuildOptions): string {
    const profiles = o.profile.split(',').map((x) => x.trim()).filter(Boolean);
    if (step.dir === LOCAL_MOCK_MODULE && o.localMock && !profiles.includes(LOCAL_MOCK_PROFILE)) profiles.push(LOCAL_MOCK_PROFILE);
    const prof = step.withProfile && profiles.length ? ` -P${profiles.join(',')}` : '';
    const skip = step.withProfile && o.skipTests ? ' -Dmaven.test.skip=true' : '';
    return `mvn ${step.goals}${prof}${skip}`;
  }

  private async runBuild(s: Steps, p: Paths, step: M.BuildStep, o: BuildOptions) {
    await s.shell(step.label, path.join(p.workspace, step.dir), this.buildCommand(step, o));
  }

  buildBackend(p: Paths, o: BuildOptions) {
    const steps = M.backendBuild();
    return this.runner.run('Build Backend', steps.length, async (s) => { for (const st of steps) await this.runBuild(s, p, st, o); });
  }

  buildFrontend(p: Paths, o: BuildOptions, fast: boolean) {
    const steps = M.frontendBuild(fast, o.threads);
    return this.runner.run(fast ? `Build Frontend (rápido -T ${o.threads})` : 'Build Frontend', steps.length,
      async (s) => { for (const st of steps) await this.runBuild(s, p, st, o); });
  }

  buildModules(p: Paths, entries: M.ModuleEntry[], o: BuildOptions) {
    return this.runner.run(entries.length === 1 ? `Build ${entries[0].name}` : `Build de ${entries.length} módulos`, entries.length, async (s) => {
      for (const e of entries) {
        const step = e.kind === 'backend' ? M.backendBuild().find((x) => x.label === e.name)! : M.frontendModuleBuild(e.name);
        await this.runBuild(s, p, step, o);
      }
    });
  }

  // ---- deploy ----

  private async deployBackendOne(s: Steps, p: Paths, m: string) {
    await s.step(`Deploy ${m}`, () => {
      const target = path.join(p.workspace, m, 'target');
      if (m === M.JAR_MODULE) copyInto(findArtifact(target, m, 'jar'), path.join(p.domain, 'lib'));
      else {
        const war = findArtifact(target, m, 'war');
        rmrf(path.join(p.domain, 'servers/AdminServer/tmp/_WL_user', m));
        copyInto(war, path.join(p.domain, 'servers/AdminServer/upload'));
      }
    });
  }

  private async deployFrontendOne(s: Steps, p: Paths, m: string) {
    await s.step(`Deploy ${m}`, () => {
      const sub = M.frontendDeploy[m];
      const war = findArtifact(path.join(p.workspace, 'spms-plugins', sub, m, 'target'), m, 'war');
      rmrf(path.join(p.domain, 'autodeploy', m));
      copyInto(war, path.join(p.domain, 'liferay/deploy'));
    });
  }

  deployBackend(p: Paths, modules: string[]) {
    return this.runner.run('Deploy Backend', modules.length, async (s) => { for (const m of modules) await this.deployBackendOne(s, p, m); });
  }

  deployFrontend(p: Paths, modules: string[]) {
    return this.runner.run('Deploy Frontend', modules.length, async (s) => { for (const m of modules) await this.deployFrontendOne(s, p, m); });
  }

  deployModules(p: Paths, entries: M.ModuleEntry[]) {
    return this.runner.run(entries.length === 1 ? `Deploy ${entries[0].name}` : `Deploy de ${entries.length} módulos`, entries.length, async (s) => {
      for (const e of entries) await (e.kind === 'backend' ? this.deployBackendOne(s, p, e.name) : this.deployFrontendOne(s, p, e.name));
    });
  }

  // ---- WebLogic ----

  private scriptPath(p: Paths, name: string): string {
    return path.join(p.domain, 'bin', `${name}.${isWindows ? 'cmd' : 'sh'}`);
  }

  startWebLogic(p: Paths): boolean {
    if (this.weblogicRunning) {
      this.wlLog('WebLogic já está a correr (iniciado por esta extensão).', 'err');
      return false;
    }
    const script = this.scriptPath(p, 'startWebLogic');
    if (!fs.existsSync(script)) {
      this.wlLog(`Não encontrado: ${script}`, 'err');
      return false;
    }
    const bin = path.dirname(script);
    const env = javaEnv();
    // Windows: pelo NOME do ficheiro, com a pasta bin como diretoria de trabalho (sem aspas dentro do cmd /c)
    const child = isWindows
      ? spawn('cmd', ['/d', '/s', '/c', path.basename(script)], { cwd: bin, env, windowsVerbatimArguments: true, windowsHide: true })
      : spawn('bash', [script], { cwd: p.domain, env, detached: true });
    this.wl = child;
    this.onWebLogicStart(p);
    const out = (k: 'info' | 'err') => (c: Buffer) => {
      const text = c.toString('utf8').replace(/\r?\n$/, '');
      this.wlLog(text, k);
      for (const l of text.split(/\r?\n/)) this.onWebLogicLine(l);
    };
    child.stdout?.on('data', out('info'));
    child.stderr?.on('data', out('err'));
    child.on('error', (e) => this.wlLog(`Falha a iniciar o WebLogic: ${e.message}`, 'err'));
    child.on('close', (code) => {
      this.wlLog(`[extensão] processo do startWebLogic terminou (exit ${code}).`, 'sys');
      this.wl = undefined;
      this.onWebLogicExit();
    });
    return true;
  }

  /** Stop: tenta o script de paragem (máx. 60s) e depois FORÇA a terminação do que restar. */
  stopWebLogic(p: Paths) {
    return this.wlRunner.run('Stop WebLogic', 2, async (s) => {
      const script = this.scriptPath(p, 'stopWebLogic');
      const cmd = isWindows ? path.basename(script) : `bash '${script}'`;
      await s.shell(`stopWebLogic (gracioso)`, path.dirname(script), cmd, { timeoutSec: 60, allowFail: true });
      await s.step('Forçar paragem dos processos WebLogic', () => this.forceKill(p.domain));
    });
  }

  killWebLogic(p: Paths) {
    return this.wlRunner.run('Kill WebLogic', 1, async (s) => {
      await s.step('Forçar paragem dos processos WebLogic', () => this.forceKill(p.domain));
    });
  }

  private async forceKill(domain: string): Promise<void> {
    const hadHandle = !!this.wl;
    if (this.wl) killTree(this.wl);
    const all = await listProcesses();
    const roots = matchWebLogic(all, domain);
    if (roots.length === 0) {
      if (hadHandle) this.wlLog('[extensão] WebLogic terminado.', 'sys');
      else this.wlRunner.log(`Nenhum processo WebLogic do domínio ${domain} em execução.`);
      return;
    }
    await terminate(withDescendants(roots, all), this.wlRunner.log);
    this.wlLog('[extensão] WebLogic terminado.', 'sys');
  }
}

function escapeRe(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
