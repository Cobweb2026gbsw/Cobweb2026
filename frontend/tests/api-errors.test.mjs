import test from 'node:test';
import assert from 'node:assert/strict';
import {api,beginSession,getAccessToken,isStale,logoutSession,restoreSession} from '../src/lib/api.js';
import {ApiError,NetworkError,ProtocolError,RequestError,StaleSessionError,SessionChangedError} from '../src/lib/errors.js';
import {jwtFixture} from './jwt-fixture.mjs';
import {emptyProblem,problemPayload,readSourceFile} from '../src/lib/problemModel.js';
import {contestPayload} from '../src/lib/groupContracts.js';

const original=globalThis.fetch;
const response=(status,envelope)=>({status,ok:status>=200&&status<300,json:async()=>envelope});
test.beforeEach(()=>beginSession(null));
test.afterEach(()=>{globalThis.fetch=original;beginSession(null);});

test('HTTP failures have explicit ApiError status and safe server errorCode without refresh',async()=>{
  let calls=0;
  for(const [status,errorCode] of [[400,'INVALID_REQUEST'],[401,'AUTHENTICATION_REQUIRED'],[403,'PERMISSION_DENIED'],[404,'RESOURCE_NOT_FOUND'],[409,'STATE_CONFLICT'],[429,'RATE_LIMITED'],[503,'SERVICE_UNAVAILABLE']]){
    globalThis.fetch=async()=>{calls++;return response(status,{success:false,data:null,message:'처리할 수 없습니다.',errorCode});};
    await assert.rejects(api('/api/problems/1000'),error=>error instanceof ApiError&&error.status===status&&error.errorCode===errorCode);
  }
  assert.equal(calls,7);
  globalThis.fetch=async()=>response(403,{success:false,data:null});
  await assert.rejects(api('/api/problems/1000'),error=>error instanceof ApiError&&error.errorCode==='PERMISSION_DENIED');
});

test('HTML SQL credential stack and malformed messages never reach displayed API errors',async()=>{
  const unsafe=['<script>secret</script>','SQLException SELECT password FROM users','SQLState 23000 private details','password=super-secret','access_token=super-secret','org.springframework.jdbc.private.Class','x'.repeat(501),{secret:'secret'},'line\nsecret'];
  for(const status of [409,500,503])for(const message of unsafe){
    globalThis.fetch=async()=>response(status,{success:false,data:null,message,errorCode:'<private-code>'});
    await assert.rejects(api('/api/test'),error=>error instanceof ApiError&&!error.message.includes('secret')&&!error.message.includes('SELECT')&&!error.message.includes('script')&&error.message.length<150&&/^[A-Z_]+$/.test(error.errorCode));
  }
  globalThis.fetch=async()=>response(500,{success:false,message:'private nontechnical diagnostic',errorCode:'INTERNAL_ERROR'});
  await assert.rejects(api('/api/test'),error=>!error.message.includes('diagnostic'));
});

test('successful broken JSON or invalid envelopes become ProtocolError without raw response data',async()=>{
  for(const envelope of [null,[],{data:7},{success:'true',data:7},{success:true},{success:null,data:'secret'}]){
    globalThis.fetch=async()=>response(200,envelope);
    await assert.rejects(api('/api/test'),error=>error instanceof ProtocolError&&error.errorCode==='INVALID_RESPONSE'&&!error.message.includes('secret'));
  }
  globalThis.fetch=async()=>({status:200,ok:true,json:async()=>{throw new SyntaxError('secret body');}});
  await assert.rejects(api('/api/test'),error=>error instanceof ProtocolError&&error.status===200&&!error.message.includes('secret'));
  globalThis.fetch=async()=>response(502,null);
  await assert.rejects(api('/api/test'),error=>error instanceof ApiError&&error.status===502&&error.errorCode==='UPSTREAM_ERROR');
});

