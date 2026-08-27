package com.drewdrew0414.domain.user.oauth;

import com.drewdrew0414.domain.user.entity.Provider;

/*
 * 소셜 로그인 제공자 하나(구글/깃허브/네이버)와의 OAuth 연동을 담당하는 클라이언트의 공통 규격입니다.
 * OAuthService는 이 인터페이스만 알고 있으면 되고, 제공자별 API 차이(엔드포인트, 응답 형식)는
 * 각 구현체 안에 캡슐화됩니다.
 */
public interface OAuthClient {
    Provider getProvider();
    // 이 클라이언트가 어떤 제공자를 담당하는지 표시 -> OAuthService가 Provider별로 알맞은 구현체를 찾을 때 씀

    String buildAuthorizeUrl(String state);
    // 프론트엔드가 사용자를 이동시킬 제공자 측 로그인 동의 화면 URL을 만듭니다.

    OAuthUserInfo fetchUserInfo(String code, String state);
    // 인가 코드(code)를 제공자의 access token으로 교환한 뒤, 프로필 정보를 조회해 반환합니다.
    // state는 네이버처럼 토큰 교환 시에도 state를 그대로 요구하는 제공자를 위해 함께 넘깁니다.
}
