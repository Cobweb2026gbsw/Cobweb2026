import test from 'node:test';
import assert from 'node:assert/strict';
import {groupListState, groupListUrl, groupDetailUrl, groupReturnUrl, rememberGroupPosition, groupPosition} from '../src/lib/groupNavigation.js';

test('group return URLs keep only bounded list filters and never accept another destination', () => {
  const from = '/groups?tab=mine&q=' + encodeURIComponent('같이 공부') + '&page=2';
  assert.deepEqual(groupListState('?q=abc&page=-1&tab=unknown'), {tab: 'explore', search: 'abc', page: 0});
  assert.equal(groupListState('?page=10001').page, 0);
  assert.equal(groupListState('?q=' + 'a'.repeat(120)).search.length, 100);
  const destination = new URL(groupDetailUrl(7, from), 'http://localhost');
  assert.equal(groupReturnUrl(destination.search), groupListUrl(from));
  for (const invalid of ['//evil.test/groups', '/groups/7', '/groups/../admin', '/groups#other', '/groups?x=\n', 'https://evil.test/groups']) assert.equal(groupListUrl(invalid), '/groups');
  assert.equal(groupListUrl('/groups?redirect=/admin&page=2'), '/groups?page=2');
});

test('scroll positions are bounded and isolated by account and list filters', () => {
  const values = new Map();
  globalThis.sessionStorage = {getItem: key => values.get(key), setItem: (key, value) => values.set(key, value)};
  try {
    rememberGroupPosition('alice', '/groups?q=study&page=2', 480);
    assert.equal(groupPosition('alice', '/groups?q=study&page=2'), 480);
    assert.equal(groupPosition('bob', '/groups?q=study&page=2'), 0);
    assert.equal(groupPosition('alice', '/groups'), 0);
    for (let page = 0; page < 30; page++) rememberGroupPosition('alice', '/groups?page=' + page, page * 10);
    assert.equal(JSON.parse([...values.values()][0]).length, 20);
    values.set('cobweb.group-positions.v1:alice', 'invalid');
    assert.equal(groupPosition('alice', '/groups'), 0);
  } finally {delete globalThis.sessionStorage;}
});
