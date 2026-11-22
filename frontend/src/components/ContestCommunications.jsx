import {useEffect, useRef, useState} from 'react';
import {api} from '../lib/api';
import {formatDate} from '../lib/groupContracts';
import {useMutationGuard} from '../lib/useApiData';

function MessageForm({label, button, send, children, allowPublish = false}) {
  const [body, setBody] = useState(''), [publish, setPublish] = useState(false);
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  const flight = useRef(false), guard = useMutationGuard();
  async function submit(event) {
    event.preventDefault();
    if (flight.current || !body.trim()) return;
    const current = guard();
    flight.current = true; setBusy(true); setError('');
    try {
      await send(body.trim(), publish);
      if (current()) {setBody(''); setPublish(false);}
    } catch (failure) {
      if (current() && !failure.stale) setError(failure.message);
    } finally {
      flight.current = false;
      if (current()) setBusy(false);
    }
  }
  return <form className="gp-form gc-message-form" onSubmit={submit}>
    {children}
    <label className="cw-label">{label}<textarea className="cw-input" required maxLength={2000} rows={3} value={body} onChange={e=>setBody(e.target.value)} /></label>
    {allowPublish && <label><input type="checkbox" checked={publish} onChange={e=>setPublish(e.target.checked)} /> 답변 내용만 전체 공지로도 게시</label>}
    {error && <p className="gp-error" role="alert">{error}</p>}
    <button className="cw-btn sm" disabled={busy || !body.trim()}>{button}</button>
  </form>;
}

export default function ContestCommunications({base, manager, active, canTake, selected}) {
  const [data, setData] = useState(null), [error, setError] = useState(''), [revision, setRevision] = useState(0);
  const [pages, setPages] = useState([]), cursor = pages.at(-1);
  const guard = useMutationGuard(base, active, manager, canTake);
  useEffect(() => {
    const controller = new AbortController();
    let sequence = 0;
    async function read() {
      const attempt = ++sequence;
      try {
        const value = await api(base + '/communications' + (cursor ? '?beforeId=' + cursor : ''),'GET',null,{signal:controller.signal});
        if (!controller.signal.aborted && sequence === attempt) {setData(value); setError('');}
      } catch (failure) {
        if (!failure.stale && !controller.signal.aborted && sequence === attempt) {setData(null); setError(failure.message);}
      }
    }
    read();
    const interval = active ? setInterval(read, 10000) : null;
    return () => {controller.abort(); clearInterval(interval);};
  },[base, active, cursor, revision]);
  async function post(path, payload) {
    const current = guard();
    await api(base + path,'POST',payload);
    if (current()) {setPages([]); setRevision(value=>value+1);}
  }
  const notices = data?.announcements || [], questions = data?.questions || [];
  return <section className="cw-card gc-messages">
    {notices.length > 0 && <aside aria-label="시험 공지"><h2 className="cw-card-title">시험 공지</h2>
      <strong className="gc-message-text">{notices[0].body}</strong><small>{formatDate(notices[0].createdAt)}</small>
      {notices.length > 1 && <details><summary>이전 공지 {notices.length-1}개</summary>{notices.slice(1).map(n=><p className="gc-message-text" key={n.id}>{n.body}<small>{formatDate(n.createdAt)}</small></p>)}</details>}
    </aside>}
    <details><summary>{manager ? '시험 질문과 공지 관리' : '시험 질문과 답변'}</summary>
      {error && <p className="gp-error" role="alert">{error}</p>}
      {manager && active && <MessageForm label="전체 공지 내용" button="공지 게시" send={body=>post('/announcements',{body})}/>}
      {canTake && active && <MessageForm key={selected??0} label={selected ? '문제 #' + selected + ' 비공개 질문' : '시험 운영자에게 비공개 질문'} button="질문 보내기" send={body=>post('/questions',{body,problemNumber:selected || null})}/>}
      {questions.map(q=><article className="gc-message" key={q.id}><strong>{q.problemNumber ? '문제 #' + q.problemNumber : '시험 일반'}{manager && ' · ' + q.asker}</strong><small>{formatDate(q.createdAt)}</small><p className="gc-message-text">{q.body}</p>
        {q.answer ? <blockquote className="gc-message-text">{q.answer}<small>운영자 답변 · {formatDate(q.answeredAt)}</small></blockquote> :
          manager && active ? <MessageForm label="비공개 답변" button="답변 등록" allowPublish send={(body,publish)=>post('/questions/'+q.id+'/answer',{body,publish})}/> : <small>답변 대기</small>}
      </article>)}
      {data && !questions.length && <p className="gp-muted">등록된 질문이 없습니다.</p>}
      {(pages.length>0 || data?.hasMore) && <nav className="gp-actions" aria-label="시험 질문 페이지">
        <button className="cw-btn sm" disabled={!pages.length} onClick={()=>{setData(null);setPages(p=>p.slice(0,-1));}}>이전</button>
        <button className="cw-btn sm" disabled={!data?.hasMore} onClick={()=>{setData(null);setPages(p=>[...p,questions.at(-1).id]);}}>다음</button>
      </nav>}
    </details>
  </section>;
}
