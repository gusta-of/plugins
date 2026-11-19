import { pomDependencies, sortByDependencies } from './buildOrder';

/** Listas de módulos do PEM (iguais às dos scripts .vscode/scripts e ao plugin do IntelliJ). */

export interface BuildStep {
  label: string;
  /** Pasta relativa ao workspace. */
  dir: string;
  goals: string;
  withProfile: boolean;
}

export type Kind = 'backend' | 'frontend';
export interface ModuleEntry {
  name: string;
  kind: Kind;
}

const backendGroups = ['pem-common', 'pem-shared', 'pem-med', 'pem-dm', 'pem-crd', 'pem-fh', 'pem-mcdt', 'pem-ocl', 'esp-mcdt'];

/** Ordem idêntica a build-backend.sh. */
export function backendBuild(): BuildStep[] {
  const out: BuildStep[] = [];
  const add = (label: string, dir: string, goals: string, withProfile = true) => out.push({ label, dir, goals, withProfile });
  add('pem-common-beans', 'pem-common-beans', 'clean install -rf :pem-common-beans');
  add('pem', 'pem', 'clean install -U -Dmaven.test.skip=true');
  add('spms-plugins-common', 'spms-plugins-common', 'clean install -rf :spms-plugins-common');
  for (const g of backendGroups) {
    add(`${g}-domain`, `${g}-domain`, `clean install -rf :${g}-domain war:manifest war:war`);
    add(`${g}-app`, `${g}-app`, `clean install -rf :${g}-app war:war`);
    if (g === 'pem-common') add('pem-common', 'pem-common', 'clean install -rf :pem-common');
  }
  add('pem-bkf-app', 'pem-bkf-app', 'clean install -rf :pem-bkf-app war:war');
  add('Test report backend', 'pem', 'allure:aggregate', false);
  return out;
}

/**
 * Passos do backend com os módulos ordenados pelas dependências dos pom.xml de [workspace]
 * (a ordem fixa de build-backend.sh estava errada, p.ex. para pem-common-app / spms-plugins-common).
 * Os dois primeiros passos (pem-common-beans e o reactor «pem») e o relatório final ficam nas pontas.
 */
export function backendBuildOrdered(workspace: string, onCycle: (rest: string[]) => void = () => {}): BuildStep[] {
  const fixed = backendBuild();
  const head = fixed.slice(0, 2);
  const tail = fixed.filter((s) => !s.withProfile);
  const middle = fixed.slice(2).filter((s) => s.withProfile);
  const order = sortByDependencies(middle.map((s) => s.dir), pomDependencies(workspace, middle.map((s) => s.dir)), onCycle);
  return [...head, ...order.map((d) => middle.find((s) => s.dir === d)!), ...tail];
}

/** Ordena a seleção do utilizador: backend pelas dependências, depois o frontend (ordem recebida). */
export function orderEntries(workspace: string, entries: ModuleEntry[]): ModuleEntry[] {
  const labels = backendBuildOrdered(workspace).map((s) => s.label);
  const rank = (e: ModuleEntry) => { const i = labels.indexOf(e.name); return i < 0 ? Number.MAX_SAFE_INTEGER : i; };
  return [...entries.filter((e) => e.kind === 'backend').sort((a, b) => rank(a) - rank(b)), ...entries.filter((e) => e.kind !== 'backend')];
}

export function frontendBuild(fast: boolean, threads = '1C'): BuildStep[] {
  return [
    { label: 'spms-plugins-common', dir: 'spms-plugins-common', goals: 'clean install -rf :spms-plugins-common', withProfile: true },
    { label: 'spms-plugins', dir: 'spms-plugins', goals: fast ? `clean install -T ${threads}` : 'clean install', withProfile: true },
  ];
}

export const JAR_MODULE = 'pem-common-beans';

/** Módulos de deploy do backend (WARs + o JAR do beans). */
export const backendDeploy: string[] = [
  'esp-mcdt-app', 'esp-mcdt-domain', 'pem-common-app', 'pem-common-domain', 'pem-crd-app', 'pem-crd-domain',
  'pem-dm-app', 'pem-dm-domain', 'pem-fh-app', 'pem-fh-domain', 'pem-mcdt-app', 'pem-mcdt-domain',
  'pem-med-app', 'pem-med-domain', 'pem-ocl-app', 'pem-ocl-domain', 'pem-shared-app', 'pem-shared-domain',
  'pem-bkf-app', JAR_MODULE,
];

