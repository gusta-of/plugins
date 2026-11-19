import { test } from 'node:test';
import assert from 'node:assert/strict';
// eslint-disable-next-line @typescript-eslint/no-require-imports
const { Engine, DEFAULT_RULES } = require('../media/highlight.js');

test('erro e aviso pintam a linha toda; linha normal fica sem estilo', () => {
  const e = new Engine(DEFAULT_RULES());
  const a = e.segments('[ERROR] COMPILATION ERROR');
  assert.equal(a.length, 1);
  assert.equal(a[0].style.background, '#7F1D1D');
  assert.equal(e.segments('2026 WARN algo')[0].style.background, '#7A5C00');
  assert.equal(e.segments('[INFO] normal')[0].style, null);
});

test('bloco <Error> do WebLogic vai até à linha que acaba em ">"', () => {
  const e = new Engine(DEFAULT_RULES());
  const first = e.segments('<21/out/2026 16H11m UTC> <Error> <Deployer> <BEA-149205> <Failed to initialize the application "x" due to error foo');
  assert.equal(first[0].style.background, '#B71C1C');
  for (const l of ['weblogic.application.ModuleException: Error: bar', '\tat weblogic.servlet.internal.WebAppModule.init(WebAppModule.java:284)', '\tTruncated. see log file for complete stacktrace', '>'])
    assert.equal(e.segments(l)[0].style.background, '#B71C1C', l);
  assert.equal(e.segments('Loading zip:/opt/x.jar')[0].style, null);
});

test('regra só de trecho pinta apenas o que casa e preserva o texto', () => {
  const e = new Engine(DEFAULT_RULES());
  const line = '<Notice> <WebLogicServer> <BEA-000365> <Server state changed to STANDBY.>';
  const s = e.segments(line);
  assert.equal(s.map((x: { text: string }) => x.text).join(''), line);
  const painted = s.filter((x: { style: unknown }) => x.style);
  assert.equal(painted.length, 1);
  assert.equal(painted[0].text, 'Server state changed to STANDBY');
  assert.equal(e.segments('<Notice> <BEA-000365> <Server state changed to RUNNING.>')[0].style.background, '#1B5E20'); // sucesso ganha
});

test('texto simples, maiúsculas/minúsculas e regex inválida ignorada', () => {
  const e = new Engine([
    { enabled: true, pattern: 'a.b', regex: false, caseSensitive: true, wholeLine: true, background: '#111111' },
    { enabled: true, pattern: '([', wholeLine: true, background: '#222222' },
    { enabled: false, pattern: 'off', wholeLine: true, background: '#333333' },
  ]);
  assert.ok(e.segments('x a.b y')[0].style);
  assert.equal(e.segments('x aXb y')[0].style, null);
  assert.equal(e.segments('A.B')[0].style, null);
  assert.equal(e.segments('off')[0].style, null);
});

test('modos: contém, começa, termina, igual, curinga e compatibilidade com regras antigas', () => {
  const hit = (mode: string, pattern: string, line: string) => !!new Engine([{ enabled: true, pattern, mode, wholeLine: true, background: '#111' }]).segments(line)[0].style;
  assert.equal(hit('contains', 'erro', 'um ERRO aqui'), true);
  assert.equal(hit('starts', '<Error>', '<Error> x'), true);
  assert.equal(hit('starts', '<Error>', 'x <Error>'), false);
  assert.equal(hit('ends', 'falhou', 'build falhou'), true);
  assert.equal(hit('ends', 'falhou', 'falhou depois'), false);
  assert.equal(hit('equals', '>', '>'), true);
  assert.equal(hit('equals', '>', '> x'), false);
  assert.equal(hit('contains', 'Module * of application', 'Module pem of application x'), true);
  assert.equal(hit('regex', '^\\d+$', '123'), true);
  const old = (regex: boolean) => !!new Engine([{ enabled: true, pattern: 'a.c', regex, wholeLine: true, background: '#1' }]).segments('abc')[0].style;
  assert.equal(old(true), true);
  assert.equal(old(false), false);
});
