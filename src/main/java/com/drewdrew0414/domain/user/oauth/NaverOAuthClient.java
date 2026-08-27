package com.drewdrew0414.domain.user.oauth;

import com.drewdrew0414.domain.user.entity.Provider;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/*
 * 네이버 OAuth 연동을 담당하는 클라이언트입니다.
 * 네이버는 다른 두 제공자와 달리 토큰 교환도 GET 방식이고, authorize 단계에서 쓴 state 값을
 * 토큰 교환 요청에도 그대로 실어 보내야 하는 점이 특이합니다.
 */
@Component
// 스프링이 관리하는 빈으로 등록 -> OAuthService가 List<OAuthClient>로 모아서 주입받음
public class NaverOAuthClient implements OAuthClient {
    private final RestClient restClient; // 외부 API(네이버) 호출 전용 동기 HTTP 클라이언트
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    public NaverOAuthClient(
            RestClient.Builder restClientBuilder, // 스프링이 자동 구성해준 RestClient.Builder 빈을 주입받음
            @Value("${oauth.naver.client-id}") String clientId,         // application.yaml의 oauth.naver.client-id 주입
            @Value("${oauth.naver.client-secret}") String clientSecret, // application.yaml의 oauth.naver.client-secret 주입
            @Value("${oauth.naver.redirect-uri}") String redirectUri    // application.yaml의 oauth.naver.redirect-uri 주입
    ) {
        this.restClient = restClientBuilder.build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
    }

    @Override
    public Provider getProvider() {
        return Provider.NAVER;
    }

    @Override
    public String buildAuthorizeUrl(String state) {
        return UriComponentsBuilder.fromUriString("https://nid.naver.com/oauth2.0/authorize")
                .queryParam("response_type", "code") // 인가 코드 방식 사용을 명시
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("state", state) // 네이버는 state가 필수 파라미터 (없으면 요청 자체가 거부됨)
                .build()
                .encode() // 쿼리 값들을 URL 인코딩
                .toUriString();
    }

    @Override
    public OAuthUserInfo fetchUserInfo(String code, String state) {
        // 네이버는 토큰 교환 시에도 authorize 단계에서 쓴 state를 그대로 요구합니다.
        TokenResponse token = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .scheme("https").host("nid.naver.com").path("/oauth2.0/token")
                        .queryParam("grant_type", "authorization_code")
                        .queryParam("client_id", clientId)
                        .queryParam("client_secret", clientSecret)
                        .queryParam("code", code)
                        .queryParam("state", state)
                        .build())
                // RestClient의 uri(Function<UriBuilder, URI>) 형태 -> 내부적으로 쿼리 값들을 자동 인코딩해줌
                .retrieve()
                .body(TokenResponse.class);

        ProfileResponse profile = restClient.get()
                .uri("https://openapi.naver.com/v1/nid/me")
                .header("Authorization", "Bearer " + token.accessToken())
                .retrieve()
                .body(ProfileResponse.class);

        ProfileResponse.Profile p = profile.response();
        // 네이버 프로필 API는 실제 데이터를 response라는 한 단계 안쪽 객체에 감싸서 내려줌
        return new OAuthUserInfo(Provider.NAVER, p.id(), p.email(), p.name());
    }

    // 네이버 토큰 응답 중 우리가 필요로 하는 access_token 하나만 뽑아 받는 record.
    // JSON 필드명(snake_case)과 자바 필드명(camelCase)이 다르므로 @JsonProperty로 매핑해줌
    private record TokenResponse(@JsonProperty("access_token") String accessToken) {
    }

    // 네이버 프로필 응답은 {"resultcode": "00", "message": "success", "response": {...}} 형태라
    // 바깥 껍데기(response 필드)와 실제 값(Profile)을 record 두 개로 나눠서 받음
    private record ProfileResponse(Profile response) {
        private record Profile(String id, String email, String name) {
        }
    }
}
