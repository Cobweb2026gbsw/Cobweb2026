import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {JSDOM} from 'jsdom';

const source = path => readFileSync(new URL(`../${path}`, import.meta.url), 'utf8');
const indexCss = source('src/index.css');
const fontDirectory = '../public/assets/fonts/wanted-sans/';

function inspect(css, verify) {
  const dom = new JSDOM(`<style>${css}</style>`);
  try {
    const rules = [...dom.window.document.styleSheets[0].cssRules];
    const style = selector => {
      const rule = rules.find(rule => rule.selectorText === selector)
        ?? rules.find(rule => rule.selectorText?.split(',').some(part => part.trim() === selector));
      assert.ok(rule, `Missing selector ${selector}`);
      return rule.style;
    };
    verify({rules, style});
  } finally {
    dom.window.close();
  }
}

test('Wanted Sans is self-hosted with its official variable range and nonblocking display', () => {
  inspect(indexCss, ({rules, style}) => {
    const face = rules.find(rule => rule.type === 5).style;
    assert.equal(face.getPropertyValue('font-family'), '"Wanted Sans Variable"');
    assert.equal(face.getPropertyValue('font-style'), 'normal');
    assert.equal(face.getPropertyValue('font-weight'), '400 1000');
    assert.equal(face.getPropertyValue('font-display'), 'swap');
    assert.equal(face.getPropertyValue('src'), 'url("/assets/fonts/wanted-sans/WantedSansVariable.woff2") format("woff2-variations")');
    assert.match(style(':root').getPropertyValue('--font'), /^"Wanted Sans Variable",/);
    assert.equal(style('body').getPropertyValue('font-family'), 'var(--font)');
    for (const field of ['button', 'input', 'select', 'textarea']) {
      assert.equal(style(field).getPropertyValue('font-family'), 'inherit');
    }
  });
  assert.doesNotMatch(indexCss, /@import|url\(["']?https?:/i);
});

test('local font is the unmodified official v1.0.3 WOFF2 and ships with copyright/license', () => {
  const font = readFileSync(new URL(`${fontDirectory}WantedSansVariable.woff2`, import.meta.url));
  assert.equal(font.subarray(0, 4).toString('ascii'), 'wOF2');
  assert.equal(font.readUInt32BE(8), font.length);
  assert.equal(font.length, 1289292);
  assert.equal(createHash('sha256').update(font).digest('hex'), '4259e7e9a172e634c2cb419d793b84148990316341e910443e5d10965b2c8f16');
  const license = readFileSync(new URL(`${fontDirectory}OFL.txt`, import.meta.url), 'utf8');
  const notice = readFileSync(new URL(`${fontDirectory}NOTICE.txt`, import.meta.url), 'utf8');
  assert.match(license, /Copyright 2024 The Wanted Sans Project Authors/);
  assert.match(license, /SIL OPEN FONT LICENSE Version 1\.1/);
  assert.match(notice, /Copyright \(c\) 2024 Wanted Lab, Inc\., with Reserved Font Name Wanted Sans Variable/);
  assert.match(notice, /https:\/\/github\.com\/wanteddev\/wanted-sans\/releases\/tag\/v1\.0\.3/);
  // Vite copies public/ to both the development root and production output,
  // keeping the complete license beside the redistributed font file.
  assert.match(notice, /OFL\.txt/);
});

test('font and license use the public production asset route without widening authorization', () => {
  const security = readFileSync(new URL('../../src/main/java/com/drewdrew0414/global/config/SecurityConfig.java', import.meta.url), 'utf8');
  assert.match(security, /requestMatchers\(HttpMethod\.GET,[^\n]+"\/ui\/assets\/\*\*"[^\n]+\)\.permitAll\(\)/);
  assert.match(indexCss, /url\("\/assets\/fonts\/wanted-sans\/WantedSansVariable\.woff2"\)/);
  assert.doesNotMatch(indexCss, /url\("\/fonts\//);
});

test('header/footer logos use the same UI family and auth logos do not override it', () => {
  inspect(source('src/components/Header.css'), ({style}) => {
    assert.equal(style('.cw-header-logo').getPropertyValue('font-family'), 'var(--font)');
  });
  inspect(source('src/components/Footer.css'), ({style}) => {
    assert.equal(style('.cw-footer-logo').getPropertyValue('font-family'), 'var(--font)');
  });
  for (const [page, selector] of [['Login', '.LoginBox .login-logo'], ['Signup', '.SignupBox .signup-logo'], ['PasswordReset', '.PasswordResetBox .reset-logo']]) {
    inspect(source(`src/pages/${page}.css`), ({style}) => {
      assert.equal(style(selector).getPropertyValue('font-family'), '');
    });
  }
});

test('code editor, diagnostics and code/numeric utilities preserve monospace families', () => {
  inspect(source('src/pages/common.css'), ({style}) => {
    const mono = style('.cw-page').getPropertyValue('--mono');
    assert.match(mono, /^"JetBrains Mono", "D2Coding", ui-monospace/);
    assert.doesNotMatch(mono, /Wanted/);
    assert.equal(style('.cw-mono').getPropertyValue('font-family'), 'var(--mono)');
    assert.equal(style('.cw-code-lines').getPropertyValue('font-family'), 'var(--mono)');
  });
  inspect(source('src/components/CodeEditor.css'), ({style}) => {
    assert.equal(style('.cw-editor-host .cm-editor').getPropertyValue('font-family'), 'var(--mono,monospace)');
    assert.equal(style('.cw-editor-host .cm-scroller').getPropertyValue('font-family'), 'inherit');
    assert.equal(style('.cw-editor-host .cm-tooltip-autocomplete>ul').getPropertyValue('font-family'), 'var(--mono,monospace)');
  });
  inspect(source('src/components/ProblemComponents.css'), ({style}) => {
    assert.equal(style('.markdown-body code').getPropertyValue('font-family'), 'var(--mono,monospace)');
  });
  inspect(source('src/components/SubmissionDiagnostic.css'), ({style}) => {
    assert.equal(style('.cw-diagnostic-message').getPropertyValue('font-family'), 'var(--font-mono,monospace)');
  });
});
