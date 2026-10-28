import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {JSDOM} from 'jsdom';

const css = file => readFileSync(new URL('../src/' + file, import.meta.url), 'utf8');
const globalCss = css('index.css');
const commonCss = css('pages/common.css');
const editorCss = css('components/CodeEditor.css');

function inspect(source, markup, verify) {
  const dom = new JSDOM('<style>' + source + '</style>' + markup);
  try {
    const {document} = dom.window;
    const rules = [...document.styleSheets[0].cssRules];
    const declaration = (selector, property) => {
      const matches = rule => rule.selectorText === selector || rule.selectorText?.split(',').some(part => part.trim() === selector);
      const rule = property
        ? rules.find(rule => matches(rule) && rule.style.getPropertyValue(property))
        : rules.find(rule => rule.selectorText === selector) ?? rules.find(matches);
      assert.ok(rule, 'Missing CSS selector: ' + selector);
      return rule.style;
    };
    verify({dom, document, declaration});
  } finally {
    dom.window.close();
  }
}

function assertFrameless(style, label) {
  for (const side of ['top', 'right', 'bottom', 'left']) {
    // jsdom serializes a removed border as an empty side style/width, while
    // browsers can expose "none"/"0px". Neither may draw a line.
    const line = style.getPropertyValue('border-' + side + '-style');
    const width = style.getPropertyValue('border-' + side + '-width');
    assert.ok(line === '' || line === 'none' || parseFloat(width) === 0, label + ': visible ' + side + ' border');
  }
  assert.equal(style.outline, 'none', label + ': focus outline');
  assert.equal(style.boxShadow, 'none', label + ': inset or outer shadow');
}

function configure(field, state) {
  if (state === 'disabled') {
    if ('disabled' in field) field.disabled = true;
    else field.setAttribute('aria-disabled', 'true');
  }
  if (state === 'focus') {
    field.focus();
    assert.equal(field.matches(':focus-visible'), true);
  }
}

const textFields = '<input aria-label="기본"><input type="text"><input type="email"><input type="password"><input type="search"><input type="number"><input type="url"><input type="tel"><input type="date"><select><option>선택</option></select><textarea></textarea><div contenteditable="true" tabindex="0">내용</div><div contenteditable="plaintext-only" tabindex="0">내용</div><div contenteditable tabindex="0">내용</div>';

for (const state of ['normal', 'focus', 'disabled']) {
  test('unclassed text controls are borderless in every theme (' + state + ')', () => {
    for (const theme of ['dark', 'light']) {
      inspect(globalCss, '<main class="' + theme + '">' + textFields + '</main>', ({dom, document}) => {
        for (const field of document.querySelectorAll('input,select,textarea,[contenteditable]')) {
          configure(field, state);
          assertFrameless(dom.window.getComputedStyle(field), theme + ' ' + field.tagName + ' ' + state);
        }
      });
    }
  });
}

for (const name of ['Login', 'Signup', 'PasswordReset']) {
  const authCss = css('pages/' + name + '.css');
  for (const theme of ['dark', 'light']) {
    test(name + ' text inputs preserve one unchanged underline without outer frame or focus ring in ' + theme, () => {
      for (const state of ['normal', 'focus', 'disabled']) {
        inspect(globalCss + '\n' + authCss, '<main class="' + name + 'Page ' + theme + '"><section class="' + name + 'Box"><input type="email"><input type="password"><input inputmode="numeric"><button>계속</button></section></main>', ({dom, document}) => {
          for (const input of document.querySelectorAll('input')) {
            configure(input, state);
            const style = dom.window.getComputedStyle(input);
            const label = name + ' ' + theme + ' ' + state;
            for (const side of ['top', 'right', 'left']) {
              const line = style.getPropertyValue('border-' + side + '-style');
              const width = style.getPropertyValue('border-' + side + '-width');
              assert.ok(line === '' || line === 'none' || parseFloat(width) === 0, label + ': no outer ' + side + ' border');
            }
            assert.equal(style.borderBottomStyle, 'solid', label + ': underline remains visible');
            assert.equal(style.borderBottomWidth, '1px', label + ': underline width stays unchanged');
            assert.equal(style.borderBottomColor, theme === 'light' ? 'rgb(160, 160, 160)' : 'rgb(119, 119, 119)', label + ': underline color stays unchanged');
            assert.equal(style.outline, 'none', label + ': no focus outline');
            assert.equal(style.boxShadow, 'none', label + ': no focus shadow');
          }
          const button = document.querySelector('button');
          button.focus();
          assert.equal(dom.window.getComputedStyle(button).outline, '2px solid #64e6ca');
        });
      }
    });
  }
}

