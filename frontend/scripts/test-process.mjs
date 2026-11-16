import {spawn, execFileSync} from 'node:child_process';

// 테스트의 이벤트 루프가 멈춰도 별도 부모 프로세스가 시간/메모리/출력을 제한합니다.
// 힙 한도만으로 제한되지 않는 Buffer·네이티브 메모리도 프로세스 그룹 RSS로 검사합니다.
export function runTestProcess(args, {cwd, timeoutMs = 20000, maxRssKiB = 512 * 1024,
  maxOutputBytes = 2 * 1024 * 1024, output = process.stdout} = {}) {
  if (process.platform === 'win32') throw new Error('메모리 감시 테스트는 macOS/Linux에서 실행해 주세요.');
  return new Promise(resolve => {
    const child = spawn(process.execPath, ['--max-old-space-size=192', ...args], {
      cwd, detached: true, stdio: ['ignore', 'pipe', 'pipe'],
      env: {...process.env, NODE_OPTIONS: '--max-old-space-size=192 --max-semi-space-size=16',
        DEBUG_PRINT_LIMIT: '1500'},
    });
    let reason = '', outputBytes = 0, peakRssKiB = 0, closed = false, forced;
    const signal = value => {if (!child.pid) return; try {process.kill(-child.pid, value);} catch (error) {if (error.code !== 'ESRCH') throw error;}};
    const stop = message => {
      if (reason || closed) return;
      reason = message; signal('SIGTERM');
      forced = setTimeout(() => signal('SIGKILL'), 300);
    };
    const interrupt = () => stop('사용자가 검증을 중단했습니다.');
    process.once('SIGINT', interrupt); process.once('SIGTERM', interrupt);
    const watchdog = setTimeout(() => stop(`외부 시간 제한 ${timeoutMs}ms 초과`), timeoutMs);
    const memory = setInterval(() => {
      if (!child.pid || closed || reason) return;
      try {
        const rows = execFileSync('ps', ['-eo', 'pid=,pgid=,rss='], {encoding: 'utf8', timeout: 1000});
        const rss = rows.split('\n').reduce((sum, row) => {
          const [pid, group, size] = row.trim().split(/\s+/).map(Number);
          return sum + (pid && group === child.pid && Number.isFinite(size) ? size : 0);
        }, 0);
        peakRssKiB = Math.max(peakRssKiB, rss);
        if (rss > maxRssKiB) stop(`프로세스 그룹 메모리 제한 ${Math.round(maxRssKiB / 1024)}MiB 초과`);
      } catch {stop('프로세스 메모리를 감시할 수 없어 안전하게 중단합니다.');}
    }, 200);
    for (const stream of [child.stdout, child.stderr]) stream.on('data', chunk => {
      outputBytes += chunk.length;
      if (outputBytes <= maxOutputBytes) output?.write(chunk);
      else stop('테스트 출력 제한 초과');
    });
    child.once('error', error => {reason ||= `테스트 실행 실패: ${error.code || error.name}`;});
    child.once('close', (code, exitSignal) => {
      closed = true; clearTimeout(watchdog); clearInterval(memory); clearTimeout(forced);
      // 자식이 먼저 종료되어도 남아 있는 같은 테스트 그룹의 워커를 정리합니다.
      signal('SIGKILL');
      process.removeListener('SIGINT', interrupt); process.removeListener('SIGTERM', interrupt);
      resolve({code: reason ? 1 : code ?? 1, reason, signal: exitSignal, peakRssKiB});
    });
  });
}
