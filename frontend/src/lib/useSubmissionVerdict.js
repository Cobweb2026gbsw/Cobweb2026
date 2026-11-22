import {useEffect, useState} from 'react';
import {useAuth} from '../context/AuthContext';
import {api} from './api';

export const isJudging = status => status === 'QUEUED' || status === 'RUNNING';

export default function useSubmissionVerdict(id, onPendingChange) {
  const {epoch} = useAuth();
  const [revision, setRevision] = useState(0);
  const [result, setResult] = useState(null);

  useEffect(() => {
    if (!id) return;
    const controller = new AbortController();
    let timer;
    setResult(null);
    onPendingChange?.(true);

    async function poll() {
      try {
        const data = await api('/api/submissions/' + id, 'GET', null, {signal: controller.signal});
        if (controller.signal.aborted) return;
        setResult({id, epoch, data});
        if (isJudging(data.status)) {
          timer = setTimeout(poll, 1500);
        } else {
          onPendingChange?.(false);
        }
      } catch (failure) {
        if (controller.signal.aborted || failure.stale || failure.name === 'AbortError') return;
        setResult({id, epoch, error: failure.message});
        onPendingChange?.(false);
      }
    }
    poll();
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [id, epoch, revision, onPendingChange]);

  const current = result && result.id === id && result.epoch === epoch ? result : null;
  return {data: current?.data, error: current?.error, retry: () => setRevision(n => n + 1)};
}