for (const theme of ['dark', 'light']) {
  test('shared form fields stay borderless without losing their dimensions (' + theme + ')', () => {
    for (const state of ['normal', 'focus', 'disabled']) {
      inspect(globalCss + '\n' + commonCss, '<main class="cw-page ' + theme + '"><input class="cw-input"><select class="cw-select"><option>1</option></select><textarea class="cw-textarea"></textarea><div contenteditable="true" tabindex="0">내용</div></main>', ({dom, document, declaration}) => {
        for (const selector of ['.cw-input', '.cw-select', '.cw-textarea']) {
          assert.equal(declaration(selector, 'border').getPropertyValue('border'), 'none');
        }
        for (const field of document.querySelectorAll('input,select,textarea,[contenteditable]')) {
          configure(field, state);
          const style = dom.window.getComputedStyle(field);
          assertFrameless(style, theme + ' shared ' + field.tagName + ' ' + state);
          if (field.tagName !== 'DIV') {
            assert.ok(Math.max(parseFloat(style.height) || 0, parseFloat(style.minHeight) || 0) >= 42, 'control hit area remains usable');
          }
        }
      });
    }
  });
}

test('buttons and links retain branded keyboard focus after text field borders are removed', () => {
  inspect(globalCss + '\n' + commonCss, '<main class="cw-page dark"><button>저장</button><a href="/">링크</a></main>', ({dom, document}) => {
    for (const control of document.querySelectorAll('button,a')) {
      control.focus();
      assert.equal(control.matches(':focus-visible'), true);
      assert.equal(dom.window.getComputedStyle(control).outline, '2px solid var(--accent-text)');
    }
  });
  inspect(globalCss + '\n' + css('pages/Login.css'), '<main class="LoginPage dark"><section class="LoginBox"><a href="/password-reset">비밀번호 찾기</a></section></main>', ({dom, document}) => {
    const link = document.querySelector('a');
    link.focus();
    assert.equal(dom.window.getComputedStyle(link).outline, '2px solid #64e6ca');
  });
});

test('native checkbox/radio checked states and keyboard focus are exempt from border removal', () => {
  for (const theme of ['dark', 'light']) {
    for (const checked of [false, true]) {
      for (const type of ['checkbox', 'radio']) {
        inspect(globalCss + '\n' + commonCss, '<main class="cw-page ' + theme + '"><input type="' + type + '"' + (checked ? ' checked' : '') + '></main>', ({dom, document}) => {
          const field = document.querySelector('input');
          field.focus();
          const style = dom.window.getComputedStyle(field);
          assert.equal(field.checked, checked);
          assert.notEqual(style.appearance, 'none');
          assert.notEqual(style.outline, 'none');
          assert.equal(style.outlineColor, 'var(--input-focus)');
          assert.notEqual(style.borderTopStyle, 'none');
        });
      }
    }
  }
});

test('login remember checkbox keeps 16px native control, checked accent and keyboard focus', () => {
  for (const checked of [false, true]) {
    inspect(globalCss + '\n' + css('pages/Login.css'), '<main class="LoginPage dark"><section class="LoginBox"><label class="login-remember"><input id="login-remember" type="checkbox"' + (checked ? ' checked' : '') + '>로그인 유지</label></section></main>', ({dom, document}) => {
      const checkbox = document.querySelector('input');
      checkbox.focus();
      const style = dom.window.getComputedStyle(checkbox);
      assert.equal(checkbox.checked, checked);
      assert.equal(style.width, '16px');
      assert.equal(style.height, '16px');
      assert.equal(style.margin, '0px');
      assert.equal(style.accentColor, '#64e6ca');
      assert.notEqual(style.appearance, 'none');
      assert.equal(dom.window.getComputedStyle(document.querySelector('label')).display, 'flex');
      assert.equal(checkbox.matches(':focus-visible'), true);
      assert.equal(style.outline, '2px solid var(--input-focus)');
    });
  }
});

test('CodeMirror outer/inner focus boundaries are removed without changing code accents', () => {
  inspect(globalCss + '\n' + commonCss + '\n' + editorCss, '<main class="cw-page dark"><div class="cw-editor-host"><div class="cm-editor cm-focused"><div class="cm-content" contenteditable="true" tabindex="0">코드</div></div></div></main>', ({dom, document, declaration}) => {
    const content = document.querySelector('.cm-content');
    content.focus();
    assertFrameless(dom.window.getComputedStyle(content), 'CodeMirror content');
    assertFrameless(dom.window.getComputedStyle(document.querySelector('.cm-editor')), 'CodeMirror focused editor');
    assert.equal(declaration('.cw-editor-host .cm-content').getPropertyValue('caret-color'), 'var(--accent-text)');
    assert.equal(declaration('.cw-editor-host .cm-cursor').getPropertyValue('border-left-color'), 'var(--accent-text)');
    assert.equal(declaration('.cw-editor-options input').getPropertyValue('accent-color'), 'var(--accent-text)');
    assert.match(declaration('.cw-editor-host .cm-selectionBackground').getPropertyValue('background'), /var\(--accent-text\)/);
    assert.equal(declaration('.cw-editor-host .cm-matchingBracket').getPropertyValue('outline'), '1px solid var(--accent-text)');
  });
});

test('editor font-size select has no resting, focused or disabled input boundary', () => {
  for (const state of ['normal', 'focus', 'disabled']) {
    inspect(globalCss + '\n' + commonCss + '\n' + editorCss, '<main class="cw-page dark"><label class="cw-editor-font-size"><select><option>13</option></select></label></main>', ({dom, document}) => {
      const select = document.querySelector('select');
      configure(select, state);
      assertFrameless(dom.window.getComputedStyle(select), 'editor font-size ' + state);
    });
  }
});
