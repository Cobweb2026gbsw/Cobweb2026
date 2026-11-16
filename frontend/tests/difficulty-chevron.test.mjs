import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

test('difficulty menu draws one geometric chevron instead of overlapping select and font arrows',()=>{
  const css=readFileSync(new URL('../src/pages/ProblemList.css',import.meta.url),'utf8');
  const summary=css.match(/\.pl-difficulty summary\s*\{([^}]+)\}/)?.[1]||'';
  assert.match(summary,/background-image:\s*none/);
  assert.match(css,/summary::after\s*\{[^}]*content:\s*''/);
  assert.match(css,/summary::marker\s*\{\s*content:\s*''/);
  assert.match(css,/summary::-webkit-details-marker\s*\{\s*display:\s*none/);
  assert.doesNotMatch(css,/content:\s*['"][⌄⌃vV]/);
});
