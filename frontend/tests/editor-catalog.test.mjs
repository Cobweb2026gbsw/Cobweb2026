import test from 'node:test';
import assert from 'node:assert/strict';
import {EDITOR_CATALOG,LANGUAGE_IDS} from '../src/lib/editorCatalog.js';

test('all 17 supported languages have keyword, syntax snippet and local completion vocabulary',()=>{
  assert.equal(LANGUAGE_IDS.length,17);
  for(const language of LANGUAGE_IDS){
    const entry=EDITOR_CATALOG[language];
    assert.ok(entry.keywords.length>=10,language+' keywords');
    assert.ok(entry.snippets.length>=2,language+' snippets');
    assert.equal(new Set(entry.keywords).size,entry.keywords.length,language+' unique keywords');
    assert.ok(entry.snippets.every(item=>item.label&&item.template),language+' valid snippets');
  }
});
test('less common languages include actual SQLite, LLVM IR, Pascal and umjunsik vocabulary',()=>{
  for(const [language,keyword] of [['SQL','SELECT'],['SQL','WINDOW'],['LLVM_IR','getelementptr'],['LLVM_IR','phi'],['PASCAL','begin'],['PHP','function'],['DART','mixin'],['UMJUNSIK','동탄'],['UMJUNSIK','화이팅!']])
    assert.equal(EDITOR_CATALOG[language].keywords.includes(keyword),true,language+' '+keyword);
});
