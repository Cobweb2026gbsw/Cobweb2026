import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
import {build} from 'esbuild';
import {JSDOM} from 'jsdom';

const require=createRequire(import.meta.url),root=fileURLToPath(new URL('../',import.meta.url));
const dom=new JSDOM('<!doctype html><html><body></body></html>',{url:'http://localhost:8080/'});
Object.defineProperty(globalThis,'navigator',{value:dom.window.navigator,configurable:true});
for(const key of ['window','document','HTMLElement','Event','MutationObserver'])globalThis[key]=dom.window[key];
globalThis.IS_REACT_ACT_ENVIRONMENT=true;
const React=require('react'),{render,fireEvent,waitFor,cleanup,within}=require('@testing-library/react');
const {MemoryRouter,useLocation}=require('react-router-dom');
const built=await build({stdin:{contents:"export {default} from './src/components/Header.jsx';",resolveDir:root,sourcefile:'header-navigation-test.jsx'},bundle:true,write:false,platform:'node',format:'cjs',packages:'external',jsx:'automatic',loader:{'.css':'empty'},plugins:[{name:'header-navigation-contracts',setup(builder){
  builder.onResolve({filter:/(?:^|\/)api(?:\.js)?$/},()=>({path:'api',namespace:'header-contract'}));
  builder.onResolve({filter:/\/context\/AuthContext(?:\.jsx)?$/},()=>({path:'auth',namespace:'header-contract'}));
  builder.onLoad({filter:/.*/,namespace:'header-contract'},args=>({contents:args.path==='api'?'export const getSessionEpoch=()=>globalThis.__headerAuth.epoch;':'export const useAuth=()=>globalThis.__headerAuth;'}));
}}]});
const module={exports:{}};new Function('require','module','exports',built.outputFiles[0].text)(require,module,module.exports);
const Header=module.exports.default;
function Location(){const location=useLocation();return React.createElement('output',{'data-testid':'location'},location.pathname+location.search);}
function mount(dark=true,setDark=()=>{}){return render(React.createElement(MemoryRouter,{initialEntries:['/groups']},React.createElement(Header,{dark,setDark}),React.createElement(Location)));}
test.beforeEach(()=>{globalThis.__headerAuth={user:null,epoch:0,permissions:{canCreate:false,canManage:false},logout:async()=>{}};});
test.afterEach(()=>cleanup());
test.after(()=>dom.window.close());

for(const dark of [true,false]){
  test(`anonymous navigation is always present without a menu toggle (${dark?'dark':'light'})`,()=>{
    const view=mount(dark),nav=within(view.getByRole('navigation',{name:'주 메뉴'}));
    for(const name of ['문제','그룹','코테','커뮤니티','공지'])assert.ok(nav.getByRole('link',{name,exact:true}));
    assert.equal(view.queryByRole('button',{name:'메뉴',exact:true}),null);
    assert.equal(view.container.querySelector('[aria-expanded],[aria-controls="main-nav"]'),null);
    assert.equal(nav.queryByRole('link',{name:'문제 관리'}),null);
    fireEvent.click(nav.getByRole('link',{name:'커뮤니티'}));
    assert.equal(view.getByTestId('location').textContent,'/forum');
    assert.equal(view.getByRole('navigation',{name:'주 메뉴'}).hasAttribute('hidden'),false);
  });
  test(`administrator links remain available in the same navigation (${dark?'dark':'light'})`,()=>{
    globalThis.__headerAuth={...globalThis.__headerAuth,user:{username:'admin'},permissions:{canCreate:true,canManage:true}};
    const view=mount(dark),nav=within(view.getByRole('navigation',{name:'주 메뉴'}));
    assert.equal(nav.getAllByRole('link').length,9);
    for(const [name,path] of [['내 활동','/dashboard'],['문제 제작','/problems/new'],['문제 관리','/admin/review'],['글 관리','/admin/community']]){
      assert.equal(nav.getByRole('link',{name}).getAttribute('href'),path);
    }
    assert.equal(view.queryByRole('button',{name:'메뉴',exact:true}),null);
  });
}

test('search, theme switch and logout keep their previous behavior without menu state',async()=>{
  let logoutCount=0,selectedTheme;
  globalThis.__headerAuth={...globalThis.__headerAuth,user:{username:'tester'},logout:async()=>{logoutCount++;}};
  const view=mount(true,next=>{selectedTheme=next;});
  fireEvent.change(view.getByRole('textbox',{name:'문제 검색'}),{target:{value:'  Hello World!  '}});
  fireEvent.submit(view.getByRole('search'));
  assert.equal(view.getByTestId('location').textContent,'/problems?q=Hello%20World!');
  fireEvent.click(view.getByRole('button',{name:'테마 전환'}));
  assert.equal(selectedTheme,false);
  fireEvent.click(view.getByRole('button',{name:'로그아웃'}));
  await waitFor(()=>assert.equal(view.getByTestId('location').textContent,'/'));
  assert.equal(logoutCount,1);
});
