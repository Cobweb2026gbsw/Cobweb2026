package com.drewdrew0414.domain.user.oauth;

import com.drewdrew0414.global.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.hamcrest.Matchers.startsWith;

/** 제공자 네트워크 없이 토큰/프로필 응답의 실패 경계를 검증합니다. */
class OAuthClientResponseTest {
    private static final String TOKEN="{\"access_token\":\"test-only-provider-token\"}";
    private static final String SECRET="private-provider-response-must-not-leak";

    @Test void googleRejectsNullOrBlankTokenBeforeCallingUserinfo() {
        for(String body:new String[]{"{}","{\"access_token\":\" \"}"}) {
            var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
            var client=new GoogleOAuthClient(builder,"unused","unused","https://local.invalid/callback");
            server.expect(requestTo("https://oauth2.googleapis.com/token")).andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
            badGateway(()->client.fetchUserInfo("code","state"));server.verify();
        }
    }

    @Test void githubRejectsMissingTokenBeforeCallingProfile() {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        var client=new GithubOAuthClient(builder,"unused","unused","https://local.invalid/callback");
        server.expect(requestTo("https://github.com/login/oauth/access_token")).andRespond(withSuccess("{}",MediaType.APPLICATION_JSON));
        badGateway(()->client.fetchUserInfo("code","state"));server.verify();
    }

    @Test void naverRejectsEmptyTokenResponseBeforeCallingProfile() {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        var client=new NaverOAuthClient(builder,"unused","unused","https://local.invalid/callback");
        server.expect(requestTo(startsWith("https://nid.naver.com/oauth2.0/token?"))).andRespond(withNoContent());
        badGateway(()->client.fetchUserInfo("code","state"));server.verify();
    }

    @Test void googleMissingSubjectCannotBecomeAnAccountIdentity() {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        var client=new GoogleOAuthClient(builder,"unused","unused","https://local.invalid/callback");
        server.expect(requestTo("https://oauth2.googleapis.com/token")).andRespond(withSuccess(TOKEN,MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://openidconnect.googleapis.com/v1/userinfo"))
            .andRespond(withSuccess("{\"email\":\"verified@example.invalid\",\"email_verified\":true}",MediaType.APPLICATION_JSON));
        badGateway(()->client.fetchUserInfo("code","state"));server.verify();
    }

    @Test void githubMissingOrZeroIdDoesNotReachEmailLookup() {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        var client=new GithubOAuthClient(builder,"unused","unused","https://local.invalid/callback");
        server.expect(requestTo("https://github.com/login/oauth/access_token")).andRespond(withSuccess(TOKEN,MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/user")).andRespond(withSuccess("{}",MediaType.APPLICATION_JSON));
        badGateway(()->client.fetchUserInfo("code","state"));server.verify();
    }

    @Test void naverMissingNestedProfileIsAnExplicitProviderFailure() {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        var client=new NaverOAuthClient(builder,"unused","unused","https://local.invalid/callback");
        server.expect(requestTo(startsWith("https://nid.naver.com/oauth2.0/token?"))).andRespond(withSuccess(TOKEN,MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://openapi.naver.com/v1/nid/me")).andRespond(withSuccess("{}",MediaType.APPLICATION_JSON));
        badGateway(()->client.fetchUserInfo("code","state"));server.verify();
    }

    @Test void providerHttpErrorDoesNotBecomeAUserVisibleUrlOrResponseBody() {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        var client=new GoogleOAuthClient(builder,"unused","unused","https://local.invalid/callback");
        server.expect(requestTo("https://oauth2.googleapis.com/token")).andRespond(withBadRequest().body(SECRET));
        var error=badGateway(()->client.fetchUserInfo("code","state"));
        assertFalse(error.getMessage().contains(SECRET));assertFalse(error.getMessage().contains("googleapis"));server.verify();
    }

    @Test void validGoogleIdentityStillRequiresVerifiedEmailForNewSignupEvidence() {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        var client=new GoogleOAuthClient(builder,"unused","unused","https://local.invalid/callback");
        server.expect(requestTo("https://oauth2.googleapis.com/token")).andRespond(withSuccess(TOKEN,MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://openidconnect.googleapis.com/v1/userinfo"))
            .andRespond(withSuccess("{\"sub\":\"google-user\",\"email\":\"unverified@example.invalid\",\"email_verified\":false}",MediaType.APPLICATION_JSON));
        var info=client.fetchUserInfo("code","state");
        assertEquals("google-user",info.providerId());assertNull(info.email());server.verify();
    }

    @Test void githubSelectsOnlyVerifiedPrimaryEmail() {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        var client=new GithubOAuthClient(builder,"unused","unused","https://local.invalid/callback");
        server.expect(requestTo("https://github.com/login/oauth/access_token")).andRespond(withSuccess(TOKEN,MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/user")).andRespond(withSuccess("{\"id\":123,\"login\":\"user\"}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/user/emails")).andRespond(withSuccess("""
            [{"email":"unverified@example.invalid","primary":true,"verified":false},
             {"email":"secondary@example.invalid","primary":false,"verified":true},
             {"email":"verified@example.invalid","primary":true,"verified":true}]
            """,MediaType.APPLICATION_JSON));
        assertEquals("verified@example.invalid",client.fetchUserInfo("code","state").email());server.verify();
    }

    private static ApiException badGateway(org.junit.jupiter.api.function.Executable call) {
        var error=assertThrows(ApiException.class,call);assertEquals(502,error.getStatusCode().value());return error;
    }
}
