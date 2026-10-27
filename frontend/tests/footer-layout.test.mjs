import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {readFile} from 'node:fs/promises';
import {build} from 'esbuild';
import {JSDOM} from 'jsdom';

const require=createRequire(import.meta.url);
const dom=new JSDOM('<!doctype html><html><body></body></html>',{url:'http://localhost:8080/'});
Object.defineProperty(globalThis,'navigator',{value:dom.window.navigator,configurable:true});
for(const key of ['window','document','HTMLElement','Event','MutationObserver'])globalThis[key]=dom.window[key];
globalThis.IS_REACT_ACT_ENVIRONMENT=true;
const React=require('react'),{render,fireEvent,waitFor,cleanup}=require('@testing-library/react');
const {MemoryRouter}=require('react-router-dom');

// 라우트의 공개/인증/관리자 경계와 실제 Footer를 검사합니다. 각 페이지의 API와
// 편집기 내용은 이 레이아웃 테스트의 범위가 아니므로 작은 실제 stateful 화면으로 대체합니다.
const bundle=await build({entryPoints:['src/AppRoutes.jsx'],bundle:true,write:false,platform:'node',format:'cjs',packages:'external',jsx:'automatic',loader:{'.css':'empty'},plugins:[{name:'footer-route-contract',setup(builder){
  builder.onResolve({filter:/\/context\/AuthContext(?:\.jsx)?$/},()=>({path:'auth',namespace:'footer-contract'}));
  builder.onResolve({filter:/^\.\/components\/Header$/},()=>({path:'header',namespace:'footer-contract'}));
  builder.onLoad({filter:/.*/,namespace:'footer-contract'},args=>({contents:args.path==='auth'?'export const useAuth=()=>globalThis.__footerAuth;':'export default function Header(){return <header data-testid="route-header">Header</header>;}',loader:'jsx',resolveDir:process.cwd()}));
  builder.onResolve({filter:/^\.\/pages\/[A-Za-z]+$/},args=>({path:args.path.split('/').at(-1),namespace:'footer-page'}));
  builder.onLoad({filter:/.*/,namespace:'footer-page'},args=>({contents:`import {useState} from 'react'; export default function Page(){const [draft,setDraft]=useState('');return <main data-testid="stub-page"><h1>${args.path}</h1><input aria-label="임시 초안" value={draft} onChange={event=>setDraft(event.target.value)}/></main>;}`,loader:'jsx',resolveDir:process.cwd()}));
}}]});
const module={exports:{}};new Function('require','module','exports',bundle.outputFiles[0].text)(require,module,module.exports);
const Routes=module.exports.default;
const anonymous=()=>({user:null,loading:false,epoch:0,permissions:{canManage:false,canCreate:false}});
function tree(url,dark=true){return React.createElement(MemoryRouter,{initialEntries:[url]},React.createElement(Routes,{dark,setDark:()=>{}}));}
test.beforeEach(()=>{globalThis.__footerAuth=anonymous();});
test.afterEach(()=>cleanup());
test.after(()=>dom.window.close());

test('all public and authentication route families share exactly one footer',()=>{
  for(const [url,page] of [['/','Main'],['/login','Login'],['/signup','Signup'],['/password-reset','PasswordReset'],['/oauth/callback','OAuthCallBack'],['/problems/1000','ProblemDetail'],['/not-a-page','ComingSoon']]){
    const view=render(tree(url));assert.equal(view.getByRole('heading').textContent,page,url);
    assert.equal(view.getAllByRole('contentinfo').length,1,url);
    assert.equal(view.getAllByRole('navigation',{name:'푸터 메뉴'}).length,1,url);
    cleanup();
  }
});

