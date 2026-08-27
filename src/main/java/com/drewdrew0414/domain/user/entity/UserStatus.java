package com.drewdrew0414.domain.user.entity;

/**
 * 계정이 현재 어떤 상태인지를 나타냅니다. 로그인 시 이 값을 보고 인증을 허용할지 막을지 결정하게 됩니다.
 * ACTIVE(정상), TIMEOUT(로그인 실패 누적 등으로 일시 제한), STOPPED(정지), BANNED(영구 정지)
 */
public enum UserStatus {
    ACTIVE,  // 정상 — 로그인 가능
    TIMEOUT, // 로그인 실패 누적 등으로 일시 제한
    STOPPED, // 정지
    BANNED   // 영구 정지
}
