import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
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
  stdin:{contents:"export {default as Login} from './src/pages/Login.jsx';export {default as Signup} from './src/pages/Signup.jsx';export {default as SocialSignup} from './src/pages/SocialSignup.jsx';export {default as Terms} from './src/pages/Terms.jsx';",resolveDir:process.cwd(),loader:'jsx'},
  bundle:true,write:false,platform:'node',format:'cjs',packages:'external',jsx:'automatic',loader:{'.css':'empty','.md':'text'},
  plugins:[{name:'auth-contract',setup(builder){
    builder.onResolve({filter:/service-terms\.md\?raw$/},()=>({path:fileURLToPath(new URL('../src/content/service-terms.md',import.meta.url))}));
    builder.onResolve({filter:/(?:^|\/)api(?:\.js)?$/},()=>({path:'api',namespace:'fixture'}));
    builder.onResolve({filter:/\/context\/AuthContext(?:\.jsx)?$/},()=>({path:'auth',namespace:'fixture'}));
    builder.onLoad({filter:/.*/,namespace:'fixture'},args=>({contents:args.path==='api'
      ? 'export const api=(...args)=>globalThis.__progressiveApi(...args);export const isStale=()=>false;export const waitForSessionCookies=async()=>{};export const getSessionEpoch=()=>0;'
      : 'export const useAuth=()=>globalThis.__progressiveAuth;'}));
  }}],
});
const module = {exports:{}};
new Function('require','module','exports',bundle.outputFiles[0].text)(require,module,module.exports);
const {Login,Signup,SocialSignup,Terms} = module.exports;
function CurrentPath(){return React.createElement('output',{'data-testid':'path'},useLocation().pathname);}
function mount(path){return render(React.createElement(MemoryRouter,{initialEntries:[path]},
  React.createElement(CurrentPath),React.createElement(Routes,null,
    React.createElement(Route,{path:'/login',element:React.createElement(Login,{dark:false,setDark(){}})}),
    React.createElement(Route,{path:'/signup',element:React.createElement(Signup,{dark:true,setDark(){}})}),
    React.createElement(Route,{path:'/signup/social',element:React.createElement(SocialSignup,{dark:true,setDark(){}})}),
    React.createElement(Route,{path:'/terms',element:React.createElement(Terms,{dark:true})}),
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

test('terms display the published Korean date independently of the version and link the operator email',async()=>{
  globalThis.__progressiveApi=async path=>{
    assert.equal(path,'/api/terms/current');
    return {version:'2026-11-20',effectiveDate:'2027-01-02',operator:'cobweb 개발팀',contactEmail:'cobweb2026.gbsw@gmail.com'};
  };
  const view=mount('/terms');
  const contact=await view.findByRole('link',{name:'cobweb2026.gbsw@gmail.com'});
  assert.equal(contact.getAttribute('href'),'mailto:cobweb2026.gbsw@gmail.com');
  assert.ok(view.getByText('cobweb 개발팀'));assert.ok(view.getByText('약관 버전 2026-11-20'));
  assert.equal(view.container.querySelector('time').dateTime,'2027-01-02');
  assert.equal(view.container.querySelector('time').textContent,'2027년 1월 2일');
});

test('terms remain readable on a metadata failure and retry distinguishes an unpublished version',async()=>{
  let attempts=0;
  globalThis.__progressiveApi=async()=>{if(++attempts===1)throw Error('unavailable');
    return {version:'2026-11-20',effectiveDate:null,operator:'cobweb 개발팀',contactEmail:'cobweb2026.gbsw@gmail.com'};};
  const view=mount('/terms');
  await view.findByRole('alert');assert.match(view.getByRole('article').textContent,/제13조/);
  fireEvent.click(view.getByRole('button',{name:'다시 시도'}));
  await view.findByText('첫 운영 배포 시 확정');assert.equal(view.container.querySelector('time'),null);
});

test('a page cached before a revision does not label the old text with the new effective date',async()=>{
  globalThis.__progressiveApi=async()=>({version:'2027-01-02',effectiveDate:'2027-01-02'});
  const view=mount('/terms');
  assert.match((await view.findByRole('alert')).textContent,/새로고침/);
  assert.equal(view.container.querySelector('time'),null);
});

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
  assert.equal(calls.length,2);
  assert.match(view.getByRole('alert').textContent,/이용약관/);
  const consent=view.getByRole('checkbox',{name:'이용약관에 동의합니다 (필수)'});
  assert.equal(consent.checked,false);
  assert.equal(view.getByRole('link',{name:'이용약관 전문 보기 (새 창)'}).getAttribute('target'),'_blank');
  fireEvent.click(consent);
  fireEvent.click(view.getByRole('button',{name:'회원가입'}));
  await waitFor(()=>assert.equal(view.getByTestId('path').textContent,'/login'));
  assert.deepEqual(calls.map(call=>call[0]),['/api/email-verifications','/api/email-verifications/verify','/api/auth/signup']);
  assert.deepEqual(calls[2][2],{username:'new-user',email:'learner@example.com',verificationCode:'12345678',password:'password-123',acceptedTermsVersion:'2026-11-20'});
});

test('social signup starts only after accepting the displayed version and permits a failed request to be retried',async()=>{
  const view=mount('/signup/social?provider=google');
  const start=view.getByRole('button',{name:'Google 계정으로 계속'});
  assert.equal(start.disabled,true);
  assert.equal(view.getByRole('link',{name:'이용약관 전문 보기 (새 창)'}).getAttribute('href'),'/terms');
  fireEvent.click(view.getByRole('checkbox'));
  globalThis.__progressiveApi=async(...args)=>{calls.push(args);throw Error('다시 시도해 주세요.');};
  fireEvent.click(start);
  await view.findByRole('alert');
  assert.deepEqual(calls[0].slice(0,3),['/api/auth/oauth/google/signup-start','POST',{acceptedTermsVersion:'2026-11-20'}]);
  assert.equal(start.disabled,false);
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
