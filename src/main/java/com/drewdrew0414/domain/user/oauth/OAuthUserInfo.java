package com.drewdrew0414.domain.user.oauth;

import com.drewdrew0414.domain.user.entity.Provider;

/*
 * 각 소셜 로그인 제공자의 사용자 정보 응답을 우리 서비스가 필요로 하는 최소 정보로 통일한 값입니다.
 * providerId는 각 제공자가 발급하는 고유 식별자(구글 sub, 깃허브 id, 네이버 id)이며,
 * users 테이블의 (provider, provider_id) 조합으로 실제 계정을 찾거나 새로 만드는 데 쓰입니다.
 */
// record -> 필드가 전부 final이고 getter/equals/hashCode/toString이 자동 생성되는, 값 전달만을 위한 불변 객체
public record OAuthUserInfo(Provider provider, String providerId, String email, String name) {
}