/** módulo -> subpasta dentro de spms-plugins */
export const frontendDeploy: Record<string, string> = {
  'backoffice-portlet': 'portlets', 'backoffice-theme': 'themes', 'esp-mcdt-portlet': 'portlets',
  'header-portlet': 'portlets', 'integration-portlet': 'portlets', 'patient-portlet': 'portlets',
  'pem-base-theme': 'themes', 'pem-crd-portlet': 'portlets', 'pem-dm-portlet': 'portlets',
  'pem-fh-portlet': 'portlets', 'pem-hook': 'hooks', 'pem-lang-hook': 'hooks',
  'pem-mcdt-portlet': 'portlets', 'pem-med-portlet': 'portlets', 'pem-ocl-portlet': 'portlets',
  'portal-portlet': 'portlets', 'portal-theme': 'themes',
};

export function frontendModuleBuild(module: string): BuildStep {
  return { label: module, dir: `spms-plugins/${frontendDeploy[module]}/${module}`, goals: `clean install -rf :${module}`, withProfile: true };
}

export function buildEntries(): ModuleEntry[] {
  return [
    ...backendBuild().filter((s) => s.withProfile).map((s): ModuleEntry => ({ name: s.label, kind: 'backend' })),
    ...Object.keys(frontendDeploy).map((n): ModuleEntry => ({ name: n, kind: 'frontend' })),
  ];
}

export function deployEntries(): ModuleEntry[] {
  return [
    ...backendDeploy.map((n): ModuleEntry => ({ name: n, kind: 'backend' })),
    ...Object.keys(frontendDeploy).map((n): ModuleEntry => ({ name: n, kind: 'frontend' })),
  ];
}

/** Módulos usados por kill-module-jars.sh */
export const backendDirs = [
  'pem-common-beans', 'pem-common-rdata', 'pem-common-notifications', 'pem-common-domain', 'pem-common',
  'pem-common-app', 'pem-bkf-domain', 'pem-shared-domain', 'pem-med-domain', 'pem-dm-domain',
  'pem-crd-domain', 'pem-fh-domain', 'pem-bkf-app', 'pem-shared-app', 'pem-med-app', 'pem-dm-app',
  'pem-fh-app', 'pem-crd-app', 'pem-ocl-domain', 'pem-ocl-app', 'pem-mcdt-domain', 'pem-mcdt-app',
  'esp-mcdt-domain', 'esp-mcdt-app',
];

/** Nunca apagar do autodeploy (não são reconstruídos pelo build do PEM). */
export const autodeployKeep = new Set(['marketplace-portlet', 'pem-layouttpl']);
/** Nunca apagar de ~/.m2/.../modules/pem */
export const m2Keep = new Set(['fonts']);

export const threadOptions = ['1C', '0.5C', '2C', '1', '2', '4', '6', '8', '12', '16'];

/** Aceita N, NC ou N.NC (ex.: 4, 1C, 0.5C); qualquer outra coisa cai no padrão 1C. */
export function sanitizeThreads(raw: string): string {
  const t = (raw ?? '').trim();
  return /^\d+(\.\d+)?C?$/.test(t) ? t : '1C';
}

/** Chave dos parâmetros Maven que valem para todos os builds. */
export const ALL_MODULES = '*';

export type MavenArgs = Record<string, string>;

/** Limpa o que o utilizador escreveu: uma só linha, sem espaços nas pontas. */
export function cleanArg(s: unknown): string {
  return String(s ?? '').replace(/[\r\n]+/g, ' ').trim();
}

/** Parâmetros extra do `mvn` para um passo: os de «todos» e a seguir os do módulo. */
export function mavenArgsFor(label: string, args: MavenArgs | undefined): string {
  if (!args) return '';
  return [cleanArg(args[ALL_MODULES]), cleanArg(args[label])].filter(Boolean).join(' ');
}

export interface PomModule { name: string; dir: string; kind: Kind }

/** Módulos com pom.xml que têm build (backend e frontend), sem repetições, para o ecrã de parâmetros Maven. */
export function buildPomModules(): PomModule[] {
  const seen = new Set<string>();
  const out: PomModule[] = [];
  const add = (name: string, dir: string, kind: Kind) => { if (!seen.has(name)) { seen.add(name); out.push({ name, dir, kind }); } };
  backendBuild().forEach((s) => add(s.label, s.dir, 'backend'));
  frontendBuild(false).forEach((s) => add(s.label, s.dir, 'frontend'));
  Object.keys(frontendDeploy).forEach((n) => add(n, `spms-plugins/${frontendDeploy[n]}/${n}`, 'frontend'));
  return out;
}
