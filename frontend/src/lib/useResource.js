import { useCallback, useEffect, useRef, useState } from 'react';
import { useAuth } from '../context/AuthContext';
import { api, getSessionEpoch } from './api';

// 화면 이탈·계정 전환 뒤 이전 응답을 다시 표시하지 않습니다.
export function useScope() {
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  return () => { const epoch = getSessionEpoch(); return () => mounted.current && epoch === getSessionEpoch(); };
}
export function useResource(path) {
  const { epoch } = useAuth();
  const [state, setState] = useState({ data: null, loading: true, error: '' });
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    if (!path) { setState({ data: null, loading: false, error: '' }); return; }
    const controller = new AbortController(); let active = true;
    setState({ data: null, loading: true, error: '' });
    api(path, 'GET', null, { signal: controller.signal }).then(data => {
      if (active) setState({ data, loading: false, error: '' });
    }).catch(error => {
      if (active && !error.stale && error.name !== 'AbortError') setState({ data: null, loading: false, error: error.message });
    });
    return () => { active = false; controller.abort(); };
  }, [path, epoch, revision]);
  return { ...state, reload: useCallback(() => setRevision(value => value + 1), []), revision };
}
