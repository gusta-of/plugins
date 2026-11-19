import * as vscode from 'vscode';
import { Core, errText } from './core';
import { jobText } from './jobText';
import { JobDto, JobRunDto } from './model';

/** Documentos virtuais `orastudio-job:` (só de leitura) com o detalhe de um job: agenda, estado, histórico e o código que executa. */
export const JOB_SCHEME = 'orastudio-job';

export function jobUri(connId: string, j: JobDto): vscode.Uri {
  return vscode.Uri.from({ scheme: JOB_SCHEME, path: `/${encodeURIComponent(connId)}/${j.kind}/${encodeURIComponent(j.owner)}/${encodeURIComponent(j.name)}.sql` });
}

function parse(u: vscode.Uri): { connId: string; kind: string; owner: string; name: string } | undefined {
  const p = u.path.split('/').filter(Boolean).map(decodeURIComponent);
  if (p.length !== 4) return undefined;
  return { connId: p[0], kind: p[1], owner: p[2], name: p[3].replace(/\.sql$/, '') };
}

export class JobDocProvider implements vscode.TextDocumentContentProvider {
  constructor(private readonly core: Core) {}

  async provideTextDocumentContent(uri: vscode.Uri): Promise<string> {
    const d = parse(uri);
    if (!d) return '-- URI inválido\n';
    try {
      if (!(await this.core.ensureConnected(d.connId))) return '-- Ligação cancelada\n';
      let jobs = this.core.jobs.get(d.connId);
      let job = jobs?.find((x) => x.kind === d.kind && x.owner === d.owner && x.name === d.name);
      if (!job) {
        jobs = await this.core.client.request<JobDto[]>('meta.jobs', { id: d.connId });
        this.core.jobs.set(d.connId, jobs);
        job = jobs.find((x) => x.kind === d.kind && x.owner === d.owner && x.name === d.name);
      }
      if (!job) return `-- O job ${d.owner}.${d.name} já não existe.\n`;
      const runs = job.kind === 'scheduler'
        ? await this.core.client.request<JobRunDto[]>('meta.jobRuns', { id: d.connId, owner: job.owner, name: job.name, limit: 30 })
        : [];
      return jobText(job, runs);
    } catch (e) {
      return `-- Não foi possível obter o job ${d.owner}.${d.name}:\n-- ${errText(e).split('\n').join('\n-- ')}\n`;
    }
  }
}
