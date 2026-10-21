import { execFile } from 'node:child_process';
import * as fs from 'node:fs';
import * as path from 'node:path';

/** Versão principal do Java a partir do output de `java -version` (ex.: `openjdk version "17.0.9"` → 17; `"1.8.0_202"` → 8). */
export function javaMajor(output: string): number | undefined {
  const m = /version "(\d+)(?:\.(\d+))?/.exec(output);
  if (!m) return undefined;
  const first = Number(m[1]);
  return first === 1 ? Number(m[2]) : first;
}

function javaVersion(bin: string): Promise<number | undefined> {
  return new Promise((resolve) => {
    execFile(bin, ['-version'], { timeout: 8000 }, (_err, stdout, stderr) => resolve(javaMajor(`${stdout}\n${stderr}`)));
  });
}

const exe = process.platform === 'win32' ? 'java.exe' : 'java';

/** Locais habituais onde procurar JDKs. */
export function candidateJavaBins(configured: string | undefined, env: NodeJS.ProcessEnv = process.env): string[] {
  const out: string[] = [];
  const add = (p?: string) => { if (p && !out.includes(p)) out.push(p); };
  if (configured?.trim()) add(configured.trim());
  for (const v of [env.ORASTUDIO_JAVA_HOME, env.JAVA_HOME]) if (v) add(path.join(v, 'bin', exe));
  const roots = process.platform === 'win32'
    ? ['C:\\Program Files\\Java', 'C:\\Program Files\\Eclipse Adoptium', 'C:\\Program Files\\Microsoft', 'C:\\Program Files\\Zulu']
    : ['/usr/lib/jvm', '/opt', '/usr/local', '/Library/Java/JavaVirtualMachines'];
  for (const r of roots) {
    try {
      for (const d of fs.readdirSync(r).sort().reverse()) {
        if (/jdk|java|jre|temurin|zulu|corretto|openjdk/i.test(d)) {
          add(path.join(r, d, 'bin', exe));
          add(path.join(r, d, 'Contents', 'Home', 'bin', exe));
        }
      }
    } catch { /* pasta inexistente */ }
  }
  add('java'); // PATH
  return out;
}

/** Primeiro java 17+ encontrado; erro com instruções se não houver nenhum. */
export async function findJava(configured?: string, minMajor = 17): Promise<string> {
  const tried: string[] = [];
  for (const bin of candidateJavaBins(configured)) {
    if (bin !== 'java' && !fs.existsSync(bin)) continue;
    const major = await javaVersion(bin);
    tried.push(`${bin} → ${major ?? '?'}`);
    if (major !== undefined && major >= minMajor) return bin;
  }
  throw new Error(`Não encontrei um Java ${minMinor(minMajor)} ou superior. Defina "oraStudio.javaPath" nas definições.\nTentei: ${tried.join('; ') || '(nenhum)'}`);
}

const minMinor = (n: number) => String(n);
