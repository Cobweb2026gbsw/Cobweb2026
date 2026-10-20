const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/app.js'), 'utf8');
function section(start, end) {
  const from = source.indexOf(start), to = source.indexOf(end, from);
  assert.ok(from >= 0 && to > from, 'problem editor section changed');
  return source.slice(from, to);
}

class FakeNode {
  constructor(tagName = 'div') {
    this.tagName = tagName.toUpperCase();
    this.children = [];
    this.dataset = {};
    this.attributes = {};
    this.value = '';
    this._text = '';
    this.disabled = false;
  }
  set textContent(value) { this._text = String(value); this.replaceChildren(); }
  get textContent() { return this._text + this.children.map(child => child.textContent).join(''); }
  append(...children) {
    for (const child of children) {
      child.parentNode = this;
      this.children.push(child);
    }
  }
  replaceChildren(...children) {
    for (const child of this.children) child.parentNode = null;
    this.children = [];
    this.append(...children);
  }
  remove() {
    if (!this.parentNode) return;
    this.parentNode.children.splice(this.parentNode.children.indexOf(this), 1);
    this.parentNode = null;
  }
  setAttribute(name, value) { this.attributes[name] = String(value); }
  get options() { return this.children.filter(child => child.tagName === 'OPTION'); }
  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
  querySelectorAll(selector) {
    const result = [];
    const matches = node => {
      if (selector === '[data-field]') return Boolean(node.dataset.field);
      if (selector.startsWith('[data-field=')) return node.dataset.field === selector.slice(12, -1);
      if (selector === '.repeat-title') return node.className === 'repeat-title';
      if (selector === '.repeat-heading button') return node.tagName === 'BUTTON' && node.parentNode?.className === 'repeat-heading';
      if (selector === 'textarea' || selector === 'input' || selector === 'button') return node.tagName === selector.toUpperCase();
      return false;
    };
    const walk = node => { for (const child of node.children) { if (matches(child)) result.push(child); walk(child); } };
    walk(this);
    return result;
  }
  click() { this.clicked = true; }
}

function fixture() {
  const ids = ['example-rows', 'test-rows', 'solution-rows', 'example-count', 'test-count', 'solution-count',
    'add-example', 'add-test', 'add-solution'];
  const nodes = Object.fromEntries(ids.map(id => ['#' + id, new FakeNode()]));
  nodes['#author-form [name=executionMode]'] = new FakeNode('select');
  nodes['#author-form [name=executionMode]'].value = 'STDIN';
  const catalog = [
    ['PYTHON', ['STDIN', 'FUNCTION']], ['C', ['STDIN', 'FUNCTION']], ['CPP', ['STDIN', 'FUNCTION']],
    ['JAVA', ['STDIN', 'FUNCTION']], ['JAVASCRIPT', ['STDIN', 'FUNCTION']], ['TYPESCRIPT', ['STDIN', 'FUNCTION']],
    ['CSHARP', ['STDIN', 'FUNCTION']], ['GO', ['STDIN', 'FUNCTION']], ['RUST', ['STDIN', 'FUNCTION']],
    ['KOTLIN', ['STDIN', 'FUNCTION']], ['RUBY', ['STDIN', 'FUNCTION']], ['LLVM_IR', ['STDIN', 'FUNCTION']],
    ['SQL', ['SQL']]
  ].map(([id, modes]) => ({id, label: id, modes}));
  const notices = [], downloads = [];
  const context = {
    document: {createElement: tag => {
      const node = new FakeNode(tag);
      if (tag === 'a') node.click = () => downloads.push({name: node.download, href: node.href});
      return node;
    }},
    TextEncoder, TextDecoder, Blob,
    URL: {createObjectURL: () => 'blob:test', revokeObjectURL: () => {}},
    setTimeout: fn => fn()
  };
  vm.createContext(context);
  vm.runInContext(
    `let languages=${JSON.stringify(catalog)}; const $=selector=>nodes[selector]; function notice(text){notices.push(text);}\n` +
    section('const MAX_EXAMPLES=', "\n$('#new-problem').onclick=") +
    '\nthis.editor={buildProblemPayload,readSolutionFile,addTextRow,addSolutionRow,editorRows,updateRepeatCounts};',
    Object.assign(context, {nodes, notices}));
  return {editor: context.editor, nodes, notices, downloads, catalog};
}

function plain(value) { return JSON.parse(JSON.stringify(value)); }
function file(name, bytes) {
  return {name, size: bytes.byteLength, arrayBuffer: async () => Uint8Array.from(bytes).buffer};
}

