import test from 'node:test';
import assert from 'node:assert/strict';
import { fileDisplayName, inferredFileName, smtpPortForSecurity } from '../apps/web/src/platform/forms/inferred-defaults.ts';

test('file suggestions sanitize only the basename and preserve localized names', () => {
  assert.equal(fileDisplayName('C:\\folder\\ 报表.v2.xlsx',120),'报表.v2');
  assert.equal(fileDisplayName('/tmp/hello\u0000.docx',120),'hello');
  assert.equal(fileDisplayName('abcdef.xlsx',3),'abc');
});
test('file replacement follows only an untouched inferred name; clearing is an edit', () => {
  assert.equal(inferredFileName('',null,false,'A.xlsx',120),'A');
  assert.equal(inferredFileName('A','A',false,'B.xlsx',120),'B');
  assert.equal(inferredFileName('Custom','A',false,'B.xlsx',120),null);
  assert.equal(inferredFileName('','A',true,'B.xlsx',120),null);
  assert.equal(inferredFileName('A','A',true,'B.xlsx',120),null);
  assert.equal(inferredFileName('',null,false,'.xlsx',120),null);
});
test('SMTP defaults follow explicit security selection without overwriting custom ports', () => {
  assert.equal(smtpPortForSecurity('STARTTLS','TLS',587,false),465);
  assert.equal(smtpPortForSecurity('TLS','STARTTLS',465,false),587);
  assert.equal(smtpPortForSecurity('STARTTLS','TLS',2525,false),2525);
  assert.equal(smtpPortForSecurity('STARTTLS','TLS',587,true),587);
  assert.equal(smtpPortForSecurity('STARTTLS','TLS',null,true),null);
  assert.equal(smtpPortForSecurity('STARTTLS','TLS',null,false),465);
});

const { reloadDraft } = await import('../apps/web/src/platform/forms/reload-draft.ts');
test('explicit reload resets only after a successful current-session read', async () => {
  const controller = new AbortController(); let resets=0;
  await reloadDraft(controller.signal, async()=>({data:{version:2}}),()=>resets++);
  assert.equal(resets,1);
  await assert.rejects(reloadDraft(controller.signal,async()=>{throw Error('read failed');},()=>resets++));
  assert.equal(resets,1);
  await reloadDraft(controller.signal,async()=>{controller.abort();return {data:{version:3}};},()=>resets++);
  assert.equal(resets,1);
});
