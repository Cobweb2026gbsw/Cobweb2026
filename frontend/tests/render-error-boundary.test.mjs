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
const React=require('react'),{render,fireEvent,cleanup}=require('@testing-library/react');
const {MemoryRouter}=require('react-router-dom');
const originalError=console.error;
// React 개발모드의 의도적인 throw 보고만 숨겨, 실패 출력이 Error/DOM 객체를 확장하지 않게 합니다.
test.beforeEach(()=>{console.error=()=>{};globalThis.__renderFail=false;globalThis.__renderAuth={user:{username:'alice'},loading:false,epoch:0,permissions:{canManage:true,canCreate:true}};});
test.afterEach(()=>{cleanup();console.error=originalError;});
test.after(()=>dom.window.close());
const bundle=await build({stdin:{contents:"export {default as Boundary} from './src/components/RenderErrorBoundary.jsx';export {default as Routes} from './src/AppRoutes.jsx';",resolveDir:process.cwd(),sourcefile:'render-boundary-test.jsx'},bundle:true,write:false,platform:'node',format:'cjs',packages:'external',jsx:'automatic',loader:{'.css':'empty'},plugins:[{name:'boundary-route-contract',setup(builder){
  builder.onResolve({filter:/\/context\/AuthContext(?:\.jsx)?$/},()=>({path:'auth',namespace:'boundary-contract'}));
  builder.onResolve({filter:/^\.\/components\/Header$/},()=>({path:'header',namespace:'boundary-contract'}));
  builder.onLoad({filter:/.*/,namespace:'boundary-contract'},args=>({contents:args.path==='auth'?'export const useAuth=()=>globalThis.__renderAuth;':'export default function Header(){return <header>Header</header>;}',loader:'jsx',resolveDir:process.cwd()}));
  builder.onResolve({filter:/^\.\/pages\/[A-Za-z]+$/},args=>({path:args.path.split('/').at(-1),namespace:'boundary-page'}));
  builder.onLoad({filter:/.*/,namespace:'boundary-page'},args=>({contents:`import {useState} from 'react'; export default function Page(){const [draft,setDraft]=useState('');if(globalThis.__renderFail)throw new Error('PRIVATE_SQL_PASSWORD_AND_SOURCE');return <main><h1>${args.path}</h1><input aria-label="초안" value={draft} onChange={event=>setDraft(event.target.value)}/></main>;}`,loader:'jsx',resolveDir:process.cwd()}));
}}]});
const module={exports:{}};new Function('require','module','exports',bundle.outputFiles[0].text)(require,module,module.exports);
const {Boundary,Routes}=module.exports;
function SensitiveChild({fail=false}){const [draft,setDraft]=React.useState('');if(fail)throw new Error('PRIVATE_SQL_PASSWORD_AND_SOURCE');return React.createElement('input',{'aria-label':'개인 초안',value:draft,onChange:event=>setDraft(event.target.value)});}
const tree=(url='/dashboard')=>React.createElement(MemoryRouter,{initialEntries:[url]},React.createElement(Routes,{dark:true,setDark:()=>{}}));

test('render failures remove private DOM and never print the raw message stack or source',()=>{
  const view=render(React.createElement(Boundary,null,React.createElement(SensitiveChild)));
  fireEvent.change(view.getByRole('textbox',{name:'개인 초안'}),{target:{value:'PRIVATE_DRAFT_SENTINEL'}});
  view.rerender(React.createElement(Boundary,null,React.createElement(SensitiveChild,{fail:true})));
  assert.equal(view.queryByRole('textbox')===null,true);
  assert.equal(view.getByRole('alert').textContent.includes('PRIVATE_'),false);
  assert.equal(view.getByRole('link',{name:'홈으로'}).getAttribute('href'),'/');
});

test('explicit retry remounts a clean screen without replaying a save or submission',()=>{
  let fail=true,mutations=0;
  function Screen(){const [draft,setDraft]=React.useState('');if(fail)throw new Error('private');return React.createElement('div',null,React.createElement('input',{'aria-label':'초안',value:draft,onChange:event=>setDraft(event.target.value)}),React.createElement('button',{onClick:()=>mutations++},'저장'));}
  const view=render(React.createElement(Boundary,null,React.createElement(Screen)));
  assert.equal(mutations,0);fail=false;fireEvent.click(view.getByRole('button',{name:'화면 다시 열기'}));
  assert.equal(view.getByRole('textbox',{name:'초안'}).value,'');assert.equal(mutations,0);
  fireEvent.click(view.getByRole('button',{name:'저장'}));assert.equal(mutations,1);
});

test('route render failures keep exactly one footer and a safe navigation route',()=>{
  globalThis.__renderFail=true;const view=render(tree());
  assert.equal(view.getByRole('heading',{name:'화면을 표시하지 못했습니다.'})!==null,true);
  assert.equal(view.getAllByRole('contentinfo').length,1);
  assert.equal(view.container.textContent.includes('PRIVATE_'),false);
  globalThis.__renderFail=false;fireEvent.click(view.getByRole('navigation',{name:'푸터 메뉴'}).querySelector('a[href="/problems"]'));
  assert.equal(view.getByRole('heading').textContent,'ProblemList');
  assert.equal(view.getAllByRole('contentinfo').length,1);
});

test('account epoch changes reset a failed boundary without restoring a prior private draft',()=>{
  const view=render(tree());fireEvent.change(view.getByRole('textbox',{name:'초안'}),{target:{value:'PRIVATE_ALICE_DRAFT'}});
  globalThis.__renderFail=true;view.rerender(tree());assert.equal(view.queryByRole('textbox')===null,true);
  globalThis.__renderFail=false;globalThis.__renderAuth={...globalThis.__renderAuth,epoch:1,user:{username:'bob'}};view.rerender(tree());
  assert.equal(view.getByRole('textbox',{name:'초안'}).value,'');assert.equal(view.container.textContent.includes('PRIVATE_ALICE_DRAFT'),false);
});

test('authentication screens also retain a footer when rendering fails',()=>{
  globalThis.__renderFail=true;const view=render(tree('/login'));
  assert.equal(view.getByRole('alert')!==null,true);assert.equal(view.getAllByRole('contentinfo').length,1);
});

test('the root boundary covers auth-provider failures and fallback styles wrap at narrow widths',async()=>{
  const main=await readFile(new URL('../src/main.jsx',import.meta.url),'utf8');
  assert.match(main,/<RenderErrorBoundary>\s*<AuthProvider>/);
  const css=await readFile(new URL('../src/components/RenderErrorBoundary.css',import.meta.url),'utf8');
  assert.match(css,/overflow-wrap:anywhere/);assert.match(css,/flex-wrap:wrap/);
});
