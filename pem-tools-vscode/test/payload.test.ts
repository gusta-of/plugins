import { test } from 'node:test';
import assert from 'node:assert/strict';
import { prettyJson, prettyXml, formatPayload } from '../src/payload';
// eslint-disable-next-line @typescript-eslint/no-require-imports
const { PayloadDetector } = require('../media/payload.js');

const feed = (lines: string[]) => {
  const d = new PayloadDetector();
  const out: { ref: number; kind: string; text: string }[] = [];
  lines.forEach((l, i) => out.push(...d.push(l, i)));
  return out;
};

test('JSON numa linha, com prefixo do log', () => {
  const r = feed(['2026-10-30 INFO c.x.Client - Request: {"id": 7, "nome": "a}b", "itens": [1,2]} fim']);
  assert.equal(r.length, 1);
  assert.equal(r[0].kind, 'json');
  assert.equal(r[0].text, '{"id": 7, "nome": "a}b", "itens": [1,2]}');
  assert.equal(r[0].ref, 0);
});

test('não confunde [INFO], {0} nem {} vazio com JSON', () => {
  assert.equal(feed(['[INFO] tudo bem', 'Mensagem {0} e {1}', 'vazio {} e []', '[ERROR] algo (x)']).length, 0);
});

test('JSON em várias linhas (pretty-printed) com botão na primeira linha', () => {
  const r = feed(['<Info> payload: {', '  "a": 1,', '  "b": { "c": "}" },', '  "d": [1, 2]', '} depois', 'linha normal']);
  assert.equal(r.length, 1);
  assert.equal(r[0].ref, 0);
  assert.equal(JSON.parse(r[0].text).b.c, '}');
});

test('SOAP numa linha e em várias linhas (qualquer prefixo)', () => {
  const one = feed(['x <soapenv:Envelope xmlns:soapenv="a"><soapenv:Body><ns:getX>1</ns:getX></soapenv:Body></soapenv:Envelope> y']);
  assert.equal(one.length, 1);
  assert.equal(one[0].kind, 'xml');
  assert.ok(one[0].text.startsWith('<soapenv:Envelope') && one[0].text.endsWith('</soapenv:Envelope>'));
  const multi = feed(['Outbound Message', '<S:Envelope xmlns:S="a">', ' <S:Body>', '  <getXResponse>ok</getXResponse>', ' </S:Body>', '</S:Envelope> fim', 'proxima']);
  assert.equal(multi.length, 1);
  assert.equal(multi[0].ref, 1);
  assert.ok(multi[0].text.endsWith('</S:Envelope>'));
  assert.equal(feed(['<Envelope><a/></Envelope>']).length, 1); // sem prefixo
});

test('um bloco que nunca fecha não rebenta nem apanha as linhas seguintes como payload', () => {
  const lines = ['x {"a": ['];
  for (let i = 0; i < 700; i++) lines.push(`linha ${i}`);
  assert.equal(feed(lines).length, 0);
});

test('formatação: JSON indentado e XML indentado com texto inline', () => {
  assert.equal(prettyJson('{"a":1,"b":[1,2]}'), '{\n  "a": 1,\n  "b": [\n    1,\n    2\n  ]\n}');
  const x = prettyXml('<s:Envelope xmlns:s="a"><s:Body><getX><id>1</id><vazio></vazio><x/></getX></s:Body></s:Envelope>');
  assert.equal(x, ['<s:Envelope xmlns:s="a">', '  <s:Body>', '    <getX>', '      <id>1</id>', '      <vazio></vazio>', '      <x/>', '    </getX>', '  </s:Body>', '</s:Envelope>'].join('\n'));
  assert.equal(formatPayload('json', 'não é json'), 'não é json');
});

test('os formatadores do webview (JS) dão o mesmo resultado que os do servidor (TS)', () => {
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const js = require('../media/payload.js');
  const xml = '<s:Envelope xmlns:s="a"><s:Body><getX><id>1</id><vazio></vazio><x/><!-- c --><t>a &amp; b</t></getX></s:Body></s:Envelope>';
  assert.equal(js.prettyXml(xml), prettyXml(xml));
  assert.equal(js.prettyJson('{"a":1,"b":[1,{"c":null}]}'), prettyJson('{"a":1,"b":[1,{"c":null}]}'));
  assert.equal(js.format('xml', '<a><b>1</b></a>'), '<a>\n  <b>1</b>\n</a>');
});

test('regiões colapsáveis: blocos JSON e XML formatados, com a linha de fecho incluída', () => {
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const js = require('../media/payload.js');
  const j = prettyJson('{"a":1,"b":{"c":[1,2],"d":{}},"e":"x"}').split('\n');
  const ends = js.foldRegions(j);
  // 0:{ 1:"a" 2:"b": { 3:"c": [ 4:1, 5:2 6:], 7:"d": {}, 8:}, 9:"e" 10:}
  assert.equal(ends[0], 10);
  assert.equal(ends[1], -1);
  assert.equal(ends[2], 8);
  assert.equal(ends[3], 6);
  assert.equal(ends[7], -1);
  assert.equal(js.foldTail(j, 3, 6), '],');
  assert.equal(js.foldTail(j, 2, 8), '},');
  const x = prettyXml('<s:Envelope><s:Body><a><b>1</b></a></s:Body></s:Envelope>').split('\n');
  const xe = js.foldRegions(x);
  assert.equal(xe[0], 6);
  assert.equal(xe[1], 5);
  assert.equal(xe[2], 4);
  assert.equal(xe[3], -1);
  assert.equal(js.foldTail(x, 0, 6), '</s:Envelope>');
  assert.equal(js.foldTail(['a', '  b'], 0, 1), '(1 linhas)');
});
