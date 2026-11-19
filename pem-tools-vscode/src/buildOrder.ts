import * as fs from 'node:fs';
import * as path from 'node:path';

const GROUP = 'pt.min-saude.spms.modules.pem';

/**
 * Ordem de build dos módulos do backend a partir das dependências REAIS dos pom.xml (e não de uma lista fixa):
 * um módulo só é construído depois de todos os módulos do workspace de que depende.
 */

/** pasta -> pastas (de [dirs]) de que o pom dessa pasta depende. Pastas sem pom.xml ficam sem dependências. */
export function pomDependencies(workspace: string, dirs: string[]): Map<string, Set<string>> {
  const known = new Set(dirs);
  const out = new Map<string, Set<string>>();
  for (const dir of dirs) {
    const deps = new Set<string>();
    out.set(dir, deps);
    let xml: string;
    try { xml = fs.readFileSync(path.join(workspace, dir, 'pom.xml'), 'utf8'); } catch { continue; }
    xml = xml.replace(/<!--[\s\S]*?-->/g, '');
    for (const m of xml.matchAll(/<dependency>([\s\S]*?)<\/dependency>/g)) {
      const g = /<groupId>\s*([^<\s]+)\s*<\/groupId>/.exec(m[1])?.[1];
      const a = /<artifactId>\s*([^<\s]+)\s*<\/artifactId>/.exec(m[1])?.[1];
      if (g === GROUP && a && a !== dir && known.has(a)) deps.add(a);
    }
  }
  return out;
}

/**
 * Ordenação topológica estável: em cada passo escolhe, pela ordem de [base], o primeiro módulo cujas
 * dependências já estão todas colocadas. Num ciclo (não devia haver) segue a ordem de [base] e chama [onCycle].
 */
export function sortByDependencies(base: string[], deps: Map<string, Set<string>>, onCycle: (rest: string[]) => void = () => {}): string[] {
  const remaining = [...base];
  const done: string[] = [];
  while (remaining.length) {
    const i = remaining.findIndex((m) => [...(deps.get(m) ?? [])].every((d) => !remaining.includes(d)));
    if (i < 0) { onCycle([...remaining]); done.push(...remaining); break; }
    done.push(...remaining.splice(i, 1));
  }
  return done;
}
