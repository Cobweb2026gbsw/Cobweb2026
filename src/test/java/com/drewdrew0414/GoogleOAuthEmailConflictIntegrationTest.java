package com.drewdrew0414;

import com.drewdrew0414.domain.user.entity.Provider;
import com.drewdrew0414.domain.user.service.ServiceTerms;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import com.drewdrew0414.domain.user.oauth.GoogleOAuthClient;
import com.drewdrew0414.domain.user.oauth.OAuthUserInfo;
import com.drewdrew0414.domain.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;

/** Google 응답은 대체하고 실제 PostgreSQL 유일 제약과 브라우저 콜백을 함께 검증합니다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "openai.enabled=false", "demo.seed=false"})
@ActiveProfiles("test")
class GoogleOAuthEmailConflictIntegrationTest {
    @MockitoSpyBean GoogleOAuthClient google;
    @MockitoSpyBean UserRepository users;
    @Autowired JdbcTemplate db;
    @Value("${local.server.port}") int port;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String identifier = UUID.randomUUID().toString();
    private final String email = "google-" + identifier + "@fixture.invalid";
    private final String username = "u" + identifier.substring(0, 8);

    @AfterEach void cleanup() {
        db.update("DELETE FROM users WHERE lower(email) = lower(?)", email);
    }

    @Test void occupiedEmailGoesToExistingLoginWithoutChangingAccountOrIssuingSession() throws Exception {
        seedLocalAccount();
        stubGoogle("google-" + identifier + "@FIXTURE.INVALID");

        assertLoginRedirectWithoutSession(callback());
        assertLocalAccountPreserved();
    }

    @Test void duplicateDetectedByPostgresAfterThePrecheckGoesToTheSameLoginPage() throws Exception {
        seedLocalAccount();
        stubGoogle(email);
        // Uniqueness remains authoritative if another request inserts after this request's precheck.
        doReturn(false).when(users).existsByEmail(email);

        assertLoginRedirectWithoutSession(callback(consentedState()));
        assertLocalAccountPreserved();
    }

    @Test void aNewGoogleIdentityIsCreatedAndCanLogInAgain() throws Exception {
        stubGoogle(email);
        var beforeConsent = callback();
        assertEquals("/signup/social?provider=google", location(beforeConsent));
        assertTrue(beforeConsent.headers().allValues("Set-Cookie").stream().noneMatch(cookie -> cookie.startsWith("refreshToken=")));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email));
        assertEquals(400, signupStart("obsolete").statusCode());
        var signup = callback(consentedState());
        assertEquals(302, signup.statusCode());
        assertTrue(location(signup).startsWith("/oauth/callback#accessToken="));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM users WHERE email = ? AND provider = 'GOOGLE' AND accepted_terms_version=? AND terms_accepted_at IS NOT NULL", Integer.class, email, ServiceTerms.VERSION));

        // Once linked, the provider ID is sufficient even when Google omits the verified email.
        stubGoogle(null);
        var login = callback();
        assertEquals(302, login.statusCode());
        assertTrue(location(login).startsWith("/oauth/callback#accessToken="));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM users WHERE lower(email) = lower(?)", Integer.class, email));
    }

    private void seedLocalAccount() {
        db.update("INSERT INTO users(username, email, password) VALUES (?, ?, 'fixture-hash')", username, email);
    }

    private void stubGoogle(String verifiedEmail) {
        doReturn(new OAuthUserInfo(Provider.GOOGLE, identifier, verifiedEmail, "fixture"))
                .when(google).fetchUserInfo(eq("fixture-code"), anyString());
    }

    private HttpResponse<String> signupStart(String version) throws Exception {
        doReturn("http://127.0.0.1:" + port + "/api/auth/oauth/google/callback").when(google).getRedirectUri();
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/oauth/google/signup-start"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"acceptedTermsVersion\":\"" + version + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String consentedState() throws Exception {
        var start = signupStart(ServiceTerms.VERSION);
        assertEquals(200, start.statusCode(), start::body);
        return start.headers().allValues("Set-Cookie").stream().filter(cookie -> cookie.startsWith("oauthState="))
                .findFirst().orElseThrow().split(";", 2)[0].substring("oauthState=".length());
    }

    private HttpResponse<String> callback() throws Exception { return callback("fixture-state"); }

    private HttpResponse<String> callback(String state) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/auth/oauth/google/callback?code=fixture-code&state=" + state))
                .header("Cookie", "oauthState=" + state)
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String location(HttpResponse<String> response) {
        URI target = response.uri().resolve(response.headers().firstValue("Location").orElseThrow());
        assertEquals(response.uri().getScheme(), target.getScheme());
        assertEquals(response.uri().getAuthority(), target.getAuthority());
        return "/" + response.uri().resolve("/").relativize(target);
    }

    private static void assertLoginRedirectWithoutSession(HttpResponse<String> response) {
        assertEquals(302, response.statusCode(), response::body);
        assertEquals("/login?oauthError=account-conflict", location(response));
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        var cookies = response.headers().allValues("Set-Cookie");
        assertTrue(cookies.stream().anyMatch(cookie -> cookie.startsWith("oauthState=") && cookie.contains("Max-Age=0")));
        assertTrue(cookies.stream().noneMatch(cookie -> cookie.startsWith("refreshToken=")));
    }

    private void assertLocalAccountPreserved() {
        assertEquals(1, db.queryForObject("SELECT count(*) FROM users WHERE lower(email) = lower(?)", Integer.class, email));
        assertEquals(1, db.queryForObject("""
                SELECT count(*) FROM users WHERE username = ? AND provider IS NULL AND provider_id IS NULL
                    AND password = 'fixture-hash'
                """, Integer.class, username));
        assertEquals(0, db.queryForObject("""
                SELECT count(*) FROM refresh_token WHERE user_id = (SELECT id FROM users WHERE username = ?)
                """, Integer.class, username));
    }
}
