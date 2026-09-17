package com.drewdrew0414.domain.user.entity;

/**
 * 소셜 로그인 제공자를 나타내는 값입니다.
 * 유저가 구글/깃허브/네이버 같은 외부 서비스로 로그인했을 때 어떤 서비스를 통해 가입했는지 구분하는 용도입니다.
 * 일반 아이디/비밀번호로 가입한 유저는 이 값이 null입니다.
 *
 * GOOGLE, GITHUB, NAVER
 */
public enum Provider {
    GOOGLE, //  구글 OAuth 로그인
    GITHUB, // 깃허브 OAuth 로그인
    NAVER   // 네이버 OAuth 로그인
}
