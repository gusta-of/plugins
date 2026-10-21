/** Tipos trocados com o servidor Java (iguais aos do ora-studio-server). */

export interface ObjRef {
  owner: string;
  name: string;
  /** nome SQL do tipo: TABLE, VIEW, "MATERIALIZED VIEW", PROCEDURE, FUNCTION, PACKAGE, TRIGGER, TYPE, SEQUENCE, SYNONYM, INDEX */
  type: string;
}

export interface ConnDto {
  id: string;
  name: string;
  driverId: string;
  type: 'SID' | 'SERVICE_NAME' | 'URL' | string;
  host: string;
  port: number;
  database: string;
  user: string;
  savePassword: boolean;
  url: string | null;
  autoCommit: boolean;
  rowLimit: number;
  connectTimeoutSec: number;
  initSql: string | null;
  hasPassword: boolean;
  effectiveUrl?: string;
}

export interface DriverDto {
  id: string;
  name: string;
  className: string;
  jars: string[];
}

export interface ColumnMeta {
  name: string;
  typeName: string;
  numeric: boolean;
}

export interface CompletionItemDto {
  text: string;
  kind: 'COLUMN' | 'OBJECT' | 'SCHEMA' | 'MEMBER' | 'KEYWORD';
  type: string | null;
  detail: string;
  right: string;
}

export interface CompleteResult {
  prefixStart: number;
  prefix: string;
  loading: boolean;
  items: CompletionItemDto[];
}

export interface TableData {
  cursorId: string;
  columns: ColumnMeta[];
  rows: (string | null)[][];
  hasMore: boolean;
  elapsedMs: number;
}

export const BROWSABLE_TYPES = ['TABLE', 'VIEW', 'MATERIALIZED VIEW', 'PROCEDURE', 'FUNCTION', 'PACKAGE', 'TRIGGER', 'TYPE', 'SEQUENCE', 'SYNONYM'];

export const TYPE_PLURAL: Record<string, string> = {
  TABLE: 'Tabelas', VIEW: 'Views', 'MATERIALIZED VIEW': 'Materialized views', PROCEDURE: 'Procedures', FUNCTION: 'Functions',
  PACKAGE: 'Packages', TRIGGER: 'Triggers', TYPE: 'Types', SEQUENCE: 'Sequences', SYNONYM: 'Synonyms', INDEX: 'Indexes',
};

export const TYPE_LABEL: Record<string, string> = {
  TABLE: 'Tabela', VIEW: 'View', 'MATERIALIZED VIEW': 'Materialized view', PROCEDURE: 'Procedure', FUNCTION: 'Function',
  PACKAGE: 'Package', TRIGGER: 'Trigger', TYPE: 'Type', SEQUENCE: 'Sequence', SYNONYM: 'Synonym', INDEX: 'Index',
};

/** codicons do VS Code por tipo de objeto */
export const TYPE_ICON: Record<string, string> = {
  TABLE: 'table', VIEW: 'eye', 'MATERIALIZED VIEW': 'server', PROCEDURE: 'symbol-method', FUNCTION: 'symbol-function',
  PACKAGE: 'package', TRIGGER: 'zap', TYPE: 'symbol-class', SEQUENCE: 'symbol-numeric', SYNONYM: 'link', INDEX: 'list-ordered',
};

export const isRelation = (type: string) => type === 'TABLE' || type === 'VIEW' || type === 'MATERIALIZED VIEW';

export const quoteId = (id: string) => (/^[A-Z][A-Z0-9_$#]*$/.test(id) ? id : `"${id}"`);
export const quotedRef = (r: ObjRef) => `${quoteId(r.owner)}.${quoteId(r.name)}`;
export const refKey = (r: ObjRef) => `${r.owner}.${r.name}:${r.type}`;
