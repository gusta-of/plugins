import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import * as M from '../src/modules';
import { Operations, deleteTargets, clearDir, findArtifact, parsePs, matchModuleJars, matchWebLogic, withDescendants, listProcesses } from '../src/operations';
import { TaskRunner, ProgressEvent } from '../src/runner';

const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), 'pemwl-'));
// NUNCA tocar no ~/.m2 real
process.env.PEMWL_M2_PEM_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'pemwl-m2-'));
const touch = (p: string, content = 'x') => { fs.mkdirSync(path.dirname(p), { recursive: true }); fs.writeFileSync(p, content); };

function mk() {
  const lines: string[] = [];
  const wl: string[] = [];
  const runner = new TaskRunner((t) => lines.push(t));
  const wlRunner = new TaskRunner((t) => wl.push(t));
  const ops = new Operations(runner, wlRunner, (t) => wl.push(t));
  const progress: ProgressEvent[] = [];
  runner.onProgress = (e) => progress.push(e);
  wlRunner.onProgress = (e) => progress.push(e);
  return { lines, wl, runner, wlRunner, ops, progress };
}

test('sanitizeThreads', () => {
  assert.equal(M.sanitizeThreads('4'), '4');
  assert.equal(M.sanitizeThreads(' 1.5C '), '1.5C');
  assert.equal(M.sanitizeThreads('abc'), '1C');
  assert.equal(M.sanitizeThreads(''), '1C');
});

test('ordem do build backend igual ao build-backend.sh', () => {
  const labels = M.backendBuild().map((s) => s.label);
  assert.deepEqual(labels.slice(0, 4), ['pem-common-beans', 'pem', 'spms-plugins-common', 'pem-common-domain']);
  assert.ok(labels.indexOf('pem-common') > labels.indexOf('pem-common-app'));
  assert.equal(labels[labels.length - 1], 'Test report backend');
  assert.equal(M.backendBuild().at(-1)!.withProfile, false);
});

test('deleteTargets apaga target/ em todos os níveis e ignora node_modules e .git', () => {
  const root = tmp();
  touch(path.join(root, 'a/target/x.class'));
  touch(path.join(root, 'spms-plugins/portlets/p/target/y.war'));
  touch(path.join(root, 'node_modules/foo/target/keep.txt'));
  touch(path.join(root, '.git/target/keep.txt'));
  touch(path.join(root, 'a/src/main/Target.java'));
  const removed = deleteTargets(root);
  assert.equal(removed.length, 2);
  assert.ok(!fs.existsSync(path.join(root, 'a/target')));
  assert.ok(fs.existsSync(path.join(root, 'node_modules/foo/target/keep.txt')));
  assert.ok(fs.existsSync(path.join(root, '.git/target/keep.txt')));
  assert.ok(fs.existsSync(path.join(root, 'a/src/main/Target.java')));
});

test('clearDir mantém a pasta e os nomes protegidos', () => {
  const d = tmp();
  touch(path.join(d, 'fonts/a.ttf')); touch(path.join(d, 'x.jar')); touch(path.join(d, 'sub/y'));
  clearDir(d, new Set(['fonts']));
  assert.deepEqual(fs.readdirSync(d), ['fonts']);
});

