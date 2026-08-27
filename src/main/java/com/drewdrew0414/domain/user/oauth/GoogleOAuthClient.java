package com.drewdrew0414.domain.user.oauth;

import com.drewdrew0414.domain.user.entity.Provider;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/*
 * 구글 OAuth 연동을 담당하는 클라이언트입니다.
 * 표준 OAuth 2.0 Authorization Code 흐름 그대로: 인가 코드 -> 토큰 교환 -> 사용자 정보 조회.
 */
@Component
// 스프링이 관리하는 빈으로 등록 -> OAuthService가 List<OAuthClient>로 모아서 주입받음
public class GoogleOAuthClient implements OAuthClient {
    private final RestClient restClient; // 외부 API(구글) 호출 전용 동기 HTTP 클라이언트
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    public GoogleOAuthClient(
            RestClient.Builder restClientBuilder, // 스프링이 자동 구성해준 RestClient.Builder 빈을 주입받음
            @Value("${oauth.google.client-id}") String clientId,         // application.yaml의 oauth.google.client-id 주입
            @Value("${oauth.google.client-secret}") String clientSecret, // application.yaml의 oauth.google.client-secret 주입
            @Value("${oauth.google.redirect-uri}") String redirectUri    // application.yaml의 oauth.google.redirect-uri 주입
    ) {
        this.restClient = restClientBuilder.build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
    }

    @Override
    public Provider getProvider() {
        return Provider.GOOGLE;
    }

    @Override
    public String buildAuthorizeUrl(String state) {
        return UriComponentsBuilder.fromUriString("https://accounts.google.com/o/oauth2/v2/auth")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code") // 인가 코드 방식 사용을 명시
                .queryParam("scope", "openid email profile") // 요청할 정보 범위: 식별자/이메일/프로필
                .queryParam("state", state)
                .build()
                .encode() // 쿼리 값들(공백 등)을 URL 인코딩 (안 하면 "openid email profile"의 공백이 그대로 남아 깨짐)
                .toUriString();
    }

    @Override
    public OAuthUserInfo fetchUserInfo(String code, String state) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        // 구글 토큰 엔드포인트는 JSON이 아니라 application/x-www-form-urlencoded 형식을 요구함
        form.add("code", code);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("redirect_uri", redirectUri);
        form.add("grant_type", "authorization_code");

        TokenResponse token = restClient.post()
                .uri("https://oauth2.googleapis.com/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve() // 응답을 받아와서 상태코드 확인 + 역직렬화 준비
                .body(TokenResponse.class); // JSON 응답 바디를 TokenResponse 레코드로 역직렬화

        UserInfoResponse userInfo = restClient.get()
                .uri("https://www.googleapis.com/oauth2/v3/userinfo")
                .header("Authorization", "Bearer " + token.accessToken())
                // 방금 받은 access token으로 사용자 프로필 API 호출
                .retrieve()
                .body(UserInfoResponse.class);

        return new OAuthUserInfo(Provider.GOOGLE, userInfo.sub(), userInfo.email(), userInfo.name());
    }

    // 구글 토큰 응답 중 우리가 필요로 하는 access_token 하나만 뽑아 받는 record.
    // JSON 필드명(snake_case)과 자바 필드명(camelCase)이 다르므로 @JsonProperty로 매핑해줌
    private record TokenResponse(@JsonProperty("access_token") String accessToken) {
    }

    // 구글 userinfo 응답 중 우리가 쓰는 필드만 선언 -> 나머지 필드(picture 등)는 자동으로 무시됨
    private record UserInfoResponse(String sub, String email, String name) {
    }
}
