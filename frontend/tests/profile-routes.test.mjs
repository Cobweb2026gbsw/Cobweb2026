import test from 'node:test';
import assert from 'node:assert/strict';
import {profileHref, publicProfileApi} from '../src/lib/profileRoutes.js';

test('profile names remain query values rather than normalized path segments', () => {
  for (const username of ['..', 'a/b', '기존사용자', 'a+b &?#', '.', 'plain-user']) {
    for (const [value, pathname] of [[profileHref(username), '/users'], [publicProfileApi(username), '/api/profiles']]) {
      const url = new URL(value, 'https://cobweb.example');
      assert.equal(url.origin, 'https://cobweb.example');
      assert.equal(url.pathname, pathname);
      assert.equal(url.searchParams.get('username'), username);
      assert.equal([...url.searchParams].length, 1);
      assert.equal(url.hash, '');
    }
  }
});
