import * as vscode from 'vscode';
import { ConnDto, DriverDto } from './model';
import { SidecarClient } from './rpc';

/** Estado partilhado da extensão: cliente do servidor, ligações em cache e quais estão ligadas. */
export class Core {
  connections: ConnDto[] = [];
  drivers: DriverDto[] = [];
  configDir = '';
  readonly connected = new Set<string>();
  readonly currentSchema = new Map<string, string>();
  /** schema escolhido por consola (uri do documento → schema); sem entrada = o do próprio utilizador */
  readonly docSchema = new Map<string, string>();
  private readonly passwords = new Map<string, string>();

  private readonly _onConnections = new vscode.EventEmitter<void>();
  readonly onConnectionsChanged = this._onConnections.event;
  private readonly _onState = new vscode.EventEmitter<string | undefined>();
  /** muda quando uma ligação liga/desliga (argumento: id da ligação) */
  readonly onConnectionState = this._onState.event;

  constructor(readonly client: SidecarClient, readonly log: vscode.OutputChannel) {}

  async refreshConfig(): Promise<void> {
    const r = await this.client.request<{ connections: ConnDto[]; drivers: DriverDto[]; configDir: string }>('config.get');
    this.connections = r.connections;
    this.drivers = r.drivers;
    this.configDir = r.configDir;
    this._onConnections.fire();
  }

  conn(id: string): ConnDto | undefined {
    return this.connections.find((c) => c.id === id);
  }

  connName(id: string): string {
    const c = this.conn(id);
    return c ? (c.name || c.effectiveUrl || c.id) : id;
  }

  /** Liga (pedindo a palavra-passe se não estiver guardada). Devolve false se o utilizador cancelar. */
  async ensureConnected(id: string): Promise<boolean> {
    if (this.connected.has(id)) return true;
    let password: string | undefined = this.passwords.get(id);
    if (password === undefined && (await this.client.request<boolean>('connection.needsPassword', { id }))) {
      const c = this.conn(id);
      password = await vscode.window.showInputBox({ prompt: `Palavra-passe de ${c?.user ?? ''} em ${this.connName(id)}`, password: true, ignoreFocusOut: true });
      if (password === undefined) return false;
      this.passwords.set(id, password);
    }
    try {
      const r = await this.client.request<{ currentSchema: string; version: string }>('connect', { id, password });
      this.connected.add(id);
      this.currentSchema.set(id, r.currentSchema);
      this.log.appendLine(`Ligado a ${this.connName(id)} (${r.version}), schema ${r.currentSchema}`);
      this._onState.fire(id);
      return true;
    } catch (e) {
      this.passwords.delete(id);
      this._onState.fire(id);
      throw e;
    }
  }

  async disconnect(id: string): Promise<void> {
    await this.client.request('disconnect', { id });
    this.connected.delete(id);
    this.currentSchema.delete(id);
    this._onState.fire(id);
  }

  /** Esquece o estado "ligado" (a ligação foi editada/removida). */
  forget(id: string): void {
    this.connected.delete(id);
    this.currentSchema.delete(id);
    this.passwords.delete(id);
    this._onState.fire(id);
  }

  setting<T>(key: string, def: T): T {
    return vscode.workspace.getConfiguration('oraStudio').get<T>(key, def);
  }
}

export function errText(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}
