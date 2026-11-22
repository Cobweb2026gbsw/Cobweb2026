// 시험 편집기가 paste 이벤트를 처리하기 전에 캡처 단계에서 차단합니다.
// 운영체제가 브라우저에 전달하지 않는 캡처 동작은 감지할 수 없습니다.
export function installContestInputGuard({document, window, captureProtection, active, appliesTo, record, reload}) {
    const listeners = [];
    let closed = false, pendingReload = false, lastCaptureAt = -Infinity, timer;
    const listen = (target, type, listener) => {
        target.addEventListener(type, listener, true);
        listeners.push(() => target.removeEventListener(type, listener, true));
    };
    function block(event) {
        if (!active() || !appliesTo(event.target)) return;
        event.preventDefault();
        event.stopImmediatePropagation();
        if (captureProtection) record('COPY_BLOCKED');
    }
    for (const type of ['copy', 'cut', 'paste', 'drop']) listen(document, type, block);
    listen(document, 'beforeinput', event => {
        if (['insertFromPaste', 'insertFromPasteAsQuotation', 'insertFromDrop'].includes(event.inputType)) block(event);
    });
    if (captureProtection) {
        listen(document, 'contextmenu', block);
        const platform = window.navigator?.userAgentData?.platform || window.navigator?.platform || '';
        function captureKey(event) {
            if (!active() || event.repeat) return;
            const key = event.key.toLowerCase();
            const screenshot = key === 'printscreen' || event.code === 'PrintScreen' ||
                (event.type === 'keydown' && event.metaKey && event.shiftKey && (
                    (/Mac/i.test(platform) && (['Digit3','Digit4','Digit5'].includes(event.code) || ['3','4','5'].includes(key))) ||
                    (/Win/i.test(platform) && (event.code === 'KeyS' || key === 's'))));
            if (!screenshot) {
                if (event.type === 'keydown' && (event.ctrlKey || event.metaKey) && key === 'p') {
                    event.preventDefault();
                    record('PRINT_BLOCKED');
                }
                return;
            }
            event.preventDefault();
            const at = Date.now();
            if (pendingReload || at - lastCaptureAt < 1500) return;
            // 일부 환경은 PrintScreen의 keyup만 전달합니다. 재기동 직후의 같은 키도 한 번만 처리합니다.
            try {
                const previous = Number(window.sessionStorage.getItem('cobweb:contest-capture-at'));
                if (at - previous < 1500) return;
                window.sessionStorage.setItem('cobweb:contest-capture-at', String(at));
            } catch { /* 저장소가 없어도 현재 화면의 중복 신호는 합칩니다. */ }
            lastCaptureAt = at;
            pendingReload = true;
            const refresh = () => {
                if (closed || !pendingReload) return;
                pendingReload = false;
                window.clearTimeout(timer);
                if (active()) reload();
            };
            timer = window.setTimeout(refresh, 800);
            Promise.resolve().then(() => record('CAPTURE_KEY')).then(refresh, refresh);
        }
        listen(document, 'keydown', captureKey);
        listen(document, 'keyup', captureKey);
        listen(window, 'beforeprint', () => {if (active()) record('PRINT_BLOCKED');});
    }
    return () => {
        closed = true;
        window.clearTimeout(timer);
        listeners.forEach(remove => remove());
    };
}
