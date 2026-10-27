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
  test(`search input uses one rounded parent focus boundary, not an inner rectangle (${theme})`,()=>{
    const dom=header(theme);
    try{
      const {document}=dom.window,input=document.querySelector('input'),form=document.querySelector('form');
      input.focus();
      assert.equal(input.matches(':focus-visible'),true);
      assert.equal(dom.window.getComputedStyle(input).outline,'none');
      const parent=dom.window.getComputedStyle(form);
      assert.equal(parent.borderRadius,'999px');
      // jsdom은 border-color의 custom property를 실제 색으로 해석하지 않습니다.
      // focus 선택자의 선언과 실제로 계산되는 바깥 shadow를 함께 확인합니다.
      const focusRule=[...document.styleSheets[0].cssRules].find(rule=>rule.selectorText==='.cw-header-search:focus-within');
      assert.equal(focusRule.style.getPropertyValue('border-color'),'var(--h-accent)');
      assert.equal(parent.boxShadow,'0 0 0 1px var(--h-accent)');
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

test('compact header keeps its 1250px menu boundary and bounds narrow phone account links',()=>{
  const dom=header('light');
  try{
    const rules=[...dom.window.document.styleSheets[0].cssRules];
    const compact=rules.find(rule=>rule.conditionText==='(max-width: 1250px)');
    const style=selector=>[...compact.cssRules].find(rule=>rule.selectorText===selector).style;
    assert.equal(style('.cw-header nav').getPropertyValue('display'),'none');
    assert.equal(style('.cw-header-search').getPropertyValue('display'),'none');
    assert.equal(style('.cw-menu-toggle').getPropertyValue('display'),'inline-flex');
    assert.equal(style('.cw-header nav.open').getPropertyValue('display'),'flex');
    assert.equal(style('.cw-header nav.open').getPropertyValue('left'),'0');
    assert.equal(style('.cw-header nav.open').getPropertyValue('right'),'0');
    const phone=rules.find(rule=>rule.conditionText==='(max-width: 380px)');
    const account=[...phone.cssRules].find(rule=>rule.selectorText==='.cw-header-right > a');
    assert.equal(account.style.getPropertyValue('max-width'),'48px');
  }finally{dom.window.close();}
});
