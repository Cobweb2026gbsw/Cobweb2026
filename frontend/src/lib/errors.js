/** 화면은 서버 원문/stack 대신 이 경계에서 정한 안전한 message만 표시합니다. */
const FALLBACK_CODES={400:'INVALID_REQUEST',401:'AUTHENTICATION_REQUIRED',403:'PERMISSION_DENIED',404:'RESOURCE_NOT_FOUND',405:'METHOD_NOT_ALLOWED',409:'STATE_CONFLICT',413:'PAYLOAD_TOO_LARGE',415:'UNSUPPORTED_MEDIA_TYPE',429:'RATE_LIMITED',500:'INTERNAL_ERROR',502:'UPSTREAM_ERROR',503:'SERVICE_UNAVAILABLE',504:'UPSTREAM_TIMEOUT'};
export function statusErrorCode(status){return FALLBACK_CODES[status]||(status>=500?'INTERNAL_ERROR':'REQUEST_FAILED');}
export function safeErrorCode(value,fallback){return typeof value==='string'&&/^[A-Z][A-Z0-9_]{0,79}$/.test(value)?value:fallback;}
export class ApiError extends Error {
  constructor(message,{status=0,errorCode=null}={}){
    super(message);this.name='ApiError';this.status=status;this.errorCode=safeErrorCode(errorCode,statusErrorCode(status));
  }
}
export class NetworkError extends ApiError {
  constructor({requestMayHaveCompleted=false}={}){
    super(requestMayHaveCompleted?'서버 연결을 확인할 수 없습니다. 요청이 처리됐을 수 있으니 기록을 확인한 뒤 다시 시도해주세요.':'서버에 연결할 수 없습니다. 네트워크 상태를 확인해주세요.',{errorCode:'NETWORK_ERROR'});
    this.name='NetworkError';this.requestMayHaveCompleted=requestMayHaveCompleted;
  }
}
export class ProtocolError extends ApiError {
  constructor(message='서버 응답 형식을 확인할 수 없습니다.',{status=0,requestMayHaveCompleted=false}={}){
    super(message+(requestMayHaveCompleted?' 요청이 처리됐을 수 있으니 기록을 확인한 뒤 다시 시도해주세요.':''),{status,errorCode:'INVALID_RESPONSE'});this.name='ProtocolError';this.requestMayHaveCompleted=requestMayHaveCompleted;
  }
}
export class RequestError extends ApiError {
  constructor(message){super(message,{errorCode:'INVALID_CLIENT_REQUEST'});this.name='RequestError';}
}
export class StaleSessionError extends ApiError {
  constructor(){super('지난 로그인 상태의 요청입니다.',{errorCode:'STALE_SESSION'});this.name='StaleSessionError';this.stale=true;}
}
export class SessionChangedError extends ApiError {
  constructor(message){super(message,{status:401,errorCode:'SESSION_CHANGED'});this.name='SessionChangedError';this.sessionChanged=true;}
}

export function statusMessage(status){
  if(status>=500)return '서버에서 요청을 처리하지 못했습니다. 잠시 후 다시 시도해주세요.';
  return ({400:'요청 내용을 확인해주세요.',401:'로그인이 필요합니다.',403:'접근 권한이 없습니다.',404:'요청한 자료를 찾을 수 없습니다.',405:'지원하지 않는 요청 방식입니다.',409:'현재 상태에서는 처리할 수 없습니다.',413:'요청 크기가 제한을 초과했습니다.',415:'지원하지 않는 요청 형식입니다.',429:'요청이 너무 많습니다. 잠시 후 다시 시도해주세요.'})[status]||`요청에 실패했습니다. (${status})`;
}
export function safeServerMessage(value,status){
  // 장애 메시지에 포함된 SQL/자격 증명/경로·stack은 사용자 화면으로 전달하지 않습니다.
  if(status>=500||typeof value!=='string'||!value.trim()||value.length>500||/[<>\u0000-\u001f\u007f]/.test(value)
    ||/\b(?:java\.|org\.springframework|SQLException|SQLState|JDBC|SQLSyntaxError|SQLIntegrityConstraint|stack\s*trace|password\s*[=:]|access[_-]?token\s*[=:]|select\s+.+\s+from|insert\s+into|update\s+.+\s+set)\b/i.test(value))return statusMessage(status);
  return value.trim();
}
