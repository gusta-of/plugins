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
