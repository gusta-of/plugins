import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { WlWatcher, expectedApps } from '../src/wlStatus';
import { TaskRunner } from '../src/runner';

const mk = () => {
  const d = fs.mkdtempSync(path.join(os.tmpdir(), 'wl-'));
  fs.mkdirSync(path.join(d, 'config'), { recursive: true });
  fs.writeFileSync(path.join(d, 'config', 'config.xml'), '<domain><app-deployment><name>liferay</name></app-deployment><app-deployment><name>pem-crd-portlet</name></app-deployment><app-deployment><name>integration-portlet</name></app-deployment></domain>');
  return d;
};

test('lê os artefactos esperados do config.xml', () => {
  assert.deepEqual(expectedApps(mk()), ['liferay', 'pem-crd-portlet', 'integration-portlet']);
});

test('estado de cada artefacto: espera → a carregar → pronto, e falha com detalhe', () => {
  const w = new WlWatcher();
  w.start(mk());

  assert.equal(w.counts().loading, 3);
  assert.ok([...w.apps.values()].every((a) => a.state === 'pending'));

  w.feed('<21/out/2026 16H11m UTC> <Notice> <WebLogicServer> <BEA-000365> <Server state changed to STANDBY.>');
  assert.equal(w.serverState, 'STANDBY');
  w.feed('####<28/out/2026 11H38m UTC> <Info> <Deployer> <h> <AdminServer> <t> <<WLS Kernel>> <> <id> <1> <[sev]> <BEA-149060> <Module pem-crd-portlet of application pem-crd-portlet successfully transitioned from STATE_NEW to STATE_PREPARED on server AdminServer.>', 'file');
  assert.equal(w.apps.get('pem-crd-portlet')!.state, 'loading');
  w.feed('<BEA-149060> <Module pem-crd-portlet of application pem-crd-portlet successfully transitioned from STATE_ADMIN to STATE_ACTIVE on server AdminServer.>', 'file');
  assert.equal(w.apps.get('pem-crd-portlet')!.state, 'ready');

  // falha, com as linhas seguintes a servirem de detalhe até ao próximo cabeçalho
  w.feed('<21/out/2026 16H11m UTC> <Error> <Deployer> <BEA-149205> <Failed to initialize the application "integration-portlet" due to error weblogic.application.ModuleException: Error: Unresolved Webapp Library references');
  w.feed('weblogic.application.ModuleException: Error: Unresolved Webapp Library');
  w.feed('\tat weblogic.servlet.internal.WebAppModule.init(WebAppModule.java:284)');
  w.feed('<21/out/2026 16H11m UTC> <Notice> <WebLogicServer> <BEA-000365> <Server state changed to RUNNING.>');
  const bad = w.apps.get('integration-portlet')!;
  assert.equal(bad.state, 'failed');
  assert.match(bad.error!, /Unresolved Webapp Library/);
  assert.match(bad.error!, /WebAppModule\.init/);
  assert.equal(w.serverState, 'RUNNING');
  // um artefacto que falhou não volta a "pronto" por uma transição posterior
  w.feed('<BEA-149060> <Module integration-portlet of application integration-portlet successfully transitioned from STATE_ADMIN to STATE_ACTIVE on server AdminServer.>');
  assert.equal(bad.state, 'failed');
  assert.deepEqual(w.counts(), { ready: 1, loading: 1, failed: 1, total: 3 });
  w.stop();
});

test('lê o ficheiro de log novo (só o que é escrito depois do arranque) e deteta rotação', async () => {
  const d = mk();
  const logs = path.join(d, 'servers', 'AdminServer', 'logs');
  fs.mkdirSync(logs, { recursive: true });
  const f = path.join(logs, 'AdminServer.log');
  fs.writeFileSync(f, '<BEA-149060> <Module liferay of application liferay successfully transitioned from STATE_ADMIN to STATE_ACTIVE on server AdminServer.>\n'); // antigo: ignorado
  const w = new WlWatcher();
  w.start(d);
  fs.appendFileSync(f, '<BEA-149060> <Module pem-crd-portlet of application pem-crd-portlet successfully transitioned from STATE_ADMIN to STATE_ACTIVE on server AdminServer.>\n');
  await new Promise((r) => setTimeout(r, 1300));
  assert.equal(w.apps.get('liferay')!.state, 'pending');
  assert.equal(w.apps.get('pem-crd-portlet')!.state, 'ready');
  fs.writeFileSync(f, '<BEA-149060> <Module liferay of application liferay successfully transitioned from STATE_ADMIN to STATE_ACTIVE on server AdminServer.>\n'); // rodou: ficheiro novo, mais pequeno
  await new Promise((r) => setTimeout(r, 1300));
  assert.equal(w.apps.get('liferay')!.state, 'ready');
  w.stop();
});

test('o TaskRunner regista o resultado de cada passo, com a saída dos que falham', async () => {
  const lines: string[] = [];
  const r = new TaskRunner((t) => lines.push(t));
  let notified = 0;
  r.onResults = () => { notified++; };
  const cwd = fs.mkdtempSync(path.join(os.tmpdir(), 'rn-'));
  await r.run('Build teste', 3, async (s) => {
    await s.shell('modulo-ok', cwd, 'echo tudo bem');
    await s.shell('modulo-mau', cwd, 'echo "[ERROR] COMPILATION ERROR"; echo detalhe 1>&2; exit 3');
    await s.step('passo ts', () => { throw new Error('falhou em TS'); });
  });
  assert.deepEqual(r.results.map((x) => [x.label, x.status]), [['modulo-ok', 'ok'], ['modulo-mau', 'fail'], ['passo ts', 'fail']]);
  assert.match(r.results[1].output, /COMPILATION ERROR/);
  assert.match(r.results[1].output, /detalhe/);
  assert.match(r.results[1].output, /exit code: 3/);
  assert.match(r.results[2].output, /falhou em TS/);
  assert.ok(notified >= 6);
  assert.equal(r.resultsTitle, 'Build teste');
});
