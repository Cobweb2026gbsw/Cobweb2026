import test from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {analysisPreviewPlugin,createAnalysisMiddleware,loadAnalysisProblems,publicProblem} from '../dev/analysisPreview.mjs';

test('analysis plugin is opt-in and excluded even from analysis-mode builds',()=>{
  const plugin=analysisPreviewPlugin();
  assert.equal(plugin.apply({}, {command:'serve',mode:'analysis'}),true);
  for(const [command,mode] of [['serve','development'],['serve','production'],['build','analysis'],['build','production']]) {
    assert.equal(plugin.apply({}, {command,mode}),false);
  }
  assert.throws(()=>plugin.configResolved({server:{host:'0.0.0.0'}}));
  const config={server:{host:'127.0.0.1',proxy:{'/api':'http://real-server'},cors:true,allowedHosts:true,fs:{deny:['.env']}}};
  plugin.configResolved(config);
  assert.deepEqual(config.server.proxy,{});
  assert.equal(config.server.cors,false);
  assert.ok(config.server.fs.deny.includes('**/src/main/resources/problems/**'));
});

test('all 450 problems contain public fields only, including nested objects',()=>{
  const problems=loadAnalysisProblems();
  assert.equal(problems.length,450);
  assert.equal(new Set(problems.map(problem=>problem.problem_number)).size,450);
  const text=JSON.stringify(problems);
  for(const key of ['referenceSolution','referenceSolutions','tests','expected','password','sourceCode']) assert.ok(!text.includes(`"${key}":`),key);
  const mapped=publicProblem({number:1000,title:'sample',functionSignature:{name:'sol',parameters:[{name:'n',type:'int',secret:'hidden'}],returnType:'int',secret:'hidden'},examples:[{input:'1',output:'2',secret:'hidden'}],tags:[],tests:['hidden'],referenceSolution:'hidden'});
  assert.ok(!JSON.stringify(mapped).includes('hidden'));
});

test('local session, discovery, filtering and unsupported actions stay isolated',async(t)=>{
  let fallback=0;
  const middleware=createAnalysisMiddleware();
  const server=createServer((req,res)=>middleware(req,res,()=>{fallback++;res.writeHead(404);res.end();}));
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  t.after(()=>{server.closeAllConnections();server.close();});
  const base=`http://127.0.0.1:${server.address().port}`;
  async function request(path,options={}) {
    const response=await fetch(base+path,options);
    return {status:response.status,...await response.json()};
  }
  const after=async(number,params='')=>(await request('/api/problems/next?number='+number+'&'+params)).data;
  assert.deepEqual(await after(1029),{number:1030,page:1});
  assert.deepEqual(await after(1000,'chapter=true'),{number:1001,page:0});
  assert.equal(await after(1019,'chapter=true'),null);
  assert.equal(await after(1450),null);
  assert.equal(await after(1000,'solved=solved'),null);
  const chapter=(await request('/api/problems/chapter?level=5&chapter=5')).data;
  assert.deepEqual(chapter.map(p=>p.problem_number),[1424,1425,1426,1427,1428,1429,1430]);
  const session=await request('/api/auth/reissue',{method:'POST'});
  const headers={Authorization:`Bearer ${session.data.accessToken}`};
  const identity=await request('/api/me',{headers});
  assert.equal(identity.data.username,'분석용 계정');
  assert.ok(BigInt(identity.data.id)>9223372036854775807n);
  assert.equal((await request('/api/me')).status,401);
  assert.deepEqual((await request('/api/problems/permissions')).data,{canCreate:false,canManage:false});

  const first=(await request('/api/problems?pagination=true')).data;
  assert.equal(first.totalItems,450);assert.equal(first.totalPages,15);assert.equal(first.items.length,30);
  const last=(await request('/api/problems?pagination=true&page=1000')).data;
  assert.equal(last.page,14);assert.equal(last.items.length,30);
  const loops=(await request('/api/problems?pagination=true&search='+encodeURIComponent('반복문'))).data;
  assert.equal(loops.totalItems,20);
  assert.ok(loops.items.every(problem=>problem.tags.includes('반복문')));
  const filtered=(await request('/api/problems?pagination=true&difficulty=INITIATE:5&difficulty=INITIATE:4')).data;
  assert.ok(filtered.items.length>0);
  assert.ok(filtered.items.every(problem=>problem.rank_type==='INITIATE'&&[5,4].includes(problem.rank_int)));
  assert.equal((await request('/api/problems?pagination=true&search=Hello')).data.items[0].problem_number,1000);
  assert.equal((await request('/api/problems?pagination=true&solved=solved')).data.totalItems,0);
  assert.equal((await request('/api/problems/1000')).data.functionSignature.name,'sol');
  assert.equal((await request('/api/problems/9999')).status,404);
  assert.equal((await request('/api/languages')).data.length,15);

  for(const path of ['/api/submissions','/api/submissions/recover','/api/problems/1000/test-runs','/api/admin/users','/api/auth/login']) {
    const response=await request(path,{method:'POST',headers,body:'{"sourceCode":"do not execute or save"}'});
    assert.equal(response.status,409);assert.equal(response.success,false);assert.match(response.message,/실행·제출/);
  }
  assert.deepEqual((await request('/api/submissions')).data,[]);
  assert.deepEqual((await request('/api/groups?search=study')).data,[]);
  assert.equal((await request('/api/admin/users')).status,404);
  assert.equal(fallback,0,'No API request may reach the real proxy');
  assert.equal((await request('/api/auth/logout',{method:'POST'})).success,true);
  assert.equal((await request('/api/analysis/session',{method:'POST'})).success,true);

  const script=await fetch(base+'/markdown.js');
  assert.equal(script.status,200);assert.match(await script.text(),/CobwebMarkdown/);
});

test('function statements keep relevant limits and remove repeated introductory instructions', async()=>{
  const {statementFields}=await import('../src/lib/statementFields.js');
  const problems=loadAnalysisProblems();
  const fields=Object.fromEntries(statementFields(problems[0]));
  assert.match(fields['문제 설명'],/Hello World!/);
  assert.doesNotMatch(Object.values(fields).join(' '),/1e-9|32비트|JSON/);
  assert.doesNotMatch(fields['출력'],/Hello World!/);
  for(const problem of problems){
    const output=Object.fromEntries(statementFields(problem))['출력'];
    if(problem.functionSignature.returnType==='float' && problem.output_description.includes('1e-9')) assert.match(output,/1e-9/);
    if(problem.functionSignature.returnType!=='float') assert.ok(!output.includes('실수 반환값은 절대 또는 상대 오차'));
  }
});

test('both introductory statement formats retain the task, applicable constraints and return rule',async()=>{
  const {statementFields}=await import('../src/lib/statementFields.js');
  const problems=loadAnalysisProblems().filter(problem=>problem.tags.includes('Lv.0'));
  for(const problem of problems){
    const fields=Object.fromEntries(statementFields(problem));
    assert.doesNotMatch(fields['문제 설명'],/JSON|^## 문제/);
    assert.ok(!fields['출력'].includes(fields['문제 설명']));
    if(problem.output_description.includes('1e-9')&&problem.functionSignature.returnType==='float')assert.match(fields['출력'],/1e-9/);
  }
  const numeric=Object.fromEntries(statementFields(problems.find(p=>p.problem_number===1019)));
  assert.doesNotMatch(numeric['제한'],/ASCII/);
  assert.match(numeric['제한'],/32비트/);
  assert.match(numeric['문제 설명'],/인덱스는 0/);
  assert.match(Object.fromEntries(statementFields(problems[0]))['제한'],/ASCII/);
});