async function main() {
  const {editor, nodes, downloads, catalog} = fixture();
  const fields = {title: '  두 수의 합  ', description: '합을 구합니다.', inputDescription: '두 정수',
    outputDescription: '합', constraints: '0 이상', rankType: 'INITIATE', rankInt: '5',
    timeLimitMs: '1000', memoryLimitMb: '256', executionMode: 'STDIN',
    tags: '수학, 구현, 수학', sourceName: '연습', sourceUrl: 'https://example.invalid/problem'};
  const examples = [{input: '1 2', output: '3', explanation: '첫 예제'}, {input: '4 5', output: '9', explanation: '둘째 예제'}];
  const tests = [{input: '7 8', expected: '15'}, {input: '9 1', expected: '10'}];
  const solutions = [{language: 'PYTHON', fileName: 'main.py', sourceCode: 'print(3)'},
    {language: 'C', fileName: 'main.c', sourceCode: 'int main(void){return 0;}'}];
  const payload = plain(editor.buildProblemPayload(fields, examples, tests, ['PYTHON', 'C'], solutions));
  assert.equal(payload.title, '두 수의 합');
  assert.deepEqual(payload.examples, examples);
  assert.deepEqual(payload.tests, tests);
  assert.deepEqual(payload.tags, ['수학', '구현']);
  assert.deepEqual(payload.languages, ['PYTHON', 'C']);
  assert.deepEqual(payload.referenceSolutions, solutions);
  assert.equal(payload.groupId, null);
  assert.equal(payload.visibility, 'PUBLIC');
  assert.equal(payload.rankInt, 5);
  assert.ok(!Object.hasOwn(payload, 'number'));
  assert.deepEqual(plain(editor.buildProblemPayload(fields, [], tests, ['PYTHON'], []).referenceSolutions), []);
  const groupPayload = plain(editor.buildProblemPayload({...fields, scope: 'GROUP', groupDestination: '7'}, [], tests, ['PYTHON'], []));
  assert.equal(groupPayload.groupId, 7);
  assert.equal(groupPayload.visibility, 'GROUP');
  assert.throws(() => editor.buildProblemPayload({...fields, scope: 'GROUP', groupDestination: ''}, [], tests, ['PYTHON'], []), /그룹을 선택/);
  assert.throws(() => editor.buildProblemPayload(fields, Array(21).fill(examples[0]), tests, ['PYTHON'], []), /최대 20개/);
  assert.throws(() => editor.buildProblemPayload(fields, examples, [], ['PYTHON'], []), /1~50개/);
  assert.throws(() => editor.buildProblemPayload(fields, examples, Array(51).fill(tests[0]), ['PYTHON'], []), /1~50개/);
  assert.throws(() => editor.buildProblemPayload(fields, examples, tests, ['SQL'], []), /허용 언어/);
  assert.throws(() => editor.buildProblemPayload(fields, examples, tests, ['PYTHON'], [solutions[0], solutions[0]]), /언어별로 하나/);
  assert.throws(() => editor.buildProblemPayload(fields, examples, tests, ['PYTHON'], [{...solutions[0], fileName: '../escape.py'}]), /경로 없이/);
  assert.throws(() => editor.buildProblemPayload(fields, examples, tests, ['PYTHON'], [{...solutions[0], fileName: 'a'.repeat(121)}]), /120자/);
  assert.throws(() => editor.buildProblemPayload(fields, examples, tests, ['PYTHON'], [{...solutions[0], sourceCode: 'x'.repeat(65537)}]), /64 KiB/);
  assert.throws(() => editor.buildProblemPayload(fields, examples, tests, ['PYTHON'], Array(14).fill(solutions[0])), /최대 13개/);
  assert.throws(() => editor.buildProblemPayload({...fields, tags: Array.from({length: 11}, (_, i) => 'tag' + i).join(',')}, examples, tests, ['PYTHON'], []), /태그는 최대 10개/);

  const unicode = new TextEncoder().encode('print("안녕")\n');
  assert.deepEqual(plain(await editor.readSolutionFile(file('한글.py', unicode))), {fileName: '한글.py', sourceCode: 'print("안녕")\n'});
  await assert.rejects(editor.readSolutionFile(file('large.py', new Uint8Array(65537))), /64 KiB/);
  await assert.rejects(editor.readSolutionFile(file('invalid.py', Uint8Array.of(0xff))), /UTF-8|encoded data/i);
  await assert.rejects(editor.readSolutionFile(file('../invalid.py', unicode)), /파일명은 경로 없이/);
  await assert.rejects(editor.readSolutionFile(file('binary.py', Uint8Array.of(0, 1, 2))), /바이너리/);

  editor.addTextRow('example', examples[0]);
  editor.addTextRow('example', examples[1]);
  editor.addTextRow('test', tests[0]);
  assert.equal(nodes['#test-rows'].children[0].querySelector('.repeat-heading button').disabled, true);
  editor.addTextRow('test', tests[1]);
  nodes['#example-rows'].children[0].querySelector('.repeat-heading button').onclick();
  assert.deepEqual(plain(editor.editorRows('example')), [examples[1]]);
  assert.equal(nodes['#example-rows'].children[0].querySelector('.repeat-title').textContent, '예제 1');
  assert.equal(nodes['#example-count'].textContent, '(1/20)');
  for (let i = 1; i < 20; i++) editor.addTextRow('example');
  assert.equal(nodes['#add-example'].disabled, true);
  assert.throws(() => editor.addTextRow('example'), /추가 한도/);
  for (let i = 2; i < 50; i++) editor.addTextRow('test');
  assert.equal(nodes['#add-test'].disabled, true);
  assert.throws(() => editor.addTextRow('test'), /추가 한도/);

  const solutionRow = editor.addSolutionRow({language: 'PYTHON', fileName: 'main.py', sourceCode: 'print(1)'});
  const attachment = solutionRow.querySelectorAll('input').find(input => input.type === 'file');
  attachment.files = [file('upload.py', unicode)];
  await attachment.onchange();
  assert.equal(solutionRow.querySelector('[data-field=fileName]').value, 'upload.py');
  assert.equal(solutionRow.querySelector('[data-field=sourceCode]').value, 'print("안녕")\n');
  const download = solutionRow.querySelectorAll('button').find(button => button.textContent === '코드 다운로드');
  download.onclick();
  assert.deepEqual(downloads, [{name: 'upload.py', href: 'blob:test'}]);
  assert.match(nodes['#solution-count'].textContent, /1\/13/);
  assert.doesNotMatch(source, /\.innerHTML\s*=/);
  assert.equal(catalog.length, 13);
  console.log('problem editor: payload, limits, row order, UTF-8 upload/download passed');
}

main().catch(error => { console.error(error); process.exitCode = 1; });
