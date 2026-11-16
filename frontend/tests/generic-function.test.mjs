import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {genericFunctionStarter,GENERIC_TYPES,validFunctionSignature} from '../src/lib/genericFunction.js';
import {starterCode,languageHelp} from '../src/lib/problemModel.js';
const languages=['PYTHON','C','CPP','JAVA','JAVASCRIPT','TYPESCRIPT','CSHARP','GO','RUST','KOTLIN','RUBY','LLVM_IR','PASCAL','PHP','DART'];
test('all six scalar/array return types have public starters in every generic FUNCTION language',()=>{
  for(const language of languages)for(const type of GENERIC_TYPES){
    const signature={name:'sol',parameters:[{name:'value',type}],returnType:type};
    const problem={execution_mode:'FUNCTION',functionAbi:'GENERIC_V1',functionSignature:signature};
    assert.equal(validFunctionSignature(signature),true);
    assert.ok(genericFunctionStarter(signature,language).includes('sol'),`${language} ${type}`);
    assert.equal(starterCode(problem,language),genericFunctionStarter(signature,language));
    assert.match(languageHelp(language,'FUNCTION',problem),/value:/);
    assert.doesNotMatch(starterCode(problem,language),/matrix|target/);
  }
});
test('argument count/name and solution entrypoint follow each public problem contract',()=>{
  const signature={name:'solution',parameters:[{name:'a',type:'int'},{name:'b',type:'int'}],returnType:'int'};
  assert.match(genericFunctionStarter(signature,'PYTHON'),/def solution\(a: int, b: int\)/);
  assert.match(genericFunctionStarter(signature,'CSHARP'),/static int solution\(int a, int b\)/);
  assert.match(genericFunctionStarter({...signature,parameters:[]},'C'),/solution\(void\)/);
  assert.equal(genericFunctionStarter(signature,'SQL'),'');
  assert.equal(genericFunctionStarter(signature,'UMJUNSIK'),'');
  assert.equal(genericFunctionStarter({...signature,name:'bad();'},'PYTHON'),'');
  assert.equal(genericFunctionStarter({...signature,parameters:[{name:'x;bad',type:'int'}]},'C'),'');
  for(const name of ['class','int','arg_length','result_length'])
    assert.equal(validFunctionSignature({...signature,parameters:[{name,type:'int'}]}),false);
});
test('all three generic signature boundaries reject the same cross-language reserved identifiers',()=>{
  const java=readFileSync(new URL('../../src/main/java/com/drewdrew0414/domain/problem/FunctionSignature.java',import.meta.url),'utf8');
  const python=readFileSync(new URL('../../judge/generic_function.py',import.meta.url),'utf8');
  const javascript=readFileSync(new URL('../src/lib/genericFunction.js',import.meta.url),'utf8');
  for(const key of ['RESERVED','PORTABLE_RESERVED']){
    const javaBlock=java.slice(java.indexOf(`Set<String> ${key}=`)).split(';')[0];
    const javaWords=[...javaBlock.matchAll(/"([^"]*)"/g)].flatMap(match=>match[1].split(/[\s,]+/)).filter(Boolean);
    const pythonWords=python.match(new RegExp(`${key} = frozenset\\('''([\\s\\S]*?)'''\\.split\\(\\)\\)`))?.[1].split(/\s+/).filter(Boolean);
    const javascriptWords=javascript.match(new RegExp(`const ${key}=new Set\\(\\('([^']*)'\\)\\.split`))?.[1].split(' ');
    assert.deepEqual([...new Set(javaWords)].sort(),[...new Set(pythonWords)].sort(),key+' backend/runtime parity');
    assert.deepEqual([...new Set(javaWords)].sort(),[...new Set(javascriptWords)].sort(),key+' backend/frontend parity');
    for(const name of javaWords)assert.equal(validFunctionSignature({name:'sol',parameters:[{name,type:'int'}],returnType:'int'}),false,name);
  }
});