test('transport errors are typed once and mutation uncertainty is explicit without automatic retries',async()=>{
  let calls=0;globalThis.fetch=async()=>{calls++;throw new TypeError('private fetch details');};
  await assert.rejects(api('/api/problems'),error=>error instanceof NetworkError&&error.status===0&&error.requestMayHaveCompleted===false&&!error.message.includes('private'));
  await assert.rejects(api('/api/submissions','POST',{sourceCode:'private source'}),error=>error instanceof NetworkError&&error.requestMayHaveCompleted===true&&/기록을 확인/.test(error.message)&&!error.message.includes('source'));
  assert.equal(calls,2);
});

test('AbortError is preserved for both transport and body reads rather than shown or retried',async()=>{
  const abort=new DOMException('cancelled','AbortError');let calls=0;
  globalThis.fetch=async()=>{calls++;throw abort;};
  await assert.rejects(api('/api/submissions','POST',{}),error=>error===abort&&isStale(error));
  globalThis.fetch=async()=>{calls++;return {status:200,ok:true,json:async()=>{throw abort;}};};
  await assert.rejects(api('/api/problems'),error=>error===abort&&isStale(error));
  assert.equal(calls,2);
});

test('invalid client paths methods and unserializable bodies fail before fetch as RequestError',async()=>{
  let calls=0;globalThis.fetch=async()=>{calls++;return response(200,{success:true,data:null});};
  for(const path of ['https://outside.invalid/api/me','//outside.invalid/api/me','/api/../pw.txt','/api/%2e%2e/pw.txt','/api/\\outside.invalid/x','/api/me#private','/api/me\n'])
    await assert.rejects(api(path),error=>error instanceof RequestError);
  await assert.rejects(api('/api/me','TRACE'),error=>error instanceof RequestError);
  const cycle={};cycle.self=cycle;
  await assert.rejects(api('/api/me','PATCH',cycle),error=>error instanceof RequestError);
  assert.equal(calls,0);
});

test('a late network failure becomes typed stale session error and cannot clear the new token',async()=>{
  let reject;globalThis.fetch=()=>new Promise((_,no)=>{reject=no;});
  beginSession(jwtFixture('1','A'));const pending=api('/api/me');
  const next=jwtFixture('2','B');beginSession(next);reject(new TypeError('private old account network failure'));
  await assert.rejects(pending,error=>error instanceof StaleSessionError&&error.stale===true&&isStale(error));
  assert.equal(getAccessToken(),next);
});

test('refresh and logout network failures use the same typed boundary and never reuse a token',async()=>{
  globalThis.fetch=async()=>{throw new TypeError('private network error');};
  await assert.rejects(restoreSession(),error=>error instanceof NetworkError);
  beginSession(jwtFixture('1','A'));
  await assert.rejects(logoutSession(),error=>error instanceof NetworkError);
  assert.equal(getAccessToken(),null);
});

test('cross-account refresh preserves the named session-changed type and denies mutation replay',async()=>{
  const calls=[];beginSession(jwtFixture('1','A'));
  globalThis.fetch=async path=>{calls.push(path);return path==='/api/auth/reissue'?response(200,{success:true,data:{accessToken:jwtFixture('2','B')}}):response(401,{success:false,data:null});};
  await assert.rejects(api('/api/me','PATCH',{bio:'old private draft'}),error=>error instanceof SessionChangedError&&error.sessionChanged===true&&error.status===401);
  assert.deepEqual(calls,['/api/me','/api/auth/reissue']);assert.equal(getAccessToken(),null);
});

test('problem contest and local-file validation use the explicit client request type',async()=>{
  assert.throws(()=>problemPayload(emptyProblem(),[]),error=>error instanceof RequestError);
  assert.throws(()=>contestPayload({title:'',problemNumbers:'',startsAt:'',endsAt:''}),error=>error instanceof RequestError);
  for(const file of [{name:'bad.py',size:2,arrayBuffer:async()=>new Uint8Array([0xc3,0x28]).buffer},{name:'bad.py',size:2,arrayBuffer:async()=>{throw new Error('private-file-path');}}])
    await assert.rejects(readSourceFile(file),error=>error instanceof RequestError&&!error.message.includes('private-file-path'));
});
