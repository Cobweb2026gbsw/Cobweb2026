import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {build} from 'esbuild';
import {JSDOM} from 'jsdom';

const dom = new JSDOM('<!doctype html><html><body></body></html>', {url:'http://localhost:5174/'});
for (const key of ['window','document','HTMLElement','Event','MutationObserver']) globalThis[key] = dom.window[key];
Object.defineProperty(globalThis,'navigator',{value:dom.window.navigator,configurable:true});
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
const require = createRequire(import.meta.url), React = require('react');
const {render,fireEvent,waitFor,act,cleanup} = require('@testing-library/react');
const {MemoryRouter,Routes,Route,useLocation} = require('react-router-dom');
const bundle = await build({
  stdin:{contents:"export {default as Login} from './src/pages/Login.jsx';export {default as Signup} from './src/pages/Signup.jsx';",resolveDir:process.cwd(),loader:'jsx'},
  bundle:true,write:false,platform:'node',format:'cjs',packages:'external',jsx:'automatic',loader:{'.css':'empty'},
  plugins:[{name:'auth-contract',setup(builder){
    builder.onResolve({filter:/(?:^|\/)api(?:\.js)?$/},()=>({path:'api',namespace:'fixture'}));
    builder.onResolve({filter:/\/context\/AuthContext(?:\.jsx)?$/},()=>({path:'auth',namespace:'fixture'}));
    builder.onLoad({filter:/.*/,namespace:'fixture'},args=>({contents:args.path==='api'
      ? 'export const api=(...args)=>globalThis.__progressiveApi(...args);export const isStale=()=>false;export const waitForSessionCookies=async()=>{};'
      : 'export const useAuth=()=>globalThis.__progressiveAuth;'}));
  }}],
});
const module = {exports:{}};
new Function('require','module','exports',bundle.outputFiles[0].text)(require,module,module.exports);
const {Login,Signup} = module.exports;
function CurrentPath(){return React.createElement('output',{'data-testid':'path'},useLocation().pathname);}
function mount(path){return render(React.createElement(MemoryRouter,{initialEntries:[path]},
  React.createElement(CurrentPath),React.createElement(Routes,null,
    React.createElement(Route,{path:'/login',element:React.createElement(Login,{dark:false,setDark(){}})}),
    React.createElement(Route,{path:'/signup',element:React.createElement(Signup,{dark:true,setDark(){}})}),
    React.createElement(Route,{path:'/dashboard',element:React.createElement('p',null,'대시보드')}))));}
const type = (view,label,value) => fireEvent.change(view.getByLabelText(label),{target:{value}});
const enter = (view,label,extra={}) => fireEvent.keyDown(view.getByLabelText(label),{key:'Enter',...extra});
const deferred = () => {let resolve,reject;const promise=new Promise((yes,no)=>{resolve=yes;reject=no;});return {promise,resolve,reject};};
let calls;
test.beforeEach(()=>{
  calls=[];
  globalThis.__progressiveApi=async(...args)=>{calls.push(args);return {};};
  globalThis.__progressiveAuth={user:null,loading:false,sessionNotice:'',login:async data=>{calls.push(['login',data]);return true;}};
});
test.afterEach(()=>cleanup());
test.after(()=>dom.window.close());

test('login starts with one focused input; Enter reveals and focuses password without logging in',async()=>{
  const view=mount('/login');
  assert.equal(view.container.querySelectorAll('input').length,1);
  assert.equal(document.activeElement,view.getByLabelText('아이디'));
  enter(view,'아이디');assert.equal(view.queryByLabelText('비밀번호'),null);
  type(view,'아이디','sample-user');enter(view,'아이디');
  const password=view.getByLabelText('비밀번호');
  assert.equal(document.activeElement,password);assert.deepEqual(calls,[]);
  assert.equal(view.getByLabelText('아이디').value,'sample-user');
  type(view,'비밀번호','test-password');enter(view,'비밀번호');
  await waitFor(()=>assert.equal(view.getByTestId('path').textContent,'/dashboard'));
  assert.deepEqual(calls,[['login',{username:'sample-user',password:'test-password',rememberMe:false}]]);
});

test('composition, legacy IME Enter and held Enter never advance or submit',()=>{
  const view=mount('/login');type(view,'아이디','sample-user');
  for(const event of [{isComposing:true},{keyCode:229},{repeat:true}])enter(view,'아이디',event);
  assert.equal(view.queryByLabelText('비밀번호'),null);
  enter(view,'아이디');type(view,'비밀번호','test-password');
  for(const event of [{isComposing:true},{keyCode:229},{repeat:true}])enter(view,'비밀번호',event);
  assert.deepEqual(calls,[]);
});

test('touch next button reveals password; login failure preserves fields and allows retry',async()=>{
  globalThis.__progressiveAuth.login=async()=>{throw Error('로그인 정보를 확인해 주세요.');};
  const view=mount('/login');type(view,'아이디','sample-user');fireEvent.click(view.getByRole('button',{name:'다음',exact:true}));
  assert.equal(document.activeElement,view.getByLabelText('비밀번호'));
  type(view,'비밀번호','test-password');fireEvent.click(view.getByRole('button',{name:'로그인',exact:true}));
  await view.findByRole('alert');assert.equal(view.getByLabelText('비밀번호').value,'test-password');
  assert.equal(view.getByRole('button',{name:'로그인',exact:true}).disabled,false);
});

