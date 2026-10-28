import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {JSDOM} from 'jsdom';

const css=readFileSync(new URL('../src/components/Header.css',import.meta.url),'utf8');
function header(theme){
  return new JSDOM(`<style>${css}</style><header class="cw-header ${theme}">
    <form class="cw-header-search"><input aria-label="문제 검색"><button>검색</button></form>
    <a href="/login">로그인</a></header>`);
}

for(const theme of ['dark','light']){
  test(`search input and its rounded parent remain borderless before and during focus (${theme})`,()=>{
    const dom=header(theme);
    try{
      const {document}=dom.window,input=document.querySelector('input'),form=document.querySelector('form');
      const rules=[...document.styleSheets[0].cssRules];
      for(const selector of ['.cw-header-search','.cw-header-search:focus-within','.cw-header-search input']){
        const declaration=rules.find(rule=>rule.selectorText===selector).style;
        assert.equal(declaration.getPropertyValue('border'),'none');
        assert.equal(declaration.getPropertyValue('outline'),'none');
        assert.equal(declaration.getPropertyValue('box-shadow'),'none');
      }
      for(const focused of [false,true,false]){
        if(focused)input.focus();else input.blur();
        assert.equal(input.matches(':focus-visible'),focused);
        const style=dom.window.getComputedStyle(input),parent=dom.window.getComputedStyle(form);
        assert.equal(style.outline,'none');
        assert.equal(style.boxShadow,'none');
        assert.equal(parent.borderRadius,'999px');
        assert.equal(parent.outline,'none');
        assert.equal(parent.boxShadow,'none');
      }
    }finally{dom.window.close();}
  });
}

test('search button and other header links retain visible keyboard focus',()=>{
  const dom=header('dark');
  try{
    for(const node of dom.window.document.querySelectorAll('button,a')){
      node.focus();
      const style=dom.window.getComputedStyle(node);
      assert.equal(node.matches(':focus-visible'),true);
      assert.equal(style.outline,'2px solid var(--h-accent)');
      assert.equal(style.outlineOffset,'2px');
    }
  }finally{dom.window.close();}
});

test('desktop spacing separates logo, navigation items and search without rigid left width',()=>{
  const dom=header('dark');
  try{
    const rules=[...dom.window.document.styleSheets[0].cssRules];
    const style=selector=>rules.find(rule=>rule.selectorText===selector).style;
    assert.equal(style('.cw-header').getPropertyValue('gap'),'32px');
    assert.equal(style('.cw-header').getPropertyValue('min-height'),'88px');
    assert.equal(style('.cw-header').getPropertyValue('height'),'');
    assert.equal(style('.cw-header-left').getPropertyValue('gap'),'36px');
    assert.equal(style('.cw-header nav').getPropertyValue('gap'),'24px');
    // 관리자 메뉴가 더해져도 검색창/계정 영역을 밀지 않고 nav 안에서 줄바꿈합니다.
    assert.equal(style('.cw-header-left').getPropertyValue('flex'),'0 1 auto');
    assert.equal(style('.cw-header-left').getPropertyValue('min-width'),'0');
    assert.equal(style('.cw-header nav').getPropertyValue('flex-wrap'),'wrap');
    assert.equal(style('.cw-header nav a').getPropertyValue('white-space'),'nowrap');
    assert.equal(style('.cw-header-search').getPropertyValue('min-width'),'180px');
    assert.equal(style('.cw-header-right > a').getPropertyValue('max-width'),'160px');
  }finally{dom.window.close();}
});

test('compact header always exposes navigation in a second row without a menu overlay',()=>{
  const dom=header('light');
  try{
    const rules=[...dom.window.document.styleSheets[0].cssRules];
    const compact=rules.find(rule=>rule.conditionText==='(max-width: 1250px)');
    const style=selector=>[...compact.cssRules].find(rule=>rule.selectorText===selector).style;
    assert.equal(style('.cw-header').getPropertyValue('display'),'grid');
    assert.equal(style('.cw-header').getPropertyValue('grid-template-rows'),'auto auto');
    assert.equal(style('.cw-header-left').getPropertyValue('display'),'contents');
    assert.equal(style('.cw-header nav').getPropertyValue('display'),'flex');
    assert.equal(style('.cw-header nav').getPropertyValue('grid-row'),'2');
    assert.equal(style('.cw-header nav').getPropertyValue('grid-column'),'1 / -1');
    assert.equal(style('.cw-header nav').getPropertyValue('flex-wrap'),'nowrap');
    assert.equal(style('.cw-header nav').getPropertyValue('overflow-x'),'auto');
    assert.equal(style('.cw-header nav a').getPropertyValue('flex'),'none');
    assert.equal(style('.cw-header-logo').getPropertyValue('grid-row'),'1');
    assert.equal(style('.cw-header-right').getPropertyValue('grid-row'),'1');
    assert.equal(style('.cw-header-search').getPropertyValue('display'),'none');
    assert.doesNotMatch(css,/\.cw-menu-toggle|nav\.open|nav\s*\{\s*display:\s*none/);
    const phone=rules.find(rule=>rule.conditionText==='(max-width: 380px)');
    const account=[...phone.cssRules].find(rule=>rule.selectorText==='.cw-header-right > a');
    assert.equal(account.style.getPropertyValue('max-width'),'48px');
  }finally{dom.window.close();}
});
