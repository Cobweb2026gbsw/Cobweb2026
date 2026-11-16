package com.drewdrew0414.domain.user.entity;

/**
 * 로그인 시도의 결과를 나타냅니다. users_log 테이블에 매 시도마다 이 중 하나로 기록됩니다.
 * SUCCESS(성공), FAIL_PASSWORD_MISMATCH(비밀번호 불일치),
 * FAIL_USER_NOT_FOUND(존재하지 않는 계정), BANNED_USER(정지된 계정으로 시도)
 */
public enum LoginStatus {
    SUCCESS,                // 성공
    FAIL_PASSWORD_MISMATCH, // 비밀번호 불일치
    FAIL_USER_NOT_FOUND,    // 존재하지 않는 계정
    BANNED_USER             // 정지된 계정으로 시도
}
