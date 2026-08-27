package com.drewdrew0414.domain.user.entity;

/**
 * 이메일 인증 코드가 어떤 목적으로 발급됐는지를 나타냅니다.
 * JOIN(회원가입 인증), PASSWORD_RESET(비밀번호 재설정 인증)
 */
public enum VerificationPurpose {
    JOIN,          // 회원가입 인증
    PASSWORD_RESET // 비밀번호 재설정 인증
}
