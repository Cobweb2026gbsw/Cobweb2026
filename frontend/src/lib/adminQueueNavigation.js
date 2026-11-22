const statuses = new Set(['QUEUED', 'RUNNING', 'SYSTEM_ERROR']);

export function queueFilters(params) {
  const page = Number(params.get('page'));
  const language = params.get('language') || '';
  const status = params.get('status');
  return {
    status: statuses.has(status) ? status : 'QUEUED',
    page: Number.isInteger(page) && page >= 0 && page <= 1_000_000 ? page : 0,
    language: /^[A-Z][A-Z0-9_]{0,30}$/.test(language) ? language : '',
  };
}

export function queueParams({status, page, language}) {
  const params = new URLSearchParams();
  if (status !== 'QUEUED') params.set('status', status);
  if (language) params.set('language', language);
  if (page) params.set('page', String(page));
  return params;
}

export function adminQueueReturn(value) {
  if (typeof value !== 'string' || !/^\/admin(?:\?[^#]*)?$/.test(value) || value.length > 4096) return null;
  const params = queueParams(queueFilters(new URLSearchParams(value.slice('/admin'.length))));
  return params.size ? '/admin?' + params : '/admin';
}
