import { JobDto, JobRunDto } from './model';

/** Texto do documento de um job (agenda, estado, histórico e o código que executa). Sem dependências do VS Code. */
export function jobText(j: JobDto, runs: JobRunDto[]): string {
  const L: string[] = [];
  L.push(`-- JOB ${j.owner}.${j.name}   [${j.kind === 'scheduler' ? 'DBMS_SCHEDULER' : 'DBMS_JOB'}]`);
  L.push(`-- Estado: ${j.state}   Ativo: ${j.enabled ? 'sim' : 'não'}${j.failures ? `   Falhas: ${j.failures}` : ''}`);
  L.push(`-- Agenda: ${j.schedule || '(sem)'}`);
  L.push(`-- Próxima execução: ${j.nextRun || '—'}`);
  L.push(`-- Última execução:  ${j.lastRun || '—'}${j.lastDuration ? `  (duração ${j.lastDuration})` : ''}`);
  if (j.jobType) L.push(`-- Tipo: ${j.jobType}`);
  if (j.comments) L.push(`-- Comentário: ${j.comments}`);
  if (j.kind === 'scheduler') {
    L.push('--', `-- Histórico (últimas ${runs.length} execuções):`);
    if (runs.length === 0) L.push('--   (nenhuma execução registada)');
    for (const r of runs) {
      L.push(`--   ${r.startedAt}  ${r.status.padEnd(10)} ${r.duration}${r.errorCode ? `  ORA-${String(r.errorCode).padStart(5, '0')}` : ''}`);
      if (r.info && r.status !== 'SUCCEEDED') for (const l of r.info.split(/\r?\n/).slice(0, 4)) L.push(`--       ${l}`);
    }
  }
  L.push('--', '-- Código executado:', '');
  const a = j.action.trim();
  L.push(a.endsWith(';') || a.endsWith('/') ? a : `${a};`);
  return L.join('\n') + '\n';
}

