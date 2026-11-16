import test from 'node:test';
import assert from 'node:assert/strict';
import {JSDOM} from 'jsdom';
import {LAST_LANGUAGE_KEY,preferredLanguage,rememberLanguage} from '../src/lib/editorLanguagePreference.js';

const dom=new JSDOM('',{url:'http://localhost/'});globalThis.window=dom.window;
test.beforeEach(()=>window.localStorage.clear());test.after(()=>dom.window.close());
test('last explicit language is restored only when the next problem allows it',()=>{
  rememberLanguage('RUST');assert.equal(window.localStorage.getItem(LAST_LANGUAGE_KEY),'RUST');
  assert.equal(preferredLanguage(['PYTHON','RUST']),'RUST');assert.equal(preferredLanguage(['PYTHON']),'PYTHON');
  assert.equal(window.localStorage.getItem(LAST_LANGUAGE_KEY),'RUST');assert.equal(preferredLanguage(['RUST','JAVA']),'RUST');
});
test('an existing per-problem selection wins and no source or identity is cached',()=>{
  rememberLanguage('JAVA');assert.equal(preferredLanguage(['PYTHON','JAVA'],'PYTHON'),'PYTHON');
  rememberLanguage('private source code');assert.equal(window.localStorage.getItem(LAST_LANGUAGE_KEY),'JAVA');
  assert.deepEqual(Object.keys(window.localStorage),[LAST_LANGUAGE_KEY]);assert.equal(preferredLanguage([]),'');
});
test('invalid cached IDs and disabled storage safely fall back',()=>{
  window.localStorage.setItem(LAST_LANGUAGE_KEY,'<script>not a language</script>');assert.equal(preferredLanguage(['PYTHON','C']),'PYTHON');
  const savedWindow=globalThis.window;globalThis.window={localStorage:{getItem(){throw Error('blocked');},setItem(){throw Error('blocked');}}};
  try{assert.equal(preferredLanguage(['PHP']),'PHP');assert.doesNotThrow(()=>rememberLanguage('PHP'));}finally{globalThis.window=savedWindow;}
});