test('clearClasspath: apaga cache/autodeploy (poupando marketplace-portlet e pem-layouttpl), targets e m2 (poupando fonts)', async () => {
  const ws = tmp(); const dom = tmp(); const m2 = tmp();
  touch(path.join(ws, 'mod/target/a.jar'));
  for (const f of ['servers/AdminServer/tmp/_WL_user/app/x', 'servers/AdminServer/tmp/_WL_internal/y', 'servers/AdminServer/cache/z',
    'liferay/deploy/old.war', 'liferay/data/osgi/state/s', 'liferay/data/hsql/db', 'liferay/data/lucene/idx']) touch(path.join(dom, f));
  for (const n of ['marketplace-portlet', 'pem-layouttpl', 'portal-portlet', '.hidden']) touch(path.join(dom, 'autodeploy', n, 'f'));
  touch(path.join(m2, 'fonts/f.ttf')); touch(path.join(m2, '2.5.0/pem.jar'));
  const { ops, lines } = mk();
  await ops.clearClasspath({ workspace: ws, domain: dom }, m2);
  assert.ok(!fs.existsSync(path.join(ws, 'mod/target')));
  assert.deepEqual(fs.readdirSync(path.join(dom, 'autodeploy')).sort(), ['.hidden', 'marketplace-portlet', 'pem-layouttpl']);
  assert.deepEqual(fs.readdirSync(path.join(dom, 'servers/AdminServer/tmp/_WL_user')), []);
  assert.deepEqual(fs.readdirSync(path.join(dom, 'liferay/deploy')), []);
  assert.deepEqual(fs.readdirSync(path.join(dom, 'liferay/data/osgi')), []);
  assert.ok(fs.existsSync(path.join(dom, 'liferay/data/hsql/db')), 'hsql intocado');
  assert.ok(fs.existsSync(path.join(dom, 'liferay/data/lucene/idx')), 'lucene intocado');
  assert.deepEqual(fs.readdirSync(m2), ['fonts']);
  assert.ok(lines.some((l) => l.includes('concluído com sucesso')), lines.join('\n'));
});

test('findArtifact escolhe o mais recente e ignora sources', () => {
  const t = tmp();
  touch(path.join(t, 'pem-x-app-2.3.0.war'));
  touch(path.join(t, 'pem-x-app-2.3.0-sources.jar'));
  touch(path.join(t, 'pem-x-app-2.5.0.war'));
  fs.utimesSync(path.join(t, 'pem-x-app-2.3.0.war'), new Date(2020, 1, 1), new Date(2020, 1, 1));
  assert.equal(path.basename(findArtifact(t, 'pem-x-app', 'war')), 'pem-x-app-2.5.0.war');
  assert.throws(() => findArtifact(t, 'inexistente', 'war'), /corre o build primeiro/);
});

test('deploy backend: WAR para upload (apaga o staging), JAR para lib; frontend: autodeploy + liferay/deploy', async () => {
  const ws = tmp(); const dom = tmp();
  touch(path.join(ws, 'pem-fh-app/target/pem-fh-app-2.5.0.war'));
  touch(path.join(ws, 'pem-common-beans/target/pem-common-beans-2.5.0.jar'));
  touch(path.join(ws, 'spms-plugins/portlets/header-portlet/target/header-portlet-2.5.0.war'));
  touch(path.join(dom, 'servers/AdminServer/tmp/_WL_user/pem-fh-app/old.class'));
  touch(path.join(dom, 'autodeploy/header-portlet/old.class'));
  const { ops } = mk();
  const p = { workspace: ws, domain: dom };
  await ops.deployBackend(p, ['pem-fh-app', 'pem-common-beans']);
  assert.ok(fs.existsSync(path.join(dom, 'servers/AdminServer/upload/pem-fh-app-2.5.0.war')));
  assert.ok(fs.existsSync(path.join(dom, 'lib/pem-common-beans-2.5.0.jar')));
  assert.ok(!fs.existsSync(path.join(dom, 'servers/AdminServer/tmp/_WL_user/pem-fh-app')));
  await ops.deployFrontend(p, ['header-portlet']);
  assert.ok(fs.existsSync(path.join(dom, 'liferay/deploy/header-portlet-2.5.0.war')));
  assert.ok(!fs.existsSync(path.join(dom, 'autodeploy/header-portlet')));
});

