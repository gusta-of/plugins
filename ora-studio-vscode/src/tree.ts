import * as vscode from 'vscode';
import { fullErrorText } from './errorBox';
import { Core, errText } from './core';
import { BROWSABLE_TYPES, ConnDto, ObjRef, TYPE_ICON, TYPE_LABEL, TYPE_PLURAL, isRelation } from './model';

export type Node =
  | { kind: 'conn'; conn: ConnDto }
  | { kind: 'schema'; connId: string; name: string; current: boolean }
  | { kind: 'category'; connId: string; owner: string; type: string }
  | { kind: 'object'; connId: string; ref: ObjRef; valid: boolean }
  | { kind: 'message'; text: string; error?: boolean; detail?: string };

/** Árvore: Ligação › Schema › Categoria (Tabelas, Views, ...) › Objeto, com carregamento ao expandir. */
export class ConnectionsTree implements vscode.TreeDataProvider<Node> {
  private readonly _onDidChange = new vscode.EventEmitter<Node | undefined | void>();
  readonly onDidChangeTreeData = this._onDidChange.event;
  /** erros de ligação a mostrar como filho do nó (por ligação) */
  private readonly errors = new Map<string, string>();

  constructor(private readonly core: Core) {
    core.onConnectionsChanged(() => this.refresh());
    core.onConnectionState(() => this.refresh());
  }

  refresh(node?: Node): void {
    this._onDidChange.fire(node);
  }

  clearError(connId: string): void {
    this.errors.delete(connId);
  }

  getTreeItem(n: Node): vscode.TreeItem {
    switch (n.kind) {
      case 'conn': {
        const on = this.core.connected.has(n.conn.id);
        const it = new vscode.TreeItem(n.conn.name || n.conn.effectiveUrl || n.conn.id, vscode.TreeItemCollapsibleState.Collapsed);
        it.id = `conn:${n.conn.id}`;
        it.iconPath = new vscode.ThemeIcon(on ? 'database' : 'debug-disconnect', on ? new vscode.ThemeColor('testing.iconPassed') : undefined);
        it.contextValue = on ? 'connectionOn' : 'connection';
        it.tooltip = `${n.conn.effectiveUrl ?? ''}\nutilizador: ${n.conn.user}`;
        it.description = on ? this.core.currentSchema.get(n.conn.id) : undefined;
        return it;
      }
      case 'schema': {
        const it = new vscode.TreeItem(n.name, vscode.TreeItemCollapsibleState.Collapsed);
        it.id = `schema:${n.connId}:${n.name}`;
        it.iconPath = new vscode.ThemeIcon('symbol-namespace', n.current ? new vscode.ThemeColor('testing.iconPassed') : undefined);
        it.contextValue = 'schema';
        if (n.current) it.description = 'atual';
        return it;
      }
      case 'category': {
        const it = new vscode.TreeItem(TYPE_PLURAL[n.type] ?? n.type, vscode.TreeItemCollapsibleState.Collapsed);
        it.id = `cat:${n.connId}:${n.owner}:${n.type}`;
        it.iconPath = new vscode.ThemeIcon(TYPE_ICON[n.type] ?? 'symbol-misc');
        it.contextValue = 'category';
        return it;
      }
      case 'object': {
        const it = new vscode.TreeItem(n.ref.name, vscode.TreeItemCollapsibleState.None);
        it.id = `obj:${n.connId}:${n.ref.owner}.${n.ref.name}:${n.ref.type}`;
        it.iconPath = new vscode.ThemeIcon(TYPE_ICON[n.ref.type] ?? 'symbol-misc', n.valid ? undefined : new vscode.ThemeColor('errorForeground'));
        it.contextValue = isRelation(n.ref.type) ? 'objectRelation' : 'object';
        it.tooltip = `${n.ref.owner}.${n.ref.name}  [${TYPE_LABEL[n.ref.type] ?? n.ref.type}]${n.valid ? '' : '  — INVÁLIDO'}`;
        it.command = { command: 'oraStudio.openObject', title: 'Abrir', arguments: [n.connId, n.ref] };
        return it;
      }
      case 'message': {
        const it = new vscode.TreeItem(n.text, vscode.TreeItemCollapsibleState.None);
        it.iconPath = n.error ? new vscode.ThemeIcon('error', new vscode.ThemeColor('errorForeground')) : undefined;
        if (n.detail) { it.tooltip = n.detail; it.command = { command: 'oraStudio.showError', title: 'Ver erro completo', arguments: ['Erro', n.detail] }; }
        return it;
      }
    }
  }

  async getChildren(n?: Node): Promise<Node[]> {
    try {
      if (!n) {
        if (this.core.connections.length === 0) {
          return [{ kind: 'message', text: 'Sem ligações. Use «+» para criar uma.' }];
        }
        return this.core.connections.map((conn): Node => ({ kind: 'conn', conn }));
      }
      switch (n.kind) {
        case 'conn': {
          const prev = this.errors.get(n.conn.id);
          if (!this.core.connected.has(n.conn.id)) {
            this.errors.delete(n.conn.id);
            if (!(await this.core.ensureConnected(n.conn.id))) return [{ kind: 'message', text: 'Ligação cancelada (clique para tentar de novo)' }];
          }
          void prev;
          const schemas = await this.core.client.request<string[]>('meta.schemas', { id: n.conn.id });
          const cur = this.core.currentSchema.get(n.conn.id);
          return [...schemas].sort((a, b) => Number(b === cur) - Number(a === cur) || a.localeCompare(b))
            .map((name): Node => ({ kind: 'schema', connId: n.conn.id, name, current: name === cur }));
        }
        case 'schema':
          return BROWSABLE_TYPES.map((type): Node => ({ kind: 'category', connId: n.connId, owner: n.name, type }));
        case 'category': {
          const objs = await this.core.client.request<{ name: string; valid: boolean }[]>('meta.objects', { id: n.connId, owner: n.owner, type: n.type });
          if (objs.length === 0) return [{ kind: 'message', text: '(vazio)' }];
          return objs.map((o): Node => ({ kind: 'object', connId: n.connId, ref: { owner: n.owner, name: o.name, type: n.type }, valid: o.valid }));
        }
        default:
          return [];
      }
    } catch (e) {
      if (n?.kind === 'conn') {
        this.errors.set(n.conn.id, errText(e));
        this.core.connected.delete(n.conn.id);
      }
      return [{ kind: 'message', text: errText(e).split('\n')[0] + '  (clique para ver tudo)', error: true, detail: fullErrorText(e) }];
    }
  }
}
