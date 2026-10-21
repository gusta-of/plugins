import { ObjRef } from './model';

/** URIs `orastudio:` dos documentos virtuais (DDL / código-fonte): orastudio:/<ligacao>/<OWNER>/<TIPO>/<NOME>.sql[?body=1] */
export const SCHEME = 'orastudio';

export interface DocRef {
  connId: string;
  ref: ObjRef;
  body: boolean;
}

const enc = encodeURIComponent;

export function buildDocPath(connId: string, ref: ObjRef, body = false): string {
  return `/${enc(connId)}/${enc(ref.owner)}/${enc(ref.type)}/${enc(ref.name)}.sql${body ? '?body=1' : ''}`;
}

/** Interpreta o path (e a query) de um URI orastudio:. */
export function parseDocPath(pathname: string, query = ''): DocRef | undefined {
  const parts = pathname.split('/').filter(Boolean).map(decodeURIComponent);
  if (parts.length !== 4) return undefined;
  const [connId, owner, type, file] = parts;
  const name = file.replace(/\.sql$/, '');
  return { connId, ref: { owner, name, type }, body: /(^|&)body=1(&|$)/.test(query) };
}

/** Para um objecto com corpo separado (PACKAGE/TYPE), o URI do corpo. */
export const hasBodyVariant = (type: string) => type === 'PACKAGE' || type === 'TYPE';
