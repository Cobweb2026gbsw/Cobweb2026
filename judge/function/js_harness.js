const fs = require('node:fs');
const values = fs.readFileSync(0, 'utf8').trim().split(/\s+/).map(Number);
if (values.length !== 227) throw new Error('Invalid FUNCTION input');
const matrix = Array.from({length: 15}, (_, row) => values.slice(row * 15, row * 15 + 15));
const target = values.slice(225);
const source = fs.readFileSync('/source/main.js', 'utf8');
// 선언형 sol과 CommonJS export를 모두 허용합니다. 고정 이미지와 컨테이너 제한은 동일합니다.
const submission = {exports: {}};
const sol = new Function('module', 'exports', 'require', source + '\nreturn typeof sol === "function" ? sol : (module.exports.sol || module.exports);')
    (submission, submission.exports, require);
if (typeof sol !== 'function') throw new Error('sol function is missing');
const result = sol(matrix, target);
if (typeof result !== 'boolean') throw new Error('sol must return boolean');
console.log(result);
