package com.drewdrew0414.global.config;

import com.drewdrew0414.global.openai.OpenAiWebRequestMatcher;
import com.drewdrew0414.global.security.CustomUserDetailsService;
import com.drewdrew0414.global.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/** 실제 SecurityFilterChain과 미래 컨트롤러 모형을 함께 두어 모든 역할의 차단을 검증합니다. */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {SecurityConfig.class, OpenAiWebSecurityTest.TestConfiguration.class})
class OpenAiWebSecurityTest {
    @Autowired WebApplicationContext context;
    @Autowired Probe probe;
    private MockMvc mvc;

    @BeforeEach void setup() {
        probe.calls.set(0);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test void anonymousUsersAndOperatorsCannotCallAnyReservedMethodOrCaseVariant() throws Exception {
        String[] paths = {"/api/openai", "/api/openai/", "/api/openai/responses", "/api/openai/v1/models",
                "/api/ai", "/api/ai/", "/api/ai/generate", "/API/OPENAI", "/Api/Ai/Generate"};
        for (String role : new String[]{"anonymous", "USER", "OPERATOR", "ADMIN"}) {
            for (String path : paths) {
                for (HttpMethod method : new HttpMethod[]{HttpMethod.GET, HttpMethod.HEAD, HttpMethod.POST,
                        HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.OPTIONS}) {
                    MockHttpServletRequestBuilder request = request(method, path).queryParam("model", "unapproved-model");
                    if (!role.equals("anonymous")) request.with(user("test-account").roles(role));
                    var response = mvc.perform(request).andReturn().getResponse();
                    assertEquals(role.equals("anonymous") ? 401 : 403, response.getStatus(), role + " " + method + " " + path);
                    assertFalse(response.getContentAsString().contains("unapproved-model"));
                }
            }
        }
        assertEquals(0, probe.calls.get(), "보안 필터 뒤의 미래 컨트롤러까지 통과하면 안 됩니다.");
    }

    @Test void encodedOrNonCanonicalPathsNeverReachAControllerEvenForOperators() throws Exception {
        for (String path : new String[]{"/api/%6fpenai/probe", "/api/%61i/probe", "/api/openai%2fprobe",
                "/api/ai;mode=test/probe", "/api/ai//probe", "/api/ai/../openai/probe"}) {
            var response = mvc.perform(get(URI.create(path)).with(user("operator").roles("OPERATOR")))
                    .andReturn().getResponse();
            assertTrue(response.getStatus() >= 400 && response.getStatus() < 500, path);
        }
        assertEquals(0, probe.calls.get());
    }

    @Test void matcherHandlesContextPathDecodedServletPathAndDoesNotBlockUnrelatedApis() {
        var matcher = new OpenAiWebRequestMatcher();
        var request = new MockHttpServletRequest("GET", "/cobweb/API/AI/generate");
        request.setContextPath("/cobweb");
        assertTrue(matcher.matches(request));
        request.setServletPath("/api/openai"); request.setPathInfo("/generate");
        request.setQueryString("key=not-a-real-key");
        assertTrue(matcher.matches(request));
        for (String path : new String[]{"/api/auth/login", "/api/languages", "/api/air", "/api/openai-other"}) {
            assertFalse(matcher.matches(new MockHttpServletRequest("GET", path)), path);
        }
    }

    @Test void existingPublicAndAuthenticatedRulesAreUnchanged() throws Exception {
        assertEquals(200, mvc.perform(get("/api/languages")).andReturn().getResponse().getStatus());
        assertEquals(200, mvc.perform(request(HttpMethod.POST, "/api/auth/login")).andReturn().getResponse().getStatus());
        assertEquals(401, mvc.perform(get("/api/private/probe")).andReturn().getResponse().getStatus());
        assertEquals(200, mvc.perform(get("/api/private/probe").with(user("user").roles("USER")))
                .andReturn().getResponse().getStatus());
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    @EnableWebMvc
    static class TestConfiguration {
        @Bean JwtTokenProvider jwt() { return mock(JwtTokenProvider.class); }
        @Bean CustomUserDetailsService users() { return mock(CustomUserDetailsService.class); }
        @Bean Probe probe() { return new Probe(); }
    }

    /** 운영에 없는 예약 API를 모사하여 미래 컨트롤러도 denyAll 뒤에서 호출되지 않음을 확인합니다. */
    @RestController
    @TestComponent
    static class Probe {
        final AtomicInteger calls = new AtomicInteger();
        @RequestMapping("/**") String probe() { calls.incrementAndGet(); return "test-only-controller"; }
    }
}
