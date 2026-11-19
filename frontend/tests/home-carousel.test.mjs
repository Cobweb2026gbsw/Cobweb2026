import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {build} from 'esbuild';
import {JSDOM} from 'jsdom';
import {rememberProblem} from '../src/lib/problemNavigation.js';

const require=createRequire(import.meta.url);
const dom=new JSDOM('<!doctype html><html><body></body></html>',{url:'http://localhost:5174/'});
Object.defineProperty(globalThis,'navigator',{value:dom.window.navigator,configurable:true});
for(const key of ['window','document','HTMLElement','Event','MutationObserver'])globalThis[key]=dom.window[key];
globalThis.IS_REACT_ACT_ENVIRONMENT=true;
const React=require('react'),{render,fireEvent,cleanup,act}=require('@testing-library/react'),{MemoryRouter}=require('react-router-dom');
const bundle=await build({entryPoints:['src/pages/Main.jsx'],bundle:true,write:false,platform:'node',format:'cjs',packages:'external',jsx:'automatic',loader:{'.css':'empty'},plugins:[{name:'home-auth',setup(builder){
  builder.onResolve({filter:/\/context\/AuthContext$/},()=>({path:'auth',namespace:'home-fixture'}));
  builder.onLoad({filter:/.*/,namespace:'home-fixture'},()=>({contents:'export const useAuth=()=>globalThis.__homeAuth;'}));
}}]});
const module={exports:{}};new Function('require','module','exports',bundle.outputFiles[0].text)(require,module,module.exports);
const Main=module.exports.default;
let intervals,timeouts,motionListeners,media,observer,clockId,intervalDelay;
function tree(){return React.createElement(MemoryRouter,null,React.createElement(Main,{dark:true}));}
function active(view){return view.getByRole('group',{name:/^[1-4] \/ 4/});}
function tick(){act(()=>{for(const fn of [...intervals.values()])fn();});}
test.beforeEach(()=>{
  globalThis.__homeAuth={user:{id:1,username:'tester'},loading:false};
  window.localStorage.clear();intervals=new Map();timeouts=new Map();motionListeners=new Set();clockId=0;
  window.setInterval=(fn,delay)=>{intervalDelay=delay;intervals.set(++clockId,fn);return clockId;};window.clearInterval=id=>intervals.delete(id);
  window.setTimeout=fn=>{timeouts.set(++clockId,fn);return clockId;};window.clearTimeout=id=>timeouts.delete(id);
  media={matches:false,addEventListener:(_,fn)=>motionListeners.add(fn),removeEventListener:(_,fn)=>motionListeners.delete(fn)};
  window.matchMedia=()=>media;
  window.IntersectionObserver=class{constructor(fn){observer=fn;}observe(){}disconnect(){observer=null;}};
  Object.defineProperty(document,'visibilityState',{configurable:true,value:'visible'});
});
test.afterEach(()=>cleanup());
test.after(()=>dom.window.close());

test('four destinations are split into slides, inactive links are inert and the control bar is absent',()=>{
  const view=render(tree());
  assert.match(active(view).textContent,/ONLINE JUDGE.*CobWeb/s);
  assert.equal(view.getAllByRole('link').length,3);
  for(const slide of view.container.querySelectorAll('[aria-roledescription="슬라이드"][aria-hidden=true]'))assert.ok(slide.hasAttribute('inert'));
  assert.equal(view.queryByRole('group',{name:'메인 슬라이드 선택'}),null);
  assert.equal(view.queryAllByRole('button').length,0);
  for(const path of ['/submissions','/ranking','/forum']){
    tick();
    assert.equal(view.getAllByRole('link').length,1);
    assert.equal(view.getByRole('link').getAttribute('href'),path);
  }
});

test('recent problem remains account scoped and guests see the level route',()=>{
  rememberProblem('user:1',1000,'/problems?page=2');
  let view=render(tree());
  assert.equal(view.getByRole('link',{name:'이어서 풀기'}).getAttribute('href'),'/problems/1000?from=%2Fproblems%3Fpage%3D2');
  cleanup();globalThis.__homeAuth={user:null,loading:false};view=render(tree());
  assert.ok(view.getByRole('link',{name:'입문 문제 시작'}).getAttribute('href').includes('difficulty=INITIATE'));
  assert.equal(view.queryByText(/마지막으로 열어본 문제/),null);
  tick();
  assert.equal(view.getByRole('link').getAttribute('href'),'/steps');
});