test('deploy de um módulo sem artefacto falha só esse passo e continua (resumo no fim)', async () => {
  const ws = tmp(); const dom = tmp();
  touch(path.join(ws, 'pem-fh-app/target/pem-fh-app-1.war'));
  const { ops, lines, progress } = mk();
  await ops.deployBackend({ workspace: ws, domain: dom }, ['pem-dm-app', 'pem-fh-app']);
  assert.ok(fs.existsSync(path.join(dom, 'servers/AdminServer/upload/pem-fh-app-1.war')));
  assert.ok(lines.some((l) => l.includes('concluído com falhas')));
  assert.ok(lines.some((l) => l === ' - Deploy pem-dm-app'));
  const last = progress.at(-1)!;
  assert.equal(last.finished, true);
  assert.equal(last.done, 2);
});

test('comando mvn: perfil, skip tests, localMock só no pem-common-domain, threads', () => {
  const { ops } = mk();
  const o = { profile: 'dev', skipTests: true, localMock: true, threads: '4' };
  const be = M.backendBuild();
  const domain = be.find((s) => s.label === 'pem-common-domain')!;
  const other = be.find((s) => s.label === 'pem-fh-domain')!;
  const report = be.find((s) => s.label === 'Test report backend')!;
  assert.equal(ops.buildCommand(domain, o), 'mvn clean install -rf :pem-common-domain war:manifest war:war -Pdev,localMock -Dmaven.test.skip=true');
  assert.equal(ops.buildCommand(other, o), 'mvn clean install -rf :pem-fh-domain war:manifest war:war -Pdev -Dmaven.test.skip=true');
  assert.equal(ops.buildCommand(report, o), 'mvn allure:aggregate');
  assert.equal(ops.buildCommand(domain, { ...o, profile: '', skipTests: false, localMock: false }), 'mvn clean install -rf :pem-common-domain war:manifest war:war');
  assert.equal(ops.buildCommand(M.frontendBuild(true, '4')[1], o), 'mvn clean install -T 4 -Pdev -Dmaven.test.skip=true');
  assert.equal(M.frontendModuleBuild('pem-hook').dir, 'spms-plugins/hooks/pem-hook');
});

test('TaskRunner: executa comandos de shell, junta a saída, conta o progresso e continua após falhas', async () => {
  const { ops, lines, progress, runner } = mk();
  const dir = tmp();
  await runner.run('Teste', 3, async (s) => {
    await s.shell('ok', dir, 'echo ola-mundo');
    await s.shell('falha', dir, 'exit 3');
    await s.shell('depois', dir, 'echo continuou');
  });
  assert.ok(lines.includes('ola-mundo'));
  assert.ok(lines.includes('continuou'));
  assert.ok(lines.some((l) => l.includes('FAILED: falha (exit code 3)')));
  assert.deepEqual(progress.map((p) => p.done), [0, 1, 2, 3, 3], 'a falha também conta como passo feito');
  assert.equal(progress.at(-1)!.finished, true);
  void ops;
});

test('TaskRunner: recusa tarefa em paralelo, cancela e termina o processo filho', async () => {
  const { runner, lines } = mk();
  const dir = tmp();
  const first = runner.run('Longa', 1, async (s) => { await s.shell('sleep', dir, 'sleep 30'); });
  await new Promise((r) => setTimeout(r, 300));
  assert.equal(await runner.run('Outra', 1, async () => {}), false);
  runner.cancel();
  const t0 = Date.now();
  await first;
  assert.ok(Date.now() - t0 < 5000, 'cancelou depressa');
  assert.ok(lines.some((l) => l === 'Cancelado.'));
});

test('timeout do passo gracioso mata o comando e permite continuar (allowFail)', async () => {
  const { runner, lines } = mk();
  const dir = tmp();
  await runner.run('T', 2, async (s) => {
    await s.shell('lento', dir, 'sleep 30', { timeoutSec: 1, allowFail: true });
    await s.shell('depois', dir, 'echo seguiu');
  });
  assert.ok(lines.some((l) => l.includes('Timeout de 1s')));
  assert.ok(lines.includes('seguiu'));
  assert.ok(!lines.some((l) => l.includes('concluído com falhas')), 'allowFail não conta como falha');
});