test('footer links point only to existing routes and use the current year and active theme',()=>{
  const view=render(tree('/login'));
  const footer=view.getByRole('contentinfo');assert.equal(footer.classList.contains('dark'),true);
  const links=[...view.getByRole('navigation',{name:'푸터 메뉴'}).querySelectorAll('a')];
  assert.deepEqual(links.map(link=>link.getAttribute('href')),['/problems','/groups','/forum','/notices']);
  assert.match(footer.textContent,new RegExp(`CobWeb · ${new Date().getFullYear()}`));
  assert.equal(footer.querySelectorAll('a[href^="http"]').length,0);
  view.rerender(tree('/login',false));assert.equal(view.getByRole('contentinfo').classList.contains('light'),true);
  assert.equal(view.container.querySelector('.cw-app-shell').classList.contains('light'),true);
});

test('authentication loading, anonymous redirect and denied admin pages keep one footer',async()=>{
  globalThis.__footerAuth={...anonymous(),loading:true};let view=render(tree('/admin/review'));
  assert.equal(view.getByRole('status').textContent,'로그인 상태 확인 중…');assert.equal(view.getAllByRole('contentinfo').length,1);cleanup();
  globalThis.__footerAuth=anonymous();view=render(tree('/dashboard'));
  await waitFor(()=>assert.equal(view.getByRole('heading').textContent,'Login'));assert.equal(view.getAllByRole('contentinfo').length,1);cleanup();
  globalThis.__footerAuth={...anonymous(),user:{username:'ordinary'}};view=render(tree('/admin/community'));
  assert.equal(view.getByRole('alert').textContent,'운영자 권한이 필요합니다.');assert.equal(view.getAllByRole('contentinfo').length,1);
});

test('authenticated and administrator pages keep the same footer without changing authorization',()=>{
  globalThis.__footerAuth={...anonymous(),user:{username:'operator'},permissions:{canManage:true,canCreate:true}};
  for(const [url,page] of [['/dashboard','Dashboard'],['/groups/7','GroupDetail'],['/admin','ProblemReview'],['/admin/review','ProblemReview'],['/admin/community','ForumList'],['/admin/community/1','ForumPost']]){
    const view=render(tree(url));assert.equal(view.getByRole('heading').textContent,page,url);
    assert.equal(view.getAllByRole('contentinfo').length,1,url);cleanup();
  }
});

test('adding the footer preserves pathname and account-epoch private draft remounts',()=>{
  globalThis.__footerAuth={...anonymous(),user:{username:'alice'}};
  const view=render(tree('/dashboard'));
  fireEvent.change(view.getByRole('textbox',{name:'임시 초안'}),{target:{value:'private-route-draft'}});
  fireEvent.click(view.getByRole('navigation',{name:'푸터 메뉴'}).querySelector('a[href="/problems"]'));
  assert.equal(view.getByRole('heading').textContent,'ProblemList');assert.equal(view.getByRole('textbox',{name:'임시 초안'}).value,'');
  fireEvent.change(view.getByRole('textbox',{name:'임시 초안'}),{target:{value:'private-account-draft'}});
  globalThis.__footerAuth={...globalThis.__footerAuth,epoch:1,user:{username:'bob'}};view.rerender(tree('/dashboard'));
  assert.equal(view.getByRole('textbox',{name:'임시 초안'}).value,'');assert.equal(view.getAllByRole('contentinfo').length,1);
});

test('footer layout grows with content and supplies visited and keyboard-focus styles',async()=>{
  const css=await readFile(new URL('../src/components/Footer.css',import.meta.url),'utf8');
  assert.match(css,/\.cw-app-shell\s*\{[^}]*display:\s*flex;[^}]*flex-direction:\s*column;[^}]*min-height:\s*100dvh/s);
  assert.match(css,/\.cw-app-content,[\s\S]*?flex:\s*1 0 auto/);
  assert.doesNotMatch(css,/position:\s*(?:fixed|absolute)/);
  assert.match(css,/\.cw-footer-nav a:visited/);assert.match(css,/\.cw-footer a:focus-visible/);
});
