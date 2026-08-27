package com.drewdrew0414.global.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/*
 * 프로젝트에서 발생할 수 있는 에러 상황들을 미리 정의해둔 열거형입니다.
 * 에러가 발생했을 때 어떤 HTTP 상태코드와 메시지로 응답할지를 이 안에서 한 곳에 모아 관리합니다.
 * 로그인 관련 에러 메시지가 여기저기 흩어지지 않고 한 파일에서 관리되게 해주는 역할입니다.
 */
@Getter
// status/message 필드의 getter를 자동 생성 (Lombok)
public enum ErrorCode {
    // 상수(status, message) 순서 -> 아래 생성자 파라미터 순서와 그대로 매칭됨
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 사용자입니다."),
    PASSWORD_MISMATCH(HttpStatus.UNAUTHORIZED, "비밀번호가 일치하지 않습니다."),
    BANNED_USER(HttpStatus.FORBIDDEN, "정지되었거나 제한된 계정입니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다."),
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "만료된 토큰입니다."),
    DUPLICATE_USERNAME(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다."),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
    EMAIL_NOT_VERIFIED(HttpStatus.BAD_REQUEST, "이메일 인증이 필요합니다."),
    EMAIL_VERIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "인증 요청을 찾을 수 없습니다. 인증 코드를 다시 요청해주세요."),
    VERIFICATION_CODE_MISMATCH(HttpStatus.BAD_REQUEST, "인증 코드가 일치하지 않습니다."),
    VERIFICATION_CODE_EXPIRED(HttpStatus.BAD_REQUEST, "인증 코드가 만료되었습니다. 다시 요청해주세요."),
    TOO_MANY_VERIFICATION_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "인증 코드를 너무 자주 요청했습니다. 잠시 후 다시 시도해주세요."),
    INVALID_RESET_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않거나 만료된 토큰입니다."),
    INVALID_OAUTH_STATE(HttpStatus.UNAUTHORIZED, "유효하지 않은 소셜 로그인 요청입니다. 로그인을 다시 시작해주세요."),
    OAUTH_EMAIL_NOT_FOUND(HttpStatus.BAD_REQUEST, "소셜 계정에서 확인된 이메일을 가져올 수 없습니다."),
    UNSUPPORTED_OAUTH_PROVIDER(HttpStatus.BAD_REQUEST, "지원하지 않는 소셜 로그인 제공자입니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        // enum 상수마다 자기 몫의 status/message를 들고 생성됨 (모든 상수 공통 생성자)
        this.status = status;
        this.message = message;
    }
}
