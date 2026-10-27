import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {readFile} from 'node:fs/promises';
import {build} from 'esbuild';
import {JSDOM} from 'jsdom';

const require=createRequire(import.meta.url);
const dom=new JSDOM('<!doctype html><html><head></head><body></body></html>',{url:'http://localhost:8080/'});
Object.defineProperty(globalThis,'navigator',{value:dom.window.navigator,configurable:true});
for(const name of ['window','document','HTMLElement','Event','MutationObserver'])globalThis[name]=dom.window[name];
globalThis.IS_REACT_ACT_ENVIRONMENT=true;
const React=require('react'),{render,cleanup}=require('@testing-library/react'),{MemoryRouter}=require('react-router-dom');
const result=await build({entryPoints:['src/pages/Dashboard.jsx'],bundle:true,write:false,platform:'node',format:'cjs',packages:'external',jsx:'automatic',loader:{'.css':'empty'},plugins:[{name:'dashboard-contracts',setup(builder){
  builder.onResolve({filter:/\/context\/AuthContext(?:\.jsx)?$/},()=>({path:'auth',namespace:'fixture'}));
  builder.onResolve({filter:/\/lib\/api(?:\.js)?$/},()=>({path:'api',namespace:'fixture'}));
  builder.onLoad({filter:/.*/,namespace:'fixture'},args=>({contents:args.path==='auth'?'export const useAuth=()=>({user:{username:"tester"},epoch:0});':'export const isStale=()=>false;export const api=async path=>path==="/api/me"?{solved_count:0,submit_count:0,solvedProblems:[]}:[];'}));
}}]});
const module={exports:{}};new Function('require','module','exports',result.outputFiles[0].text)(require,module,module.exports);
const Dashboard=module.exports.default;
test.afterEach(()=>cleanup());
test.after(()=>dom.window.close());

test('dashboard empty solved list keeps a themed, accessible profile link in both themes',async()=>{
  for(const dark of [true,false]){
    const view=render(React.createElement(MemoryRouter,null,React.createElement(Dashboard,{dark})));
    const link=await view.findByRole('link',{name:'프로필에서 더 보기',exact:true});
    assert.equal(link.getAttribute('href'),'/me');assert.equal(link.classList.contains('cw-text-link'),true);
    assert.equal(link.classList.contains('db-card-link'),true);assert.equal(link.querySelector('[aria-hidden=true]').textContent,'→');
    assert.equal(view.container.querySelector('main').classList.contains(dark?'dark':'light'),true);
    cleanup();
  }
});

test('ordinary and visited links share theme color without overriding dedicated button/card rules',async()=>{
  const style=document.createElement('style');style.textContent=await readFile(new URL('../src/pages/common.css',import.meta.url),'utf8');document.head.append(style);
  const rule=[...style.sheet.cssRules].find(row=>row.selectorText==='.cw-page :where(a:link, a:visited)');
  assert.ok(rule);assert.equal(rule.style.color,'var(--accent-text)');assert.equal(rule.style.getPropertyPriority('color'),'');
  const textRule=[...style.sheet.cssRules].find(row=>row.selectorText==='.cw-text-link');
  assert.ok(textRule);assert.equal(textRule.style.getPropertyValue('text-decoration'),'none');
  const buttonRule=[...style.sheet.cssRules].find(row=>row.selectorText==='.cw-btn');
  assert.ok(buttonRule);assert.equal(buttonRule.style.color,'var(--text)');style.remove();
});
