import {readdir} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {runTestProcess} from './test-process.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const available = (await readdir(new URL('../tests/', import.meta.url))).filter(name => name.endsWith('.test.mjs')).sort();
const requested = process.argv.slice(2);
if (requested.some(name => !available.includes(name))) throw new Error('tests/ 안의 정확한 테스트 파일 이름만 지정하세요.');
const files = requested.length ? requested : available;
for (const name of files) {
  console.log(`\n[검증] ${name} · 파일별 순차 실행 / 그룹 RSS 512MiB / 힙 192MiB / 20초 제한`);
  const result = await runTestProcess(['--test', '--test-concurrency=1', `tests/${name}`], {cwd: root});
  console.log(`[메모리] ${result.peakRssKiB ? Math.ceil(result.peakRssKiB / 1024) + 'MiB' : '200ms 미만 종료로 RSS 샘플 없음'}${result.reason ? ' · ' + result.reason : ''}`);
  if (result.code !== 0) {process.exitCode = 1; break;}
}
