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
