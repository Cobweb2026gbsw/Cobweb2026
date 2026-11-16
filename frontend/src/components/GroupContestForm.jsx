import {useEffect, useRef, useState} from 'react';
import {api, getSessionEpoch} from '../lib/api';
import {contestPayload, localDatetime} from '../lib/groupContracts';

const OPTIONS = [
  ['replayAllowed', '종료 후 참여자 다시 보기 허용'],
  ['captureProtection', '복사·인쇄 억제와 캡처 키 신호'],
  ['windowSwitchProtection', '전체 화면 권고와 창 전환 신호'],
];

export default function GroupContestForm({gid, current = null, onSaved, hideHeading = false}) {
  const [fields, setFields] = useState(() => ({
    title: current?.title || '',
    startsAt: current?.startsAt ? localDatetime(current.startsAt) : '',
    endsAt: current?.endsAt ? localDatetime(current.endsAt) : '',
    problemNumbers: (current?.problems || []).map(item => item.problemNumber).join(', '),
    replayAllowed: Boolean(current?.replayAllowed),
    captureProtection: Boolean(current?.captureProtection),
    windowSwitchProtection: Boolean(current?.windowSwitchProtection),
  }));
  const [feedback, setFeedback] = useState(null), [busy, setBusy] = useState(false);
  const alive = useRef(true), flight = useRef(false);
  const locked = Boolean(current && current.status !== 'UPCOMING');
  useEffect(() => {
    alive.current = true;
    return () => {alive.current = false;};
  }, []);

  function change(key) {
    return ({target}) => {
      const next = target.type === 'checkbox' ? target.checked : target.value;
      setFields(values => ({...values, [key]: next}));
    };
  }

  async function save(event) {
    event.preventDefault();
    if (flight.current) return;
    flight.current = true;
    const session = getSessionEpoch();
    const active = () => alive.current && session === getSessionEpoch();
    setBusy(true);
    setFeedback(null);
    try {
      const body = contestPayload(fields, current);
      const path = `/api/groups/${gid}/contests${current ? '/' + current.id : ''}`;
      const id = await api(path, current ? 'PUT' : 'POST', body);
      if (active()) {
        setFeedback({message: '저장했습니다.'});
        await onSaved?.(id);
      }
    } catch (failure) {
      if (active() && !failure.stale) {
        const prefix = failure.status === 409 ? '상태가 바뀌었거나 이미 사용 중인 값입니다. ' : '';
        setFeedback({error: true, message: prefix + failure.message});
      }
    } finally {
      flight.current = false;
      if (active()) setBusy(false);
    }
  }

  return <form className="gp-form gp-contest-form" onSubmit={save}>
    {!hideHeading && <h3>{current ? '코테 설정 편집' : '새 코테 만들기'}</h3>}
    <label className="cw-label gp-field wide">제목
      <input className="cw-input" required maxLength={200} readOnly={locked} value={fields.title} onChange={change('title')} />
    </label>
    <label className="cw-label gp-field">시작 (내 시간대)
      <input className="cw-input" type="datetime-local" step="1" required readOnly={locked} value={fields.startsAt} onChange={change('startsAt')} />
    </label>
    <label className="cw-label gp-field">종료 (내 시간대)
      <input className="cw-input" type="datetime-local" step="1" required readOnly={locked} value={fields.endsAt} onChange={change('endsAt')} />
    </label>
    <label className="cw-label gp-field wide">문제 번호 · 입력 순서가 시험 순서입니다
      <input className="cw-input" required readOnly={locked} value={fields.problemNumbers} onChange={change('problemNumbers')}
        placeholder="1001, 1002 (해당 그룹에서 공개한 문제, 최대 50개)" />
    </label>
    {locked && <p className="gp-muted">시작된 시험은 원래 제목·시각·문제 순서를 그대로 보존하고 아래 옵션만 변경합니다.</p>}
    {OPTIONS.map(([key, label]) => <label className="gp-check" key={key}>
      <input type="checkbox" checked={fields[key]} onChange={change(key)} />{label}
    </label>)}
    <p className="gp-muted">운영체제 스크린샷이나 다른 기기 사용은 완전히 차단할 수 없습니다. 신호만으로 자동 실격하지 않습니다.</p>
    {feedback && <p role={feedback.error ? 'alert' : 'status'} className={feedback.error ? 'gp-error' : 'gp-muted'}>{feedback.message}</p>}
    <button className="cw-btn primary" disabled={busy}>{current ? '변경 저장' : '코테 생성'}</button>
  </form>;
}