test('automatic loop advances every 3 seconds and returns without exposing hidden duplicate links',()=>{
  const view=render(tree());
  assert.equal(intervals.size,1);
  assert.equal(intervalDelay,3000);
  for(const label of ['내 제출','랭킹','커뮤니티','문제 시작']){tick();assert.ok(active(view).getAttribute('aria-label').endsWith(label));}
  assert.equal(view.container.querySelector('.home-track').style.transform,'translateX(-400%)');
  assert.equal(view.getAllByRole('link').length,3);
  act(()=>{for(const fn of [...timeouts.values()])fn();});
  assert.equal(view.container.querySelector('.home-track').style.transform,'translateX(-0%)');
  assert.equal(view.container.querySelector('.home-track').style.transition,'none');
});

test('hover keeps 3-second playback and keyboard focus pauses only until focus leaves',()=>{
  const view=render(tree()),region=view.getByRole('region',{name:'CobWeb 소개'});
  fireEvent.mouseEnter(region);assert.equal(intervals.size,1);
  fireEvent.mouseLeave(region);assert.equal(intervals.size,1);
  fireEvent.focus(view.getByRole('link',{name:'전체 문제 탐색'}));assert.equal(intervals.size,0);
  fireEvent.blur(view.getByRole('link',{name:'전체 문제 탐색'}),{relatedTarget:document.body});assert.equal(intervals.size,1);
  tick();
  assert.match(active(view).getAttribute('aria-label'),/내 제출$/);
});

test('hidden tabs, offscreen carousel and reduced motion do not auto-advance',()=>{
  const view=render(tree());
  act(()=>observer([{isIntersecting:false}]));assert.equal(intervals.size,0);
  act(()=>observer([{isIntersecting:true}]));assert.equal(intervals.size,1);
  Object.defineProperty(document,'visibilityState',{configurable:true,value:'hidden'});
  fireEvent(document,new Event('visibilitychange'));assert.equal(intervals.size,0);
  Object.defineProperty(document,'visibilityState',{configurable:true,value:'visible'});
  fireEvent(document,new Event('visibilitychange'));assert.equal(intervals.size,1);
  act(()=>{media.matches=true;for(const fn of motionListeners)fn({matches:true});});
  assert.equal(intervals.size,0);
  assert.equal(view.queryByRole('button',{name:/자동 재생/}),null);
  tick();
  assert.match(active(view).getAttribute('aria-label'),/문제 시작$/);
  assert.equal(view.container.querySelector('.home-track').style.transition,'none');
  view.unmount();assert.equal(intervals.size,0);assert.equal(timeouts.size,0);assert.equal(motionListeners.size,0);
});

test('reduced motion on first load disables auto-play and animation',()=>{
  media.matches=true;
  const view=render(tree());assert.equal(intervals.size,0);
  tick();
  assert.equal(view.container.querySelector('.home-track').style.transition,'none');
  assert.match(active(view).getAttribute('aria-label'),/문제 시작$/);
});

test('horizontal touch swipes change slides, vertical scrolling and action links do not',()=>{
  const view=render(tree()),viewport=view.container.querySelector('.home-viewport');
  function pointer(target,type,x,y){const event=new Event(type,{bubbles:true});Object.assign(event,{pointerType:'touch',pointerId:1,clientX:x,clientY:y});fireEvent(target,event);}
  pointer(viewport,'pointerdown',200,100);pointer(viewport,'pointerup',180,230);
  assert.match(active(view).getAttribute('aria-label'),/문제 시작$/);
  const link=view.getByRole('link',{name:'전체 문제 탐색'});
  pointer(link,'pointerdown',200,100);pointer(link,'pointerup',70,100);
  assert.match(active(view).getAttribute('aria-label'),/문제 시작$/);
  pointer(viewport,'pointerdown',200,100);pointer(viewport,'pointerup',70,110);
  assert.match(active(view).getAttribute('aria-label'),/내 제출$/);assert.equal(intervals.size,1);
  pointer(viewport,'pointerdown',70,100);pointer(viewport,'pointerup',200,110);
  assert.match(active(view).getAttribute('aria-label'),/문제 시작$/);
});
