import test from 'node:test';
import assert from 'node:assert/strict';
import {draftOwner,draftContext,loadProblemDrafts,saveProblemDraft,removeProblemDraft,DRAFT_CACHE_KEY,DRAFT_TTL_MS,MAX_DRAFTS,MAX_CACHE_BYTES} from '../src/lib/problemDraftCache.js';

class MemoryStorage{
  values=new Map();
  getItem(key){return this.values.get(key)??null;}
  setItem(key,value){this.values.set(key,String(value));}
  removeItem(key){this.values.delete(key);}
}
const now=1800000000000,context='["typed public problem","FUNCTION","GENERIC_V1",null]';
const scope=(owner='user:1',number=1000,version=context)=>({owner,number,context:version});
let storage;
test.beforeEach(()=>{storage=new MemoryStorage();globalThis.window={localStorage:storage};});
test.after(()=>{delete globalThis.window;});

test('only stable positive server account IDs or an explicit anonymous identity select a namespace',()=>{
  assert.equal(draftOwner({id:1,username:'alice'}),'user:1');
  assert.equal(draftOwner({id:'9223372036854775807'}),'user:9223372036854775807');
  assert.equal(draftOwner(null),'guest');
  for(const user of [undefined,{}, {username:'alice'},{id:0},{id:-1},{id:'01'},{id:'1.1'},{id:'user:1'},{id:Number.MAX_SAFE_INTEGER+1},{id:'x'.repeat(100)}])assert.equal(draftOwner(user),'');
});

test('cache context uses public entrypoint identity but never stores statement, tests, references or credentials',()=>{
  const problem={problem_title:'typed',execution_mode:'FUNCTION',functionAbi:'GENERIC_V1',functionSignature:{name:'sol',parameters:[],returnType:'int'},description:'private-statement',tests:['private-test'],referenceSolutions:['private-reference'],accessToken:'secret-token'};
  const current={...scope(),context:draftContext(problem)};
  assert.equal(saveProblemDraft(current,'PYTHON','return 1',now).ok,true);
  const raw=storage.getItem(DRAFT_CACHE_KEY);
  assert.doesNotMatch(raw,/private-statement|private-test|private-reference|secret-token/);
  assert.deepEqual(Object.keys(JSON.parse(raw).entries[0]).sort(),['code','context','language','number','owner','savedAt']);
  assert.equal(draftContext(null),'');
});

test('draft values are isolated by account, problem, language, and public entrypoint context',()=>{
  for(const [current,language,code] of [[scope(),'PYTHON','alice-py'],[scope(),'JAVA','alice-java'],[scope('user:2'),'PYTHON','bob-py'],[scope('guest'),'PYTHON','guest-py'],[scope('user:1',1001),'PYTHON','other-problem']])assert.equal(saveProblemDraft(current,language,code,now).ok,true);
  assert.deepEqual(loadProblemDrafts(scope(),['PYTHON','JAVA'],now).codes,{PYTHON:'alice-py',JAVA:'alice-java'});
  assert.deepEqual(loadProblemDrafts(scope('user:2'),['PYTHON'],now).codes,{PYTHON:'bob-py'});
  assert.deepEqual(loadProblemDrafts(scope('guest'),['PYTHON'],now).codes,{PYTHON:'guest-py'});
  assert.deepEqual(loadProblemDrafts(scope('user:1',1001),['PYTHON'],now).codes,{PYTHON:'other-problem'});
  assert.deepEqual(loadProblemDrafts(scope('user:1',1000,'new-signature'),['PYTHON'],now).codes,{});
  assert.deepEqual(loadProblemDrafts(scope(),['RUST'],now).codes,{});
});

test('empty strings are intentional drafts and reset removes only the requested owner/problem/language',()=>{
  saveProblemDraft(scope(),'PYTHON','',now);saveProblemDraft(scope(),'JAVA','java',now);saveProblemDraft(scope('user:2'),'PYTHON','bob',now);
  assert.deepEqual(loadProblemDrafts(scope(),['PYTHON','JAVA'],now).codes,{PYTHON:'',JAVA:'java'});
  assert.equal(removeProblemDraft(scope(),'PYTHON',now),true);
  assert.deepEqual(loadProblemDrafts(scope(),['PYTHON','JAVA'],now).codes,{JAVA:'java'});
  assert.deepEqual(loadProblemDrafts(scope('user:2'),['PYTHON'],now).codes,{PYTHON:'bob'});
});

test('a 64 KiB UTF-8 draft is accepted while larger edits preserve the last valid saved value',()=>{
  const exact='가'.repeat(21845)+'a';assert.equal(new TextEncoder().encode(exact).length,65536);
  assert.equal(saveProblemDraft(scope(),'PYTHON',exact,now).ok,true);
  assert.deepEqual(saveProblemDraft(scope(),'PYTHON',exact+'b',now),{ok:false,reason:'size'});
  assert.equal(loadProblemDrafts(scope(),['PYTHON'],now).codes.PYTHON,exact);
  assert.deepEqual(saveProblemDraft(scope(),'PYTHON','a'.repeat(65537),now),{ok:false,reason:'size'});
});

