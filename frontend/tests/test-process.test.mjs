import test from 'node:test';
import assert from 'node:assert/strict';
import {runTestProcess} from '../scripts/test-process.mjs';

test('bounded runner accepts a successful isolated process', async () => {
  const result = await runTestProcess(['-e', 'process.exit(0)'], {output: null, timeoutMs: 1500});
  assert.equal(result.code, 0); assert.equal(result.reason, '');
});
test('external watchdog terminates even a blocked child event loop', async () => {
  const started = Date.now();
  const result = await runTestProcess(['-e', 'while(true){}'], {output: null, timeoutMs: 400});
  assert.equal(result.code, 1); assert.match(result.reason, /시간 제한/);
  assert.ok(Date.now() - started < 3000);
});
test('RSS watchdog stops bounded native allocation independently of V8 heap', async () => {
  // 검증 자체는 단 한 번 64MiB만 할당합니다. 폭증 상황을 재실행하지 않습니다.
  const result = await runTestProcess(['-e', 'globalThis.fixture=Buffer.alloc(64*1024*1024,1);setInterval(()=>{},1000)'],
    {output: null, timeoutMs: 2000, maxRssKiB: 48 * 1024});
  assert.equal(result.code, 1); assert.match(result.reason, /메모리 제한/);
  assert.ok(result.peakRssKiB >= 48 * 1024);
});
test('output watchdog terminates excessive output without accumulating buffers', async () => {
  const result = await runTestProcess(['-e', 'process.stdout.write("x".repeat(65536));setInterval(()=>{},1000)'],
    {output: null, timeoutMs: 1500, maxOutputBytes: 1024});
  assert.equal(result.code, 1); assert.match(result.reason, /출력 제한/);
});
