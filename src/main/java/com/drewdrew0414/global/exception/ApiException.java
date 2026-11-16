package com.drewdrew0414.global.exception;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

/** 예상 가능한 요청 실패입니다. 기존 HTTP 상태/트랜잭션 롤백 계약을 바꾸지 않습니다. */
public final class ApiException extends ResponseStatusException {
    public enum Type {
        INVALID_REQUEST(400,"요청 내용을 확인해주세요."),
        AUTHENTICATION_REQUIRED(401,"로그인이 필요합니다."),
        PERMISSION_DENIED(403,"이 작업을 수행할 권한이 없습니다."),
        RESOURCE_NOT_FOUND(404,"요청한 자료를 찾을 수 없습니다."),
        METHOD_NOT_ALLOWED(405,"지원하지 않는 요청 방식입니다."),
        NOT_ACCEPTABLE(406,"지원하지 않는 응답 형식입니다."),
        STATE_CONFLICT(409,"현재 상태에서는 요청을 처리할 수 없습니다."),
        PAYLOAD_TOO_LARGE(413,"요청 데이터가 너무 큽니다."),
        UNSUPPORTED_MEDIA_TYPE(415,"지원하지 않는 요청 형식입니다."),
        RATE_LIMITED(429,"요청이 너무 많습니다. 잠시 후 다시 시도해주세요."),
        UPSTREAM_ERROR(502,"외부 서비스 응답을 확인할 수 없습니다."),
        SERVICE_UNAVAILABLE(503,"서비스를 일시적으로 사용할 수 없습니다."),
        INTERNAL_ERROR(500,"서버에서 오류가 발생했습니다. 잠시 후 다시 시도해주세요.");

        private final int status;
        private final String message;
        Type(int status,String message) {this.status=status;this.message=message;}
        public int status() {return status;}
        public String message() {return message;}
        public static Type from(HttpStatusCode status) {
            for(Type type:values())if(type.status==status.value())return type;
            return status.is5xxServerError()?INTERNAL_ERROR:INVALID_REQUEST;
        }
    }

    public ApiException(HttpStatusCode status) {this(status,null,null);}
    public ApiException(HttpStatusCode status,String reason) {this(status,reason,null);}
    public ApiException(HttpStatusCode status,String reason,Throwable cause) {
        super(status,reason==null?Type.from(status).message():reason,cause);
    }
    public Type getErrorType() {return Type.from(getStatusCode());}
}