async function emailStage(view){type(view,'아이디','new-user');enter(view,'아이디');assert.equal(document.activeElement,view.getByLabelText('이메일'));}
async function passwordStage(view){
  await emailStage(view);type(view,'이메일','learner@example.com');enter(view,'이메일');
  await waitFor(()=>assert.equal(document.activeElement,view.getByLabelText('인증번호')));
  type(view,'인증번호','12345678');enter(view,'인증번호');
  await waitFor(()=>assert.equal(document.activeElement,view.getByLabelText('비밀번호 (8자 이상)')));
}

test('signup advances and focuses one field at a time, verifies email before passwords, and submits only at the end',async()=>{
  const view=mount('/signup');assert.equal(view.container.querySelectorAll('input').length,1);
  assert.equal(document.activeElement,view.getByLabelText('아이디'));
  await passwordStage(view);
  assert.equal(calls.length,2);assert.equal(view.queryByLabelText('비밀번호 확인'),null);
  type(view,'비밀번호 (8자 이상)','password-123');enter(view,'비밀번호 (8자 이상)');
  assert.equal(document.activeElement,view.getByLabelText('비밀번호 확인'));
  type(view,'비밀번호 확인','not-matching');enter(view,'비밀번호 확인');
  assert.match(view.getByRole('alert').textContent,/일치/);assert.equal(calls.length,2);
  type(view,'비밀번호 확인','password-123');enter(view,'비밀번호 확인');
  await waitFor(()=>assert.equal(view.getByTestId('path').textContent,'/login'));
  assert.deepEqual(calls.map(call=>call[0]),['/api/email-verifications','/api/email-verifications/verify','/api/auth/signup']);
  assert.deepEqual(calls[2][2],{username:'new-user',email:'learner@example.com',verificationCode:'12345678',password:'password-123'});
});

test('invalid email, incomplete code and short password keep the next field hidden',async()=>{
  const view=mount('/signup');await emailStage(view);
  type(view,'이메일','invalid');enter(view,'이메일');assert.deepEqual(calls,[]);assert.equal(view.queryByLabelText('인증번호'),null);
  type(view,'이메일','learner@example.com');enter(view,'이메일');await view.findByLabelText('인증번호');
  type(view,'인증번호','1234');enter(view,'인증번호');assert.equal(calls.length,1);assert.equal(view.queryByLabelText('비밀번호 (8자 이상)'),null);
  type(view,'인증번호','12345678');enter(view,'인증번호');await view.findByLabelText('비밀번호 (8자 이상)');
  type(view,'비밀번호 (8자 이상)','short');enter(view,'비밀번호 (8자 이상)');assert.equal(view.queryByLabelText('비밀번호 확인'),null);
});

test('send and verify failures retain the current step, and successful retries move focus',async()=>{
  let fail=true;
  globalThis.__progressiveApi=async(...args)=>{calls.push(args);if(fail)throw Error('잠시 후 다시 시도해 주세요.');return {};};
  const view=mount('/signup');await emailStage(view);type(view,'이메일','learner@example.com');
  fireEvent.click(view.getByRole('button',{name:'인증번호 발송',exact:true}));await view.findByRole('alert');assert.equal(view.queryByLabelText('인증번호'),null);
  fail=false;fireEvent.click(view.getByRole('button',{name:'인증번호 발송',exact:true}));await view.findByLabelText('인증번호');
  fail=true;type(view,'인증번호','12345678');fireEvent.click(view.getByRole('button',{name:'인증번호 확인',exact:true}));await view.findByRole('alert');assert.equal(view.queryByLabelText('비밀번호 (8자 이상)'),null);
  fail=false;fireEvent.click(view.getByRole('button',{name:'인증번호 확인',exact:true}));await waitFor(()=>assert.equal(document.activeElement,view.getByLabelText('비밀번호 (8자 이상)')));
});

test('editing email ignores a stale send response and cannot reveal or focus the wrong next step',async()=>{
  const request=deferred();globalThis.__progressiveApi=async(...args)=>{calls.push(args);return request.promise;};
  const view=mount('/signup');await emailStage(view);type(view,'이메일','first@example.com');enter(view,'이메일');enter(view,'이메일');
  assert.equal(calls.length,1);
  type(view,'이메일','second@example.com');await act(async()=>{request.resolve({});});
  assert.equal(view.queryByLabelText('인증번호'),null);assert.equal(document.activeElement,view.getByLabelText('이메일'));
});

test('editing a verified email hides dependent fields and requires verification again',async()=>{
  const view=mount('/signup');await passwordStage(view);
  type(view,'비밀번호 (8자 이상)','password-123');enter(view,'비밀번호 (8자 이상)');
  type(view,'이메일','changed@example.com');
  assert.equal(view.queryByLabelText('비밀번호 (8자 이상)'),null);assert.equal(view.queryByLabelText('비밀번호 확인'),null);assert.equal(view.queryByLabelText('인증번호'),null);
  enter(view,'이메일');await view.findByLabelText('인증번호');assert.equal(view.getByLabelText('인증번호').value,'');
  assert.equal(calls.filter(call=>call[0]==='/api/auth/signup').length,0);
});
