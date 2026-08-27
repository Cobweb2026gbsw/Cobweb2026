package com.drewdrew0414.domain.user.entity;

/**
 * 유저의 권한 등급을 나타냅니다. 로그인 이후 이 값에 따라 접근 가능한 API/페이지가 달라집니다.
 * USER(일반 유저), OPERATOR(운영자), DEVELOPER(개발자) 세 단계로 구분합니다.
 * Spring Security 인증 컨텍스트에 권한(GrantedAuthority)으로 실려서 인가(authorization) 처리에 쓰입니다.
 */
public enum Role {
    USER,      // 유저
    OPERATOR,  // 관리자
    DEVELOPER; // 개발자

    public String getAuthority() {
        return "ROLE_" + name(); // Spring Security가 요구하는 "ROLE_" 접두사를 붙여 권한 문자열로 변환
    }
}
