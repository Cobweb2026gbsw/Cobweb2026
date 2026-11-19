import test from 'node:test';
import assert from 'node:assert/strict';
import {problemSearchTarget,problemListUrl,problemTrail,problemUrl,rankProblemsUrl,recentProblem,rememberProblem} from '../src/lib/problemNavigation.js';

test('search trims input, normalizes numbers, and safely encodes titles',()=>{
  assert.equal(problemSearchTarget(' 001000 '),'/problems/1000');
  assert.equal(problemSearchTarget(' '),'/problems');
  assert.equal(problemSearchTarget('a&b'),'/problems?q=a%26b');
  assert.match(problemSearchTarget('9007199254740993'),/^\/problems\?q=/);
});
test('rank links cover every level independently of result pagination',()=>{
  const url=new URL(rankProblemsUrl('INITIATE'),'http://localhost');
  assert.deepEqual(url.searchParams.getAll('difficulty'),['INITIATE:5','INITIATE:4','INITIATE:3','INITIATE:2','INITIATE:1']);
  assert.equal(rankProblemsUrl('invalid'),'/problems');
});
test('return links reject external, escaped and non-list destinations',()=>{
  for(const url of ['https://example.com','//example.com','/\\example.com','/problems/1000','/problems\n','/login',null])assert.equal(problemListUrl(url),'/problems');
  assert.equal(problemListUrl('/problems?q=graph&page=3'),'/problems?q=graph&page=3');
});
test('next problem follows the current result order and retains the list context',()=>{
  const from='/problems?q=graph&page=3&sort=submits';
  const location={search:new URL(problemUrl(1080,from),'http://localhost').search,state:{problemTrail:{from,items:[1081,1080,1082]}}};
  const next=problemTrail(location,1080);assert.equal(next.next,1082);assert.equal(next.from,from);
  assert.equal(problemTrail(location,1082).next,undefined);
  assert.equal(problemTrail({...location,state:{problemTrail:{from:'/problems',items:[1080,9999]}}},1080).next,undefined);
});
test('recent problem is scoped by account and survives unavailable storage',()=>{
  const entries=new Map();globalThis.window={localStorage:{getItem:key=>entries.get(key),setItem:(key,value)=>entries.set(key,value)}};
  rememberProblem('user:1',1000,'/problems?q=tree');
  assert.equal(recentProblem('user:1').number,1000);assert.equal(recentProblem('user:2'),null);assert.equal(recentProblem('guest'),null);
  assert.equal(recentProblem('user:1').url,problemUrl(1000,'/problems?q=tree'));
  rememberProblem('user:1',1019,null);
  assert.equal(recentProblem('user:1').url,'/problems/1019');
  assert.equal(problemTrail({search:'',state:{problemTrail:{from:'/problems',items:[1019,1020]}}},1019).next,undefined);
  window.localStorage.getItem=()=>{throw new Error('storage unavailable');};
  assert.equal(recentProblem('user:1'),null);delete globalThis.window;
});
