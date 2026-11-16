#!/usr/bin/env node
/** run.sh 전용: 신뢰된 서버 설정을 origin으로 정규화하며, 잘못된 원문은 출력하지 않는다. */
import {readFileSync} from 'node:fs';

try {
  const input = readFileSync(0, 'utf8');
  // URL의 자동 공백 제거·역슬래시/경로 정규화로 잘못된 설정을 받아들이지 않는다.
  if (!input || input.length > 2048 || /[\u0000-\u0020\u007f\\%?#]/u.test(input)) throw new Error();
  const match = /^(https?):\/\/([^/]+)\/?$/i.exec(input);
  if (!match) throw new Error();
  const authority = match[2];
  if (authority.endsWith(':')) throw new Error();
  const port = authority.startsWith('[') ? /\]:(.*)$/.exec(authority)?.[1] : /:([^:]*)$/.exec(authority)?.[1];
  if (port !== undefined && (!/^[1-9][0-9]{0,4}$/.test(port) || Number(port) > 65535)) throw new Error();
  const url = new URL(input);
  if (!url.hostname || url.username || url.password || url.search || url.hash || url.pathname !== '/') throw new Error();
  if (['0.0.0.0', '[::]'].includes(url.hostname.toLowerCase())) throw new Error();
  const loopback = ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname.toLowerCase());
  if (url.protocol !== 'https:' && !(url.protocol === 'http:' && loopback)) throw new Error();
  // Node의 파싱 결과와 Java URI의 DNS 호스트 해석이 다르지 않도록 정상 DNS 레이블만 쓴다.
  if (!url.hostname.startsWith('[') && (url.hostname.length > 253 || !/^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)*[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.?$/i.test(url.hostname))) throw new Error();
  process.stdout.write(url.origin + '\n');
} catch {
  // URL에 잘못 복사한 userinfo/query 비밀값도 로그·프로세스 인자에 되돌려 주지 않는다.
  process.stderr.write('Invalid public base URL.\n');
  process.exitCode = 1;
}
