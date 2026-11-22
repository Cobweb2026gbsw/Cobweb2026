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
        assertEquals(2, firstReport.path("schemaVersion").asInt());
        var evidence = firstReport.path("evidence");
        assertEquals(5, evidence.path("observedSubmissionCount").asInt());
        assertEquals(4, evidence.path("firstPassCount").asInt());
        assertEquals("WRONG_ANSWER", evidence.path("mostFrequentError").asText());
        assertEquals(1, evidence.path("errorCount").asInt());
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

    @Test void legacyReportRecommendsOnlyRelatedUnsolvedPublicPracticeAndRechecksVisibility() throws Exception {
        LocalDateTime now = LocalDate.now(KOREA).atStartOfDay();
        solve(true, now);
        long source = problems.getFirst();
        String topic = "배열 연습 " + UUID.randomUUID().toString().substring(0, 8);
        long tag = db.queryForObject("INSERT INTO tags(name) VALUES (?) RETURNING id", Long.class, topic);
        try {
            db.update("INSERT INTO problem_tags(problem_id, tag_id) VALUES (?, ?)", source, tag);
            db.update("""
                    INSERT INTO learning_reports(user_id, report_number, status, report_json, carry_forward_summary, completed_at)
                    VALUES (?, 1, 'READY', '{"schemaVersion":1,"reportNumber":1,"problemIds":[1],"userSummary":"기존 요약"}'::jsonb,
                            '기존 내부 요약', clock_timestamp())
                    """, learner.id());
            db.update("INSERT INTO learning_report_problems(user_id, report_number, problem_id, position) VALUES (?, 1, ?, 1)",
                    learner.id(), source);
            for (int i = 0; i < 5; i++) {
                long id = createProblem(i < 4);
                db.update("INSERT INTO problem_tags(problem_id, tag_id) VALUES (?, ?)", id, tag);
            }
            long group = db.queryForObject("SELECT id FROM study_groups WHERE system_key='COBWEB_DEFAULT'", Long.class);
            db.update("UPDATE problems SET group_id=? WHERE id=?", group, problems.get(4));
            var data = get(learner);
            assertEquals("기존 요약", data.path("reports").get(0).path("userSummary").asText());
            var recommendations = data.path("recommendations");
            assertEquals(3, recommendations.size());
            for (int i = 0; i < 3; i++) {
                assertEquals(db.queryForObject("SELECT problem_number FROM problems WHERE id=?", Integer.class, problems.get(i+1)),
                        recommendations.get(i).path("problemNumber").asInt());
                assertEquals(topic, recommendations.get(i).path("sharedTopic").asText());
            }
            assertEquals(0, get(outsider).path("recommendations").size());
            db.update("UPDATE problems SET status='ARCHIVED' WHERE id=?", problems.get(1));
            db.update("INSERT INTO user_solved_problems(user_id,problem_id) VALUES (?,?)", learner.id(), problems.get(2));
            assertEquals(1, get(learner).path("recommendations").size());
            db.update("UPDATE problems SET visibility='PRIVATE' WHERE id=?", source);
            assertEquals(0, get(learner).path("recommendations").size());
            verify(gateway, never()).generate(anyString(), nullable(String.class));
        } finally {
            db.update("DELETE FROM tags WHERE id=?", tag);
        }
    }

    @Test void operatorCanPauseOnlyNewGenerationsAndInspectDailyOutcomes() throws Exception {
        String usage = "/api/admin/report-usage";
        assertEquals(401, send("GET", usage, null).statusCode());
        assertEquals(403, send("GET", usage, learner).statusCode());
        assertEquals(403, patchPause(learner, true).statusCode());
        db.update("UPDATE users SET role='OPERATOR' WHERE id=?", owner.id());
        var admin = new Account(owner.id(), owner.name(), jwt.generateAccessToken(owner.id(), owner.name(), "OPERATOR", 0));
        var before = responseData(send("GET", usage, admin));
        for (int i = 0; i < 10; i++) solve(true, LocalDate.now(KOREA).atTime(10, i));
        when(gateway.generate(anyString(), nullable(String.class)))
                .thenReturn(new LearningReportGateway.Summary(SUMMARY, CARRY))
                .thenThrow(new IllegalStateException("synthetic failure"));
        assertEquals("running", post(learner));
        awaitReports(learner, 1);
        try {
            responseData(patchPause(admin, true));
            assertEquals("paused", post(learner));
            assertEquals(1, get(learner).path("reports").size());
            var paused = responseData(send("GET", usage, admin));
            assertTrue(paused.path("paused").asBoolean());
            assertEquals(before.path("succeeded").asInt() + 1, paused.path("succeeded").asInt());
            assertEquals(before.path("attempts").asInt() + 1, paused.path("attempts").asInt());
        } finally {
            responseData(patchPause(admin, false));
        }
        assertEquals("running", post(learner));
        awaitState(learner, "retry_later");
        var after = responseData(send("GET", usage, admin));
        assertEquals(before.path("failed").asInt() + 1, after.path("failed").asInt());
        assertEquals(before.path("remaining").asInt() - 2, after.path("remaining").asInt());
        verify(gateway, times(2)).generate(anyString(), nullable(String.class));
    }

    private HttpResponse<String> patchPause(Account actor, boolean value) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/admin/report-usage"))
                .header("Content-Type","application/json").header("Authorization","Bearer " + actor.token())
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"paused\":" + value + "}")).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test void operationsQueuesExcludeOldErrorsAndRequirePlatformStaff() throws Exception {
        String overview = "/api/admin/operations";
        assertEquals(401, send("GET", overview, null).statusCode());
        assertEquals(403, send("GET", overview + "/submissions", learner).statusCode());
        db.update("UPDATE users SET role='OPERATOR' WHERE id=?", owner.id());
        var admin = new Account(owner.id(), owner.name(), jwt.generateAccessToken(owner.id(), owner.name(), "OPERATOR", 0));
        int previous = responseData(send("GET",overview,admin)).path("systemErrors").asInt();
        long problem = createProblem(true);
        String insert = """
                INSERT INTO submissions(user_id,problem_id,language,source_code,status,judged_at)
                VALUES (?,?,'DART',?,'SYSTEM_ERROR',(CURRENT_TIMESTAMP AT TIME ZONE 'UTC') - make_interval(hours => ?))
                RETURNING id
                """;
        long recent = db.queryForObject(insert, Long.class, learner.id(), problem, PRIVATE_MARKER, 1);
        long old = db.queryForObject(insert, Long.class, learner.id(), problem, PRIVATE_MARKER, 25);
        var summary = responseData(send("GET",overview,admin));
        assertEquals(previous + 1, summary.path("systemErrors").asInt());
        var rows = responseData(send("GET",overview + "/submissions?status=SYSTEM_ERROR&language=DART",admin)).path("items");
        assertTrue(rows.valueStream().anyMatch(row -> row.path("id").asLong() == recent));
        assertFalse(rows.valueStream().anyMatch(row -> row.path("id").asLong() == old));
        assertFalse(rows.toString().contains(PRIVATE_MARKER));
        assertEquals(400, send("GET",overview + "/submissions?status=ACCEPTED",admin).statusCode());
    }

    private Account account() {
        String name = "lr" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        Long id = db.queryForObject("INSERT INTO users(username, password, role) VALUES (?, 'unused', 'USER') RETURNING id",
                Long.class, name);
        return new Account(id, name, jwt.generateAccessToken(id, name, "USER", 0));
    }

    private long createProblem(boolean visible) {
        int number = db.queryForObject("SELECT GREATEST(COALESCE(MAX(problem_number), 0), 800000) + 1 FROM problems", Integer.class);
        Long problem = db.queryForObject("""
                INSERT INTO problems(problem_number, problem_title, description, input_description, output_description,
                                     rank_type, rank_int, status, visibility, author_id)
                VALUES (?, ?, ?, 'input', 'output', 'INITIATE', 3, 'PUBLISHED', ?, ?) RETURNING id
                """, Long.class, number, "report test " + number, PRIVATE_MARKER, visible ? "PUBLIC" : "PRIVATE", owner.id());
        problems.add(problem);
        return problem;
    }

    private void solve(boolean visible, LocalDateTime koreanTime) {
        long problem = createProblem(visible);
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
