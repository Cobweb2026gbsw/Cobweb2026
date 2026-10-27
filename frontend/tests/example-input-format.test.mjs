import test,{after} from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {build} from 'esbuild';
import {JSDOM} from 'jsdom';
import {formatFunctionExampleInput} from '../src/lib/exampleInputFormat.js';

const problem=(parameters,extra={})=>({execution_mode:'FUNCTION',functionAbi:'GENERIC_V1',functionSignature:{name:'sol',parameters,returnType:'int'},...extra});
const one=type=>problem([{name:'value',type}]);

test('a scalar example shows only the typed argument value, not the args envelope',()=>{
  assert.equal(formatFunctionExampleInput(one('int'),'{"args":[1]}'),'1');
  assert.equal(formatFunctionExampleInput(one('int'),'{"args":[-2147483648]}'),'-2147483648');
  assert.equal(formatFunctionExampleInput(one('float'),'{"args":[1.25]}'),'1.25');
  assert.equal(formatFunctionExampleInput(one('bool'),'{"args":[true]}'),'true');
  assert.equal(formatFunctionExampleInput(one('string'),'{"args":["a b\\n"]}'),'"a b\\n"');
});

test('arrays remain a single value while multiple parameters receive their public names',()=>{
  assert.equal(formatFunctionExampleInput(one('int[]'),'{"args":[[1,2]]}'),'[1,2]');
  assert.equal(formatFunctionExampleInput(one('string[]'),'{"args":[["a","b"]]}'),'["a","b"]');
  assert.equal(formatFunctionExampleInput(one('int[]'),'{"args":[[]]}'),'[]');
  assert.equal(formatFunctionExampleInput(problem([{name:'numbers',type:'int[]'},{name:'text',type:'string'},{name:'enabled',type:'bool'}]),'{"args":[[1,2],"a b",false]}'),'numbers: [1,2]\ntext: "a b"\nenabled: false');
  assert.equal(formatFunctionExampleInput(problem([]),'{"args":[]}'),'인자 없음');
});

test('ordinary input, legacy ABI, absent or invalid signatures are never guessed',()=>{
  for(const source of [null,{},problem([], {execution_mode:'STDIN'}),problem([], {execution_mode:'SQL'}),problem([], {functionAbi:'CHAIN_LASER_V1'}),problem([], {functionSignature:null}),problem([{name:'invalid;name',type:'int'}])])
    assert.equal(formatFunctionExampleInput(source,'{"args":[1]}'),null);
  assert.equal(formatFunctionExampleInput({...one('int'),execution_mode:undefined,executionMode:'FUNCTION'},'{"args":[1]}'),'1');
});

test('malformed, mismatched and oversized values keep their original presentation',()=>{
  for(const input of ['1','null','[]','{"args":[1]} trailing','{"args":[1],"other":2}','{"args":1}','{"args":[]}','{"args":[1,2]}','{"args":[true]}','{"args":[1.2]}','{"args":[2147483648]}','{"args":[null]}'])
    assert.equal(formatFunctionExampleInput(one('int'),input),null,input);
  assert.equal(formatFunctionExampleInput(one('string'),JSON.stringify({args:['가']})),null);
  assert.equal(formatFunctionExampleInput(one('string'),JSON.stringify({args:['a'.repeat(16385)]})),null);
  assert.equal(formatFunctionExampleInput(one('int[]'),JSON.stringify({args:[Array(4097).fill(1)]})),JSON.stringify(Array(4097).fill(1)));
  assert.equal(formatFunctionExampleInput(one('int[][]'),'{"args":[[[1],[],[2,3]]]}'),'[[1],[],[2,3]]');
  assert.equal(formatFunctionExampleInput(one('string[][]'),'{"args":[[["a"],[],["b","c"]]]}'),'[["a"],[],["b","c"]]');
  assert.equal(formatFunctionExampleInput(one('string'),JSON.stringify({args:['a'.repeat(65536)]})),null);
  assert.equal(formatFunctionExampleInput(one('float'),'{"args":[1e400]}'),null);
});

const require=createRequire(import.meta.url),root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const dom=new JSDOM('<!doctype html><html><body></body></html>',{url:'http://localhost:8080/'});
Object.defineProperty(globalThis,'navigator',{value:dom.window.navigator,configurable:true});
for(const key of ['window','document','HTMLElement','Event'])globalThis[key]=dom.window[key];
globalThis.IS_REACT_ACT_ENVIRONMENT=true;
const React=require('react'),{render,cleanup}=require('@testing-library/react');
window.CobwebMarkdown=require('../../src/main/resources/static/markdown.js');
const bundle=await build({entryPoints:[path.join(root,'src/components/ProblemStatement.jsx')],bundle:true,write:false,platform:'node',format:'cjs',packages:'external',jsx:'automatic',loader:{'.css':'empty'}});
const module={exports:{}};new Function('require','module','exports',bundle.outputFiles[0].text)(require,module,module.exports);
const Statement=module.exports.default;
after(()=>{cleanup();dom.window.close();});

test('the shared problem and contest statement changes only example input without mutating API data',()=>{
  const original={...one('int'),time_limit_ms:2000,memory_limit_mb:256,examples:[{input_text:'{"args":[1]}',output_text:'2',explanation:'설명 유지'}]};
  const before=JSON.stringify(original),view=render(React.createElement(Statement,{problem:original}));
  const example=view.container.querySelector('.cw-example');
  assert.ok(example);assert.equal(example.querySelector('pre').textContent,'1');
  assert.equal(example.querySelectorAll('pre')[1].textContent,'2');
  assert.match(example.textContent,/설명 유지/);assert.doesNotMatch(example.textContent,/args/);
  assert.match(view.container.textContent,/함수에 전달할 인자 값/);assert.equal(JSON.stringify(original),before);cleanup();
});

test('string arguments render as literal text and output or malformed examples stay untouched',()=>{
  const malicious='<img src=x onerror=alert(1)><script>alert(1)</script>';
  const original={...one('string'),examples:[{input:JSON.stringify({args:[malicious]}),output:'{"args":["unchanged-output"]}'}]};
  const view=render(React.createElement(Statement,{problem:original}));
  assert.equal(view.container.querySelector('img,script'),null);
  assert.equal(view.container.querySelector('pre').textContent,JSON.stringify(malicious));
  assert.equal(view.container.querySelectorAll('pre')[1].textContent,'{"args":["unchanged-output"]}');
  const invalid={...one('int'),examples:[{input:'{"args":["not-an-int"]}',output:'0'}]};
  view.rerender(React.createElement(Statement,{problem:invalid}));
  assert.equal(view.container.querySelector('pre').textContent,'{"args":["not-an-int"]}');
  assert.doesNotMatch(view.container.textContent,/함수에 전달할 인자 값/);cleanup();
});
