import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { pomDependencies, sortByDependencies } from '../src/buildOrder';
import * as M from '../src/modules';

const GROUP = 'pt.min-saude.spms.modules.pem';
const pom = (ws: string, dir: string, deps: string[] = [], group = GROUP) => {
  fs.mkdirSync(path.join(ws, dir), { recursive: true });
  fs.writeFileSync(path.join(ws, dir, 'pom.xml'), `<project><dependencies>${deps.map((d) => `<dependency><groupId>${group}</groupId><artifactId>${d}</artifactId></dependency>`).join('')}</dependencies></project>`);
};

test('a ordem respeita as dependências dos pom, mesmo com a lista fixa ao contrário', () => {
  const ws = fs.mkdtempSync(path.join(os.tmpdir(), 'bo-'));
  pom(ws, 'pem-common', ['pem-common-domain']);
  pom(ws, 'pem-common-domain');
  pom(ws, 'pem-common-app', ['pem-common', 'pem-common-domain']);
  pom(ws, 'spms-plugins-common', ['pem-common', 'pem-common-domain']);
  const base = ['spms-plugins-common', 'pem-common-app', 'pem-common', 'pem-common-domain'];
  const deps = pomDependencies(ws, base);
  assert.deepEqual([...deps.get('spms-plugins-common')!].sort(), ['pem-common', 'pem-common-domain']);
  assert.deepEqual(sortByDependencies(base, deps), ['pem-common-domain', 'pem-common', 'spms-plugins-common', 'pem-common-app']);
});

test('grupo diferente, comentários e o próprio módulo não contam; ciclo não bloqueia', () => {
  const ws = fs.mkdtempSync(path.join(os.tmpdir(), 'bo-'));
  pom(ws, 'b');
  fs.mkdirSync(path.join(ws, 'a'));
  fs.writeFileSync(path.join(ws, 'a', 'pom.xml'), `<project><dependencies><!-- <dependency><groupId>${GROUP}</groupId><artifactId>b</artifactId></dependency> --><dependency><groupId>${GROUP}</groupId><artifactId>a</artifactId></dependency><dependency><groupId>outro</groupId><artifactId>b</artifactId></dependency></dependencies></project>`);
  assert.equal(pomDependencies(ws, ['a', 'b']).get('a')!.size, 0);
  let cyc: string[] = [];
  const order = sortByDependencies(['x', 'y'], new Map([['x', new Set(['y'])], ['y', new Set(['x'])]]), (r) => { cyc = r; });
  assert.deepEqual(order, ['x', 'y']);
  assert.deepEqual(cyc, ['x', 'y']);
});

test('no workspace real cada módulo vem depois de tudo aquilo de que depende', { skip: !fs.existsSync('/home/gustavo/workspace/PEM/PEM_Aplicacao/pem-common') }, () => {
  const ws = process.env.PEM_WORKSPACE ?? '/home/gustavo/workspace/PEM/PEM_Aplicacao';
  const steps = M.backendBuildOrdered(ws);
  const dirs = steps.filter((s) => s.withProfile).map((s) => s.dir);
  const deps = pomDependencies(ws, dirs);
  dirs.forEach((d, i) => { for (const dep of deps.get(d)!) assert.ok(dirs.indexOf(dep) < i, `${d} depende de ${dep} mas vem antes: ${dirs.join(' -> ')}`); });
  assert.ok(dirs.indexOf('pem-common') < dirs.indexOf('pem-common-app'));
  assert.ok(dirs.indexOf('pem-common') < dirs.indexOf('spms-plugins-common'));
  assert.deepEqual(steps.slice(0, 2).map((s) => s.label), ['pem-common-beans', 'pem']);
  assert.equal(steps.at(-1)!.label, 'Test report backend');
  const sel = M.orderEntries(ws, [{ name: 'pem-med-app', kind: 'backend' }, { name: 'pem-common-app', kind: 'backend' }, { name: 'pem-shared-domain', kind: 'backend' }, { name: 'pem-hook', kind: 'frontend' }]);
  assert.deepEqual(sel.map((e) => e.name), ['pem-common-app', 'pem-shared-domain', 'pem-med-app', 'pem-hook']);
});
