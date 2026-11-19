import { JobDto } from './model';

/** Filtro de jobs pelo nome: texto = contém (sem maiúsculas); `*` = curinga; `/expressão/` = regex. Casa com `OWNER.NOME`. */
export function jobMatcher(text: string): (j: JobDto) => boolean {
  const t = text.trim();
  if (!t) return () => true;
  const esc = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  let re: RegExp;
  try {
    if (t.length >= 2 && t.startsWith('/') && t.endsWith('/')) re = new RegExp(t.slice(1, -1), 'i');
    else if (t.includes('*')) re = new RegExp(t.split('*').map(esc).join('.*'), 'i');
    else re = new RegExp(esc(t), 'i');
  } catch {
    re = new RegExp(esc(t), 'i'); // regex inválida: trata como texto
  }
  return (j) => re.test(`${j.owner}.${j.name}`);
}
