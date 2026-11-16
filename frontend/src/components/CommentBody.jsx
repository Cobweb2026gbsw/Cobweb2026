import { useEffect, useId, useRef, useState } from 'react';
import './CommentBody.css';

// 긴 댓글은 화면에서만 접습니다. 수정·이력·서버 저장에는 원문을 그대로 사용합니다.
export default function CommentBody({ body }) {
  const contentId = useId();
  const content = useRef(null);
  const [expanded, setExpanded] = useState(false);
  const longText = Array.from(body).length > 300 || body.split(/\r\n?|\n/).length > 6;
  const [overflowing, setOverflowing] = useState(longText);

  useEffect(() => {
    const element = content.current;
    if (!element || expanded) return;
    // 짧은 문자열도 좁은 화면에서 여섯 줄을 넘을 수 있어 실제 높이를 함께 검사합니다.
    let active = true;
    const measure = () => {
      if (active) setOverflowing(element.clientHeight > 0
        ? element.scrollHeight > element.clientHeight + 1 : longText);
    };
    measure();
    document.fonts?.ready.then(measure);
    const observer = typeof ResizeObserver === 'function' ? new ResizeObserver(measure) : null;
    observer?.observe(element);
    window.addEventListener('resize', measure);
    return () => {
      active = false;
      observer?.disconnect();
      window.removeEventListener('resize', measure);
    };
  }, [body, expanded, longText]);

  return <div className="fm-comment-content">
    <p ref={content} id={contentId} className={`fm-body fm-comment-body${expanded ? '' : ' is-collapsed'}`}>{body}</p>
    {overflowing && <button type="button" className="fm-comment-more"
      aria-expanded={expanded} aria-controls={contentId} onClick={() => setExpanded(value => !value)}>
      {expanded ? '접기' : '…더보기'}
    </button>}
  </div>;
}
