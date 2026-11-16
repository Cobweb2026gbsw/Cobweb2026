package com.drewdrew0414;

import com.drewdrew0414.global.openai.LearningReportGateway;
import com.drewdrew0414.global.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

/** 실제 HTTP, PostgreSQL 및 JWT 경계를 사용하고 외부 요약 호출만 대체합니다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=", "openai.enabled=false", "demo.seed=false"})
@ActiveProfiles("test")
class LearningReportIntegrationTest {
    private static final String API = "/api/learning-reports";
    private static final String SUMMARY = "배열 문제를 연습했어요. 경곗값을 한 번 더 확인해 보세요.";
    private static final String CARRY = "이전 회차 내부 요약 표시자";
    private static final String PRIVATE_MARKER = "never-send-source-or-statement";
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    @MockitoBean LearningReportGateway gateway;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired JwtTokenProvider jwt;
    @Value("${local.server.port}") int port;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<Long> problems = new ArrayList<>();
    private Account owner;
    private Account learner;
    private Account outsider;

    @BeforeEach void setUp() {
        owner = account();
        learner = account();
        outsider = account();
        when(gateway.isAvailable()).thenReturn(true);
    }

    @AfterEach void cleanUp() {
        if (learner != null) db.update("DELETE FROM users WHERE id = ?", learner.id());
        if (outsider != null) db.update("DELETE FROM users WHERE id = ?", outsider.id());
        for (long problem : problems) db.update("DELETE FROM problems WHERE id = ?", problem);
        if (owner != null) db.update("DELETE FROM users WHERE id = ?", owner.id());
    }

    @Test void pageEntryCreatesTwoDistinctReportsFromTwelveSolvesAndKeepsPrivateDataInTheServer() throws Exception {
        assertEquals(200, send("GET", "/dashboard/learning-report", null).statusCode());
        assertEquals(401, send("GET", API, null).statusCode());
        assertEquals(401, send("POST", API + "/generate", null).statusCode());
        LocalDate day = LocalDate.now(KOREA).minusDays(3);
        for (int i = 0; i < 4; i++) solve(true, day.atTime(23, 55 + i));
        solve(false, day.atTime(23, 59));
        var firstSolvedAt = day.atTime(23, 55).atZone(KOREA).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        db.update("""
                INSERT INTO submissions(user_id, problem_id, language, source_code, status, submitted_at, judged_at)
                VALUES (?, ?, 'PYTHON', ?, 'WRONG_ANSWER', ?, ?)
                """, learner.id(), problems.getFirst(), PRIVATE_MARKER, firstSolvedAt.minusSeconds(45), firstSolvedAt.plusSeconds(20));
        var initial = get(learner);
        assertEquals(1, initial.path("remainingToNextReport").asInt());
        assertEquals(0, initial.path("availableBatches").asInt());
        assertEquals(4, solvesOn(initial, day));
        assertEquals("idle", post(learner));
        verify(gateway, never()).generate(anyString(), nullable(String.class));

        for (int i = 0; i < 8; i++) solve(true, day.plusDays(1).atTime(0, i));
        var available = get(learner);
        assertEquals(2, available.path("availableBatches").asInt());
        assertEquals(8, solvesOn(available, day.plusDays(1)));
        assertEquals(0, get(outsider).path("availableBatches").asInt());
        assertEquals(0, responseData(send("GET", API + "?userId=" + learner.id(), outsider)).path("availableBatches").asInt());
        verify(gateway, never()).generate(anyString(), nullable(String.class));

        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var facts = new CopyOnWriteArrayList<String>();
        var previous = new CopyOnWriteArrayList<String>();
        when(gateway.generate(anyString(), nullable(String.class))).thenAnswer(call -> {
            facts.add(call.getArgument(0));
            String prior = call.getArgument(1);
            previous.add(prior == null ? "" : prior);
            if (facts.size() == 1) {
                started.countDown();
                if (!release.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("test release timed out");
            }
            return new LearningReportGateway.Summary(SUMMARY, CARRY);
        });
        try {
            assertEquals("running", post(learner));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals("running", post(learner));
            assertEquals(1, facts.size());
            assertEquals(0, get(learner).path("reports").size());
        } finally {
            release.countDown();
        }
        var firstReport = awaitReports(learner, 1).path("reports").get(0);
        assertEquals(80, firstReport.path("radar").path("firstPassAccuracy").asInt());
        assertEquals("running", post(learner));
        var result = awaitReports(learner, 2);
        assertEquals("idle", post(learner));
        assertEquals(3, result.path("remainingToNextReport").asInt());
        assertEquals(0, result.path("availableBatches").asInt());
        assertEquals(2, facts.size());
        assertEquals(List.of("", CARRY), previous);
        assertTrue(facts.stream().noneMatch(value -> value.contains(PRIVATE_MARKER) || value.contains(learner.name())));
        var problemNumbers = new ArrayList<Integer>();
        for (JsonNode report : result.path("reports")) {
            assertEquals(SUMMARY, report.path("userSummary").asText());
            assertEquals(6, report.path("radar").size());
            assertTrue(report.path("problemSolvingScore").isNumber());
            assertEquals(5, report.path("problemIds").size());
            report.path("problemIds").forEach(id -> problemNumbers.add(id.asInt()));
            assertFalse(report.toString().contains(CARRY));
        }
        assertEquals(10, problemNumbers.stream().distinct().count());
        assertEquals(0, get(outsider).path("reports").size());
        verify(gateway, times(2)).generate(anyString(), nullable(String.class));
    }

    @Test void aFailedGenerationLeavesTheSavedReportVisibleAndDoesNotRetryOnPoll() throws Exception {
        LocalDateTime start = LocalDate.now(KOREA).minusDays(2).atTime(12, 0);
        for (int i = 0; i < 10; i++) solve(true, start.plusMinutes(i));
        when(gateway.generate(anyString(), nullable(String.class)))
                .thenReturn(new LearningReportGateway.Summary(SUMMARY, CARRY))
                .thenThrow(new IllegalStateException("provider-only test detail"));
        assertEquals("running", post(learner));
        awaitReports(learner, 1);
        assertEquals("running", post(learner));
        JsonNode result = awaitState(learner, "retry_later");
        assertEquals(1, result.path("reports").size());
        assertFalse(result.toString().contains("provider-only test detail"));
        assertEquals("retry_later", post(learner));
        verify(gateway, times(2)).generate(anyString(), nullable(String.class));
    }

    private Account account() {
        String name = "lr" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        Long id = db.queryForObject("INSERT INTO users(username, password, role) VALUES (?, 'unused', 'USER') RETURNING id",
                Long.class, name);
        return new Account(id, name, jwt.generateAccessToken(id, name, "USER", 0));
    }

    private void solve(boolean visible, LocalDateTime koreanTime) {
        int number = db.queryForObject("SELECT GREATEST(COALESCE(MAX(problem_number), 0), 800000) + 1 FROM problems", Integer.class);
        Long problem = db.queryForObject("""
                INSERT INTO problems(problem_number, problem_title, description, input_description, output_description,
                                     rank_type, rank_int, status, visibility, author_id)
                VALUES (?, ?, ?, 'input', 'output', 'INITIATE', 3, 'PUBLISHED', ?, ?) RETURNING id
                """, Long.class, number, "report test " + number, PRIVATE_MARKER, visible ? "PUBLIC" : "PRIVATE", owner.id());
        problems.add(problem);
        LocalDateTime utcTime = koreanTime.atZone(KOREA).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        db.update("""
                INSERT INTO submissions(user_id, problem_id, language, source_code, status, score, submitted_at, judged_at)
                VALUES (?, ?, 'PYTHON', ?, 'ACCEPTED', 100, ?, ?)
                """, learner.id(), problem, PRIVATE_MARKER, utcTime.minusSeconds(1), utcTime);
        db.update("INSERT INTO user_solved_problems(user_id, problem_id, solved_at) VALUES (?, ?, ?)", learner.id(), problem, utcTime);
    }

    private int solvesOn(JsonNode report, LocalDate date) {
        for (JsonNode day : report.path("activity")) {
            if (day.path("date").asText().equals(date.toString())) return day.path("solvedCount").asInt();
        }
        return 0;
    }

    private JsonNode awaitReports(Account account, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        do {
            JsonNode value = get(account);
            if (value.path("reports").size() == expected) return value;
            Thread.sleep(60);
        } while (System.nanoTime() < deadline);
        return fail("Timed out waiting for " + expected + " reports");
    }

    private JsonNode awaitState(Account account, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        do {
            JsonNode value = get(account);
            if (expected.equals(value.path("generationState").asText())) return value;
            Thread.sleep(60);
        } while (System.nanoTime() < deadline);
        return fail("Timed out waiting for state " + expected);
    }

    private String post(Account account) throws Exception {
        return responseData(send("POST", API + "/generate", account)).path("generationState").asText();
    }

    private JsonNode get(Account account) throws Exception {
        var response = send("GET", API, account);
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        return responseData(response);
    }

    private JsonNode responseData(HttpResponse<String> response) {
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response::body);
        var body = json.readTree(response.body());
        assertTrue(body.path("success").asBoolean(), response::body);
        return body.path("data");
    }

    private HttpResponse<String> send(String method, String path, Account account) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
        if (account != null) request.header("Authorization", "Bearer " + account.token());
        return http.send(request.method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private record Account(long id, String name, String token) {}
}
