import { test } from 'node:test';
import assert from 'node:assert/strict';
import { identifierAt, inLineCommentOrString, norm } from '../src/sqlText';
import { toCsv, esc } from '../src/html';
import { quotedRef, isRelation } from '../src/model';

test('identificador com qualificador', () => {
  const id = identifierAt('select * from hr.employees e', 18)!;
  assert.equal(id.name, 'EMPLOYEES');
  assert.equal(id.qualifier, 'HR');
});
test('aspas preservam caixa', () => assert.equal(norm('"MixedCase"'), 'MixedCase'));
test('comentário e string', () => {
  assert.equal(inLineCommentOrString("select 1 -- foo", 14), true);
  assert.equal(inLineCommentOrString("select 'a b' x", 10), true);
  assert.equal(inLineCommentOrString("select abc", 8), false);
});
test('csv escapa', () => {
  assert.equal(toCsv(['A', 'B'], [['x,y', null], ['"q"', '1']]), '\ufeffA;B\r\nx,y;\r\n"""q""";1\r\n');
});
test('html escape + modelo', () => {
  assert.equal(esc('<a&"'), '&lt;a&amp;&quot;');
  assert.equal(quotedRef({ owner: 'HR', name: 'My Tab', type: 'TABLE' }), 'HR."My Tab"');
  assert.ok(isRelation('VIEW'));
});

test('documento de um job: agenda, histórico e código', async () => {
  const { jobText } = await import('../src/jobText');
  const j = { kind: 'scheduler' as const, owner: 'PEM', name: 'LIMPA_LOGS', enabled: true, state: 'SCHEDULED', schedule: 'FREQ=DAILY;BYHOUR=3', nextRun: '2026-11-06 03:00:00 +00:00', lastRun: '2026-11-05 03:00:00', lastDuration: '+000000000 00:00:02', jobType: 'PLSQL_BLOCK', action: 'BEGIN pem.limpa; END;', failures: 1, comments: 'limpeza' };
  const t = jobText(j, [{ startedAt: '2026-11-05 03:00:00', status: 'FAILED', duration: '00:00:02', errorCode: 942, info: 'ORA-00942: table or view does not exist\nmais' }]);
  assert.match(t, /^-- JOB PEM\.LIMPA_LOGS {3}\[DBMS_SCHEDULER\]/);
  assert.match(t, /Agenda: FREQ=DAILY;BYHOUR=3/);
  assert.match(t, /FAILED +00:00:02 {2}ORA-00942/);
  assert.match(t, /--       ORA-00942: table or view does not exist/);
  assert.ok(t.trimEnd().endsWith('BEGIN pem.limpa; END;'));
  const old = jobText({ ...j, kind: 'dbms_job', name: 'JOB 12', action: 'pem.limpa;', failures: 0 }, []);
  assert.ok(!old.includes('Histórico') && old.trimEnd().endsWith('pem.limpa;'));
});

test('filtro de jobs: texto, curinga, regex e regex inválida', async () => {
  const { jobMatcher } = await import('../src/jobFilter');
  const j = (owner: string, name: string) => ({ kind: 'scheduler' as const, owner, name, enabled: true, state: '', schedule: '', nextRun: '', lastRun: '', lastDuration: '', jobType: '', action: '', failures: 0, comments: '' });
  const all = [j('PEM', 'LIMPA_LOGS'), j('PEM', 'ENVIA_EMAIL'), j('APP', 'LIMPA_CACHE')];
  const names = (f: string) => all.filter(jobMatcher(f)).map((x) => x.name);
  assert.deepEqual(names(''), ['LIMPA_LOGS', 'ENVIA_EMAIL', 'LIMPA_CACHE']);
  assert.deepEqual(names('limpa'), ['LIMPA_LOGS', 'LIMPA_CACHE']);
  assert.deepEqual(names('limpa*logs'), ['LIMPA_LOGS']);
  assert.deepEqual(names('app.'), ['LIMPA_CACHE']);
  assert.deepEqual(names('/_(email|cache)$/'), ['ENVIA_EMAIL', 'LIMPA_CACHE']);
  assert.deepEqual(names('/([/'), []);
});