test('drafts expire after 30 days, future timestamps and malformed entries are not restored',()=>{
  const entry={...scope(),language:'PYTHON',code:'old',savedAt:now-DRAFT_TTL_MS};
  storage.setItem('other-app-key','untouched');
  storage.setItem(DRAFT_CACHE_KEY,JSON.stringify({version:1,entries:[entry,{...entry,savedAt:now+1},{...entry,savedAt:now,language:'__proto__'},{...entry,savedAt:now,owner:'invalid'},{...entry,savedAt:now,code:[]},{...entry,savedAt:now,code:'a'.repeat(65537)}]}));
  assert.deepEqual(loadProblemDrafts(scope(),['PYTHON'],now),{codes:{},available:true});
  assert.equal(storage.getItem(DRAFT_CACHE_KEY),null);assert.equal(storage.getItem('other-app-key'),'untouched');
  saveProblemDraft(scope(),'PYTHON','fresh',now-DRAFT_TTL_MS+1);
  assert.equal(loadProblemDrafts(scope(),['PYTHON'],now).codes.PYTHON,'fresh');
});

test('recent drafts have an entry-count limit and updating a draft makes it the newest',()=>{
  for(let index=0;index<MAX_DRAFTS+1;index++)saveProblemDraft(scope('user:1',1000+index),'PYTHON','code-'+index,now+index);
  assert.equal(JSON.parse(storage.getItem(DRAFT_CACHE_KEY)).entries.length,MAX_DRAFTS);
  assert.deepEqual(loadProblemDrafts(scope(),['PYTHON'],now+MAX_DRAFTS).codes,{});
  saveProblemDraft(scope('user:1',1001),'PYTHON','updated',now+MAX_DRAFTS+1);
  const entries=JSON.parse(storage.getItem(DRAFT_CACHE_KEY)).entries;
  assert.equal(entries.length,MAX_DRAFTS);assert.equal(entries.at(-1).number,1001);assert.equal(entries.at(-1).code,'updated');
});

test('serialized cache never exceeds 2 MiB and evicts oldest entries only from its own key',()=>{
  storage.setItem('theme','dark');
  for(let index=0;index<MAX_DRAFTS;index++)saveProblemDraft(scope('user:1',1000+index),'PYTHON','x'.repeat(65536),now+index);
  const raw=storage.getItem(DRAFT_CACHE_KEY),entries=JSON.parse(raw).entries;
  assert.ok(new TextEncoder().encode(raw).length<=MAX_CACHE_BYTES);
  assert.ok(entries.length<MAX_DRAFTS);assert.equal(entries.at(-1).number,1049);assert.equal(storage.getItem('theme'),'dark');
});

test('corrupt, unrecognized and oversized cache data falls back without throwing or touching other keys',()=>{
  for(const raw of ['{broken','null','[]','{"version":2,"entries":[]}','x'.repeat(MAX_CACHE_BYTES+1)]){
    storage.setItem(DRAFT_CACHE_KEY,raw);storage.setItem('unrelated','keep');
    assert.deepEqual(loadProblemDrafts(scope(),['PYTHON'],now),{codes:{},available:true});
    assert.equal(storage.getItem('unrelated'),'keep');assert.equal(storage.getItem(DRAFT_CACHE_KEY),null);
  }
});

test('blocked storage access, quota exhaustion and invalid scopes fail closed without exceptions',()=>{
  const blocked=()=>{throw new Error('private-storage-error');};
  Object.defineProperty(window,'localStorage',{get:blocked,configurable:true});
  assert.deepEqual(loadProblemDrafts(scope(),['PYTHON'],now),{codes:{},available:false});
  assert.deepEqual(saveProblemDraft(scope(),'PYTHON','code',now),{ok:false,reason:'storage'});assert.equal(removeProblemDraft(scope(),'PYTHON',now),false);
  Object.defineProperty(window,'localStorage',{value:{getItem:()=>null,setItem:blocked,removeItem:blocked},configurable:true});
  assert.deepEqual(loadProblemDrafts(scope(),['PYTHON'],now),{codes:{},available:false});
  assert.deepEqual(saveProblemDraft(scope(),'PYTHON','code',now),{ok:false,reason:'storage'});
  for(const current of [null,{},scope('user:0'),scope('guest',999),scope('guest',1.2),scope('guest',1000,'x'.repeat(4097))]){
    assert.deepEqual(loadProblemDrafts(current,['PYTHON'],now),{codes:{},available:false});
    assert.deepEqual(saveProblemDraft(current,'PYTHON','code',now),{ok:false,reason:'invalid'});
  }
});
