/** Utilitários de texto SQL (porte do SqlTokens do Ora Studio) usados pelo "ir para a definição". */

export interface IdentRef {
  start: number;
  end: number;
  qualifier?: string;
  name: string;
}

const isPart = (c: string) => /[A-Za-z0-9_$#À-ɏ]/.test(c);

export function norm(raw: string): string {
  return raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"') ? raw.slice(1, -1) : raw.toUpperCase();
}

function wordBounds(line: string, pos: number): [number, number] | undefined {
  if (pos < 0 || pos >= line.length) return undefined;
  const before = (line.slice(0, pos).match(/"/g) ?? []).length;
  if (line[pos] === '"' || before % 2 === 1) {
    const open = line[pos] === '"' ? (before % 2 === 0 ? pos : line.lastIndexOf('"', pos - 1)) : line.lastIndexOf('"', pos);
    if (open < 0) return undefined;
    const close = line.indexOf('"', open + 1);
    return close < 0 ? undefined : [open, close + 1];
  }
  if (!isPart(line[pos])) return undefined;
  let s = pos;
  let e = pos + 1;
  while (s > 0 && isPart(line[s - 1])) s--;
  while (e < line.length && isPart(line[e])) e++;
  return [s, e];
}

/** Identificador na coluna [col] de [line] (com `qualificador.` imediatamente antes, se existir). */
export function identifierAt(line: string, col: number): IdentRef | undefined {
  if (!line) return undefined;
  let c = Math.max(0, Math.min(col, line.length));
  if (c === line.length || !(isPart(line[c]) || line[c] === '"')) c--; // cursor logo depois da palavra
  if (c < 0) return undefined;
  const b = wordBounds(line, c);
  if (!b) return undefined;
  const raw = line.slice(b[0], b[1]);
  if (/^\d/.test(raw)) return undefined;
  let qualifier: string | undefined;
  if (b[0] > 0 && line[b[0] - 1] === '.') {
    const qb = wordBounds(line, b[0] - 2);
    if (qb) qualifier = norm(line.slice(qb[0], qb[1]));
  }
  return { start: b[0], end: b[1], qualifier, name: norm(raw) };
}

/** true se a posição [col] da linha está dentro de um comentário `--` ou de uma string '...' (não olha para comentários de bloco multi-linha). */
export function inLineCommentOrString(line: string, col: number): boolean {
  let inStr = false;
  for (let i = 0; i < Math.min(col, line.length); i++) {
    const c = line[i];
    if (inStr) {
      if (c === "'") {
        if (line[i + 1] === "'") i++;
        else inStr = false;
      }
    } else if (c === "'") inStr = true;
    else if (c === '-' && line[i + 1] === '-') return true;
    else if (c === '"') {
      const close = line.indexOf('"', i + 1);
      if (close < 0 || close >= col) return false; // dentro de identificador entre aspas: é um identificador
      i = close;
    }
  }
  return inStr;
}

/** Palavras reservadas que nunca são objetos (evita pedidos inúteis ao servidor). */
export const SQL_WORDS = new Set([
  'SELECT', 'FROM', 'WHERE', 'AND', 'OR', 'NOT', 'NULL', 'AS', 'IN', 'IS', 'ON', 'BY', 'GROUP', 'ORDER', 'HAVING', 'UNION', 'ALL',
  'DISTINCT', 'JOIN', 'INNER', 'LEFT', 'RIGHT', 'FULL', 'OUTER', 'CROSS', 'NATURAL', 'USING', 'CASE', 'WHEN', 'THEN', 'ELSE', 'END',
  'INSERT', 'INTO', 'VALUES', 'UPDATE', 'SET', 'DELETE', 'MERGE', 'CREATE', 'ALTER', 'DROP', 'TABLE', 'VIEW', 'INDEX', 'SEQUENCE',
  'TRIGGER', 'PROCEDURE', 'FUNCTION', 'PACKAGE', 'BODY', 'TYPE', 'REPLACE', 'BEGIN', 'DECLARE', 'EXCEPTION', 'RETURN', 'IF', 'ELSIF',
  'LOOP', 'WHILE', 'FOR', 'EXIT', 'CURSOR', 'OPEN', 'CLOSE', 'FETCH', 'COMMIT', 'ROLLBACK', 'SAVEPOINT', 'GRANT', 'REVOKE',
  'BETWEEN', 'LIKE', 'EXISTS', 'ANY', 'SOME', 'WITH', 'CONNECT', 'START', 'PRIOR', 'LEVEL', 'ROWNUM', 'ROWID', 'SYSDATE', 'DUAL',
  'ASC', 'DESC', 'OVER', 'PARTITION', 'PRIMARY', 'KEY', 'FOREIGN', 'REFERENCES', 'CONSTRAINT', 'UNIQUE', 'CHECK', 'DEFAULT',
  'VARCHAR2', 'NUMBER', 'DATE', 'TIMESTAMP', 'CLOB', 'BLOB', 'INTEGER', 'MATERIALIZED', 'OF', 'TO',
]);
