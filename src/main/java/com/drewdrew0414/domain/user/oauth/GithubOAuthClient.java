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
 * 깃허브 OAuth 연동을 담당하는 클라이언트입니다.
 * 깃허브는 프로필의 email이 비공개로 설정된 경우 null로 내려오기 때문에,
 * 그럴 때는 /user/emails를 추가로 호출해서 검증된(primary) 이메일을 따로 찾아옵니다.
 */
@Component
// 스프링이 관리하는 빈으로 등록 -> OAuthService가 List<OAuthClient>로 모아서 주입받음
public class GithubOAuthClient implements OAuthClient {
    private final RestClient restClient; // 외부 API(깃허브) 호출 전용 동기 HTTP 클라이언트
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    public GithubOAuthClient(
            RestClient.Builder restClientBuilder, // 스프링이 자동 구성해준 RestClient.Builder 빈을 주입받음
            @Value("${oauth.github.client-id}") String clientId,         // application.yaml의 oauth.github.client-id 주입
            @Value("${oauth.github.client-secret}") String clientSecret, // application.yaml의 oauth.github.client-secret 주입
            @Value("${oauth.github.redirect-uri}") String redirectUri    // application.yaml의 oauth.github.redirect-uri 주입
    ) {
        this.restClient = restClientBuilder.build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
    }

    @Override
    public Provider getProvider() {
        return Provider.GITHUB;
    }

    @Override
    public String buildAuthorizeUrl(String state) {
        return UriComponentsBuilder.fromUriString("https://github.com/login/oauth/authorize")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", "read:user user:email") // 프로필 읽기 + 이메일 조회 권한 요청
                .queryParam("state", state)
                .build()
                .encode() // 쿼리 값들(공백 등)을 URL 인코딩
                .toUriString();
    }

    @Override
    public OAuthUserInfo fetchUserInfo(String code, String state) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        // 깃허브 토큰 엔드포인트도 application/x-www-form-urlencoded 형식을 요구함
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("code", code);
        form.add("redirect_uri", redirectUri);

        TokenResponse token = restClient.post()
                .uri("https://github.com/login/oauth/access_token")
                .header("Accept", "application/json")
                // 이 헤더가 없으면 깃허브가 기본값인 쿼리스트링 형식으로 응답해서 JSON 역직렬화가 실패함
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);

        UserResponse user = restClient.get()
                .uri("https://api.github.com/user")
                .header("Authorization", "Bearer " + token.accessToken())
                .retrieve()
                .body(UserResponse.class);

        String email = user.email() != null ? user.email() : fetchPrimaryEmail(token.accessToken());
        // 프로필 이메일이 비공개면 null로 내려오므로, 그 경우에만 이메일 목록 API를 추가로 호출

        return new OAuthUserInfo(Provider.GITHUB, String.valueOf(user.id()), email, user.login());
    }

    private String fetchPrimaryEmail(String accessToken) {
        EmailResponse[] emails = restClient.get()
                .uri("https://api.github.com/user/emails")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(EmailResponse[].class);
        // 유저가 등록한 이메일 목록(비공개 이메일 포함)을 배열로 받음

        if (emails == null) {
            return null;
        }
        for (EmailResponse e : emails) {
            if (e.primary() && e.verified()) {
                // 대표(primary)로 지정돼 있고, 깃허브가 소유권을 검증(verified)한 이메일만 신뢰해서 사용
                return e.email();
            }
        }
        return null;
    }

    // 깃허브 토큰 응답 중 우리가 필요로 하는 access_token 하나만 뽑아 받는 record.
    // JSON 필드명(snake_case)과 자바 필드명(camelCase)이 다르므로 @JsonProperty로 매핑해줌
    private record TokenResponse(@JsonProperty("access_token") String accessToken) {
    }

    // /user 응답 중 우리가 쓰는 필드만 선언 -> 나머지 필드(avatar_url 등)는 자동으로 무시됨
    private record UserResponse(long id, String login, String email) {
    }

    // /user/emails 응답 배열의 원소 하나
    private record EmailResponse(String email, boolean primary, boolean verified) {
    }
}
