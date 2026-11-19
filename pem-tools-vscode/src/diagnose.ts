/** Diagnóstico de falhas conhecidas de build, a partir do texto que o Maven escreveu. */

export interface Diagnosis {
  id: 'git-commit-id-missing-object';
  /** hash do objeto git que o plugin não encontrou */
  hash?: string;
  summary: string;
}

/**
 * git-commit-id-plugin (2.2.6) com um JGit antigo não lê os packs `loose-*.pack` que o `git maintenance` cria
 * (multi-pack-index): falha com «Missing tree <hash>» mesmo com o objeto presente no repositório.
 */
export function diagnoseBuild(output: string): Diagnosis | undefined {
  if (!/git-commit-id/i.test(output)) return undefined;
  const m = /Missing (?:tree|commit|blob|object)\s+([0-9a-f]{7,40})/i.exec(output);
  if (!m) return undefined;
  return {
    id: 'git-commit-id-missing-object',
    hash: m[1].toLowerCase(),
    summary: `O git-commit-id-plugin não consegue ler o repositório git (Missing tree ${m[1].slice(0, 10)}…). ` +
      'Costuma acontecer com packs «loose-*» criados pelo git maintenance; o objeto existe, é o JGit antigo do plugin que não o lê.',
  };
}

export const GIT_COMMIT_ID_SKIP = '-Dmaven.gitcommitid.skip=true';

/** Junta [flag] aos parâmetros de «todos os módulos» (sem repetir). */
export function withGlobalArg(args: Record<string, string>, flag: string): Record<string, string> {
  const cur = (args['*'] ?? '').trim();
  if (cur.split(/\s+/).includes(flag)) return { ...args };
  return { ...args, '*': (cur ? cur + ' ' : '') + flag };
}
