import test from 'node:test';
import assert from 'node:assert/strict';
import {serverDate, formatServerDate} from '../src/lib/date.js';
test('offset-less JDBC UTC timestamps and explicit offsets identify the same instant', () => {
  const expected = '2026-10-21T12:44:03.123Z';
  for (const value of ['2026-10-21T12:44:03.123456','2026-10-21 12:44:03.123','2026-10-21T12:44:03.123Z','2026-10-21T21:44:03.123+09:00'])
    assert.equal(serverDate(value).toISOString(), expected);
  const options = {timeZone:'Asia/Seoul', hour12:false};
  assert.equal(formatServerDate('2026-10-21T12:44:03', options), new Date('2026-10-21T21:44:03+09:00').toLocaleString('ko-KR', options));
});
test('absent and invalid server dates do not invent a timestamp', () => {
  assert.equal(serverDate(null), null); assert.equal(formatServerDate('not-a-date'), '—');
});