test('parsePs / matchModuleJars / matchWebLogic / descendentes', () => {
  const procs = parsePs([
    '  100     1 /sbin/init',
    ' 2001   100 /opt/jdk/bin/java -jar /x/pem-fh-app/target/pem-fh-app.jar',
    ' 2002   100 /opt/jdk/bin/java -Dfoo -cp x org.other.Main',
    ' 3000   100 /bin/bash startWebLogic.sh',
    ' 3001  3000 /opt/jdk/bin/java -Ddomain.home=/opt/oracle/middleware/user_projects/domains/PEM weblogic.Server',
    ' 3002  3001 /bin/sh -c child',
    ' 3003   100 /opt/jdk/bin/java -Ddomain.home=/opt/oracle/OTHER weblogic.Server',
  ].join('\n'));
  assert.equal(procs.length, 7);
  assert.deepEqual(matchModuleJars(procs, 0).map((p) => p.pid), [2001]);
  const wl = matchWebLogic(procs, '/opt/oracle/middleware/user_projects/domains/PEM', 0);
  assert.deepEqual(wl.map((p) => p.pid), [3001]);
  assert.deepEqual(withDescendants(wl, procs).map((p) => p.pid).sort(), [3001, 3002]);
  // caminhos do Windows (barras invertidas) também casam
  const win = [{ pid: 9, ppid: 1, cmd: 'java -Ddomain.home=C:\\Users\\x\\domains\\PEM weblogic.Server' }];
  assert.equal(matchWebLogic(win, 'C:/Users/x/domains/PEM', 0).length, 1);
});

test('listProcesses devolve processos reais (inclui este)', async () => {
  const all = await listProcesses();
  assert.ok(all.some((p) => p.pid === process.pid));
});

test('WebLogic: start (script falso) arranca, stop gracioso falha/termina e o força-matar apanha o que sobrar', { skip: process.platform === 'win32' }, async () => {
  const dom = tmp();
  // processo falso com "weblogic.Server" e o caminho do domínio na linha de comando (node ignora os argumentos a seguir ao -e)
  touch(path.join(dom, 'bin/startWebLogic.sh'), `#!/bin/bash\nexec '${process.execPath}' -e "setTimeout(function(){},300000)" weblogic.Server "$PWD"\n`);
  touch(path.join(dom, 'bin/stopWebLogic.sh'), '#!/bin/bash\necho "stop gracioso (nao faz nada)"\nexit 0\n');
  const { ops, wl, wlRunner } = mk();
  const dom2 = dom;
  let exited = false;
  ops.onWebLogicExit = () => { exited = true; };
  assert.equal(ops.startWebLogic({ workspace: dom, domain: dom }), true);
  await new Promise((r) => setTimeout(r, 800));
  assert.equal(ops.weblogicRunning, true);
  assert.equal(ops.startWebLogic({ workspace: dom, domain: dom }), false, 'não arranca duas vezes');
  const before = matchWebLogic(await listProcesses(), dom);
  try {
    assert.ok(before.length >= 1, 'o processo "weblogic.Server" existe');
  } catch (e) {
    await ops.killWebLogic({ workspace: dom2, domain: dom2 });
    throw e;
  }
  await ops.stopWebLogic({ workspace: dom, domain: dom });
  await new Promise((r) => setTimeout(r, 500));
  assert.equal(matchWebLogic(await listProcesses(), dom).length, 0, 'tudo terminado');
  assert.ok(exited, 'avisou que o processo do start terminou');
  assert.ok(wl.some((l) => l.includes('WebLogic terminado')), wl.join('\n'));
  void wlRunner;
});

test('kill WebLogic quando nada corre', async () => {
  const { ops, wl } = mk();
  await ops.killWebLogic({ workspace: '/x', domain: '/nao/existe/dominio-xyz' });
  assert.ok(wl.some((l) => l.includes('Nenhum processo WebLogic')));
});
