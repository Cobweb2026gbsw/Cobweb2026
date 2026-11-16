import test from 'node:test';
import assert from 'node:assert/strict';
import {emptyProblem,problemPayload} from '../src/lib/problemModel.js';

test('generic authoring accepts a large typed case while ordinary authoring keeps its 2 MiB bound',()=>{
  const input=JSON.stringify({args:[Array(250000).fill(1234567890)]});
  const form={...emptyProblem(),title:'대용량 배열',description:'배열 검사',executionMode:'FUNCTION',
    functionSignature:{name:'sol',parameters:[{name:'numbers',type:'int[]'}],returnType:'bool'},
    languages:['PYTHON'],tests:[{input,expected:'true'}]};
  const catalog=[{id:'PYTHON',modes:['STDIN','FUNCTION']}];
  assert.equal(problemPayload(form,catalog).tests[0].input,input);
  assert.throws(()=>problemPayload({...form,executionMode:'STDIN'},catalog),/2 MiB/);
});
