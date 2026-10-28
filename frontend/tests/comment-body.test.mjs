import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFile } from 'node:fs/promises';
import { build } from 'esbuild';
import { JSDOM } from 'jsdom';

const require = createRequire(import.meta.url);
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' });
Object.defineProperty(globalThis, 'navigator', { value: dom.window.navigator, configurable: true });
for (const name of ['window', 'document', 'HTMLElement', 'Event', 'MutationObserver']) globalThis[name] = dom.window[name];
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
const React = require('react');
const { render, fireEvent, cleanup, act } = require('@testing-library/react');
const bundle = await build({
  entryPoints: ['src/components/CommentBody.jsx'], bundle: true, write: false, platform: 'node',
  format: 'cjs', packages: 'external', jsx: 'automatic', loader: { '.css': 'empty' },
});
const module = { exports: {} };
new Function('require', 'module', 'exports', bundle.outputFiles[0].text)(require, module, module.exports);
const CommentBody = module.exports.default;
test.afterEach(() => { cleanup(); delete globalThis.ResizeObserver; });
test.after(() => dom.window.close());

test('short comments remain readable without a redundant expand button', () => {
  const view = render(React.createElement(CommentBody, { body: '짧은 댓글입니다.' }));
  assert.equal(view.getByText('짧은 댓글입니다.').tagName, 'P');
  assert.equal(view.queryByRole('button'), null);
});

test('long comments toggle six-line preview and full original text without a request', () => {
  const body = '원문🙂<script>alert(1)</script> '.repeat(30);
  const view = render(React.createElement(CommentBody, { body }));
  const button = view.getByRole('button', { name: '…더보기' });
  const paragraph = document.getElementById(button.getAttribute('aria-controls'));
  assert.equal(paragraph.textContent, body);
  assert.equal(paragraph.classList.contains('is-collapsed'), true);
  assert.equal(button.getAttribute('aria-expanded'), 'false');
  assert.equal(paragraph.querySelector('script'), null);
  fireEvent.click(button);
  assert.equal(view.getByRole('button', { name: '접기' }).getAttribute('aria-expanded'), 'true');
  assert.equal(paragraph.classList.contains('is-collapsed'), false);
  assert.equal(paragraph.textContent, body);
  fireEvent.click(button);
  assert.equal(button.textContent, '…더보기');
  assert.equal(paragraph.classList.contains('is-collapsed'), true);
});

test('more than six explicit lines also expose an expand control', () => {
  const view = render(React.createElement(CommentBody, { body: '1\r\n2\r3\n4\n5\n6\n7' }));
  assert.ok(view.getByRole('button', { name: '…더보기' }));
});

test('exact preview thresholds avoid unnecessary controls and preserve emoji', () => {
  const view = render(React.createElement(CommentBody, { body: '🙂'.repeat(300) }));
  assert.equal(view.queryByRole('button'), null);
  view.rerender(React.createElement(CommentBody, { body: '🙂'.repeat(301) }));
  assert.ok(view.getByRole('button', { name: '…더보기' }));
  assert.equal(Array.from(view.container.querySelector('p').textContent).length, 301);
});

test('narrow-screen wrapped overflow is detected and resize observer is disconnected', () => {
  let resized, disconnected = 0;
  globalThis.ResizeObserver = class {
    constructor(callback) { resized = callback; }
    observe() {}
    disconnect() { disconnected += 1; }
  };
  const view = render(React.createElement(CommentBody, { body: '화면 폭 때문에 여러 줄이 되는 댓글' }));
  const paragraph = view.container.querySelector('p');
  Object.defineProperty(paragraph, 'scrollHeight', { configurable: true, value: 220 });
  Object.defineProperty(paragraph, 'clientHeight', { configurable: true, value: 160 });
  act(() => resized());
  fireEvent.click(view.getByRole('button', { name: '…더보기' }));
  assert.ok(view.getByRole('button', { name: '접기' }));
  assert.ok(disconnected > 0);
  view.unmount();
});

test('comment controls have independent expansion and unique accessible targets', () => {
  const view = render(React.createElement(React.Fragment, null,
    React.createElement(CommentBody, { body: '부모'.repeat(180) }),
    React.createElement(CommentBody, { body: '답글'.repeat(180) })));
  const buttons = view.getAllByRole('button', { name: '…더보기' });
  assert.notEqual(buttons[0].getAttribute('aria-controls'), buttons[1].getAttribute('aria-controls'));
  fireEvent.click(buttons[1]);
  assert.equal(buttons[0].getAttribute('aria-expanded'), 'false');
  assert.equal(buttons[1].getAttribute('aria-expanded'), 'true');
});

test('a long comment fitting six visible lines does not keep a no-op expand control', () => {
  let resized;
  globalThis.ResizeObserver = class { constructor(callback) { resized = callback; } observe() {} disconnect() {} };
  const view = render(React.createElement(CommentBody, { body: 'a'.repeat(301) }));
  const paragraph = view.container.querySelector('p');
  Object.defineProperty(paragraph, 'scrollHeight', { configurable: true, value: 120 });
  Object.defineProperty(paragraph, 'clientHeight', { configurable: true, value: 120 });
  act(() => resized());
  assert.equal(view.queryByRole('button'), null);
});

test('six-line clamp is scoped to collapsed comment content, not post bodies or edit fields', async () => {
  const css = await readFile('src/components/CommentBody.css', 'utf8');
  assert.match(css, /\.fm-comment-body\.is-collapsed\s*\{/);
  assert.match(css, /-webkit-line-clamp:\s*6;/);
  assert.match(css, /overflow:\s*hidden;/);
  assert.doesNotMatch(css, /(?:^|\})\s*(?:\.fm-body|textarea)\s*\{/);
});
