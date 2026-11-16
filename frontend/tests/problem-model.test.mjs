import test from 'node:test';
import assert from 'node:assert/strict';
import {FILE_NAMES,FUNCTION_SIGNATURES,STATUS,emptyProblem,problemPayload,readSourceFile,sourceBytes,starterCode,languageHelp} from '../src/lib/problemModel.js';
const catalog=Object.keys(FILE_NAMES).map(id=>({id,modes:id==='SQL'?['SQL']:id==='UMJUNSIK'?['STDIN']:['STDIN','FUNCTION']}));
const draft=()=>({...emptyProblem(),title:'  테스트 문제  ',description:'설명',languages:catalog.filter(l=>l.modes.includes('STDIN')).map(l=>l.id),tests:[{input:'',expected:''}]});

test('all seventeen fixed filenames and fifteen FUNCTION signatures match real modes',()=>{
  assert.equal(Object.keys(FILE_NAMES).length,17);assert.equal(Object.keys(FUNCTION_SIGNATURES).length,15);assert.equal(new Set(Object.values(FILE_NAMES)).size,17);
  assert.equal(FUNCTION_SIGNATURES.SQL,undefined);assert.equal(FUNCTION_SIGNATURES.UMJUNSIK,undefined);
  for(const name of Object.values(FILE_NAMES))assert.doesNotMatch(name,/[\\/]/);
});
test('payload uses server DTO, trims title and preserves empty input/output bytes',()=>{
  const form=draft(),body=problemPayload(form,catalog);assert.equal(body.title,'테스트 문제');assert.deepEqual(body.tests,[{input:'',expected:''}]);assert.equal(body.executionMode,'STDIN');assert.equal(body.timeLimitMs,1000);
  const loaded={...form,number:1000,canEdit:true,status:'DRAFT',usedInContest:false,currentVisibility:'PRIVATE'};
  const result=problemPayload(loaded,catalog);for(const key of ['number','canEdit','status','usedInContest','currentVisibility'])assert.equal(result[key],undefined);
});
test('all sixteen STDIN references fit expanded limit and Unicode is byte bounded',()=>{
  const form=draft();form.referenceSolutions=form.languages.map(language=>({language,fileName:FILE_NAMES[language],sourceCode:'source'}));
  assert.equal(problemPayload(form,catalog).referenceSolutions.length,16);
  assert.equal(sourceBytes('가'),3);assert.throws(()=>problemPayload({...form,referenceSolutions:[{language:'PYTHON',fileName:'main.py',sourceCode:'가'.repeat(21846)}]},catalog),/64 KiB/);
});
test('incompatible modes, duplicate references and path-like filenames are rejected',()=>{
  const form=draft();for(const id of ['SQL','UMJUNSIK'])assert.throws(()=>problemPayload({...form,executionMode:'FUNCTION',languages:[id]},catalog),/허용 언어/);
  const reference={language:'PYTHON',fileName:'main.py',sourceCode:'pass'};
  assert.throws(()=>problemPayload({...form,referenceSolutions:[reference,reference]},catalog),/언어별 하나/);
  for(const fileName of ['../main.py','x\\main.py','bad\n.py'])assert.throws(()=>problemPayload({...form,referenceSolutions:[{...reference,fileName}]},catalog),/파일명/);
});
test('group scope, tags and row count boundaries match server restrictions',()=>{
  assert.throws(()=>problemPayload({...draft(),visibility:'GROUP'},catalog),/그룹/);
  assert.equal(problemPayload({...draft(),groupId:2,visibility:'PUBLIC'},catalog).visibility,'GROUP');
  assert.throws(()=>problemPayload({...draft(),tags:Array(11).fill('tag')},catalog),/태그/);
  assert.throws(()=>problemPayload({...draft(),examples:Array(21).fill({input:'',output:''})},catalog),/20개/);
  assert.throws(()=>problemPayload({...draft(),tests:Array(51).fill({input:'',expected:''})},catalog),/50개/);
});
test('source file loading accepts UTF8 and rejects binary, invalidUTF8 and oversized input',async()=>{
  const good=new TextEncoder().encode('print("안녕")\n');
  assert.deepEqual(await readSourceFile({name:'main.py',size:good.length,arrayBuffer:async()=>good.buffer}),{fileName:'main.py',sourceCode:'print("안녕")\n'});
  for(const [name,bytes,size] of [['../main.py',good,good.length],['main.py',Uint8Array.of(255),1],['main.py',Uint8Array.of(0,1),2],['main.py',good,65537]])await assert.rejects(readSourceFile({name,size,arrayBuffer:async()=>bytes.buffer}));
});
test('result labels cover every queued/running and terminal server status',()=>{
  for(const status of ['QUEUED','RUNNING','ACCEPTED','WRONG_ANSWER','TIME_LIMIT_EXCEEDED','MEMORY_LIMIT_EXCEEDED','RUNTIME_ERROR','COMPILATION_ERROR','SYSTEM_ERROR'])assert.ok(STATUS[status]);
});
test('starter defaults cover every real mode and fixed FUNCTION ABI without borrowing private solutions',()=>{
  for(const language of catalog){for(const mode of ['STDIN','FUNCTION','SQL']){
    const code=starterCode({execution_mode:mode,functionAbi:mode==='FUNCTION'?'CHAIN_LASER_V1':null,referenceSolutions:[{language:language.id,sourceCode:'DO_NOT_COPY_PRIVATE_ANSWER'}]},language.id);
    assert.equal(Boolean(code),language.modes.includes(mode),`${language.id} ${mode}`);
    assert.doesNotMatch(code,/DO_NOT_COPY_PRIVATE_ANSWER/);
    if(mode==='FUNCTION'&&language.modes.includes(mode))assert.match(code,/sol|Sol/);
  }}
  assert.match(starterCode({executionMode:'FUNCTION',functionAbi:'CHAIN_LASER_V1'},'PYTHON'),/matrix: list\[list\[int\]\], target: tuple\[int, int\]/);
  assert.match(starterCode({executionMode:'FUNCTION',functionAbi:'CHAIN_LASER_V1'},'JAVASCRIPT'),/function sol\(matrix, target\)/);
  assert.doesNotMatch(starterCode({executionMode:'FUNCTION',functionAbi:'CHAIN_LASER_V1'},'JAVASCRIPT'),/sol\(let/);
  assert.match(starterCode({executionMode:'FUNCTION',functionAbi:'CHAIN_LASER_V1'},'PASCAL'),/Result := False;/);
  assert.equal(starterCode({execution_mode:'STDIN'},'UMJUNSIK'),'어떻게\n\n이 사람이름이냐ㅋㅋ\n');
  assert.equal(starterCode({execution_mode:'unknown'},'PYTHON'),'');
});
test('per-problem public starters preserve each exact signature and never infer parameters from answers',()=>{
  const custom='def sol(x: int, y: int):\n    pass\n';
  const problem={executionMode:'FUNCTION',functionAbi:'CHAIN_LASER_V1',starterCodes:[{language:'PYTHON',sourceCode:custom}],referenceSolutions:[{language:'JAVASCRIPT',sourceCode:'private_answer'}]};
  assert.equal(starterCode(problem,'PYTHON'),custom);
  assert.match(starterCode(problem,'JAVASCRIPT'),/matrix, target/);
  assert.doesNotMatch(starterCode(problem,'JAVASCRIPT'),/private_answer/);
  assert.equal(starterCode({...problem,executionMode:'SQL'},'PYTHON'),'');
  for(const sourceCode of ['',null,'가'.repeat(21846)])assert.match(starterCode({...problem,starterCodes:[{language:'PYTHON',sourceCode}]},'PYTHON'),/matrix: list/);
  for(const functionAbi of [null,undefined,'UNRECOGNIZED']){
    assert.equal(starterCode({executionMode:'FUNCTION',functionAbi},'PYTHON'),'');
    assert.equal(starterCode({...problem,functionAbi},'PYTHON'),custom);
  }
});
test('starter payload is explicit, language-unique, byte bounded and disjoint from private references',()=>{
  const form=draft(),sourceCode='def sol(x: int, y: int):\n    pass\n';
  assert.deepEqual(form.starterCodes,[]);
  assert.deepEqual(problemPayload({...form,starterCodes:undefined},catalog).starterCodes,[]);
  const row={language:'PYTHON',sourceCode};
  assert.deepEqual(problemPayload({...form,starterCodes:[{...row,fileName:'ignored.py'}]},catalog).starterCodes,[row]);
  assert.deepEqual(problemPayload({...form,starterCodes:[row]},catalog).referenceSolutions,[]);
  assert.equal(problemPayload({...form,functionAbi:'CHAIN_LASER_V1'},catalog).functionAbi,undefined);
  assert.throws(()=>problemPayload({...form,starterCodes:[row,row]},catalog),/언어별 하나/);
  assert.throws(()=>problemPayload({...form,starterCodes:[{...row,language:'SQL'}]},catalog),/허용 언어별/);
  for(const value of ['',null,42,'가'.repeat(21846)])assert.throws(()=>problemPayload({...form,starterCodes:[{...row,sourceCode:value}]},catalog),/64 KiB/);
});
test('custom and legacy function guidance does not invent matrix target parameters',()=>{
  const starterCodes=[{language:'PYTHON',sourceCode:'def sol(x: int, y: int):\n    pass\n'}];
  assert.match(languageHelp('PYTHON','FUNCTION',{starterCodes}),/공개 시작 코드의 함수 선언·매개변수/);
  assert.doesNotMatch(languageHelp('PYTHON','FUNCTION',{starterCodes}),/matrix|target/);
  assert.match(languageHelp('PYTHON','FUNCTION',{functionAbi:null}),/매개변수를 자동 추정하지 않습니다/);
  assert.match(languageHelp('PYTHON','FUNCTION',{functionAbi:'CHAIN_LASER_V1'}),/matrix, target/);
  assert.match(languageHelp('PYTHON','FUNCTION'),/matrix, target/);
});
