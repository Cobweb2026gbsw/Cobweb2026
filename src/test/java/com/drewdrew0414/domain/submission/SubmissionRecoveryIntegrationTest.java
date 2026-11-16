package com.drewdrew0414.domain.submission;

import com.drewdrew0414.global.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 실제 인증 필터와 PostgreSQL을 연결하여 응답 유실 뒤의 본인 기록 조회를 검증합니다. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SubmissionRecoveryIntegrationTest {
    private static final String RECOVER = "/api/submissions/recover";
    private static final String SOURCE = "print('recover fixture é')\n";

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired JwtTokenProvider jwt;

    @Test void recoversLatestExactOwnRecordWithoutEnqueueingOrExposingSource() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        Account author = account("USER");
        Account learner = account("USER");
        Account operator = account("OPERATOR");
        int number = db.queryForObject("SELECT GREATEST(COALESCE(MAX(problem_number), 0), 810000) + 1 FROM problems", Integer.class);
        long problem = db.queryForObject("""
            INSERT INTO problems(problem_number, problem_title, description, input_description, output_description,
                                 status, visibility, author_id)
            VALUES (?, ?, '', '', '', 'PUBLISHED', 'PUBLIC', ?) RETURNING id
            """, Long.class, number, "Submission recovery test " + number, author.id());

        record(learner, problem, "PYTHON", SOURCE, SubmissionService.sourceDigest(SOURCE));
        long latest = record(learner, problem, "PYTHON", SOURCE, SubmissionService.sourceDigest(SOURCE));
        // 다른 계정과 같은 해시가 있는 이후 행은 기존 결과로 착각하면 안 됩니다.
        record(author, problem, "PYTHON", SOURCE, SubmissionService.sourceDigest(SOURCE));
        record(learner, problem, "PYTHON", "print('mismatched source')", SubmissionService.sourceDigest(SOURCE));
        long before = db.queryForObject("SELECT COUNT(*) FROM submissions WHERE problem_id = ?", Long.class, problem);

        var result = request(mvc, number, "PYTHON", SOURCE, learner, 200);
        assertEquals(latest, json.readTree(result.getResponse().getContentAsString()).path("data").asLong());
        assertTrue(result.getResponse().getHeader("Cache-Control").contains("no-store"));
        assertFalse(result.getResponse().getContentAsString().contains("recover fixture"));
        for (String nearMatch : new String[]{SOURCE.stripTrailing(), SOURCE.replace("é", "e\u0301"), SOURCE.toUpperCase(java.util.Locale.ROOT)}) {
            assertMiss(request(mvc, number, "PYTHON", nearMatch, learner, 200));
        }
        assertMiss(request(mvc, number, "JAVA", SOURCE, learner, 200));
        assertMiss(request(mvc, number, "PYTHON", SOURCE, operator, 200));
        request(mvc, number, "PYTHON", SOURCE, null, 401);

        db.update("UPDATE submissions SET source_sha256 = NULL WHERE id = ?", latest);
        assertEquals(latest, json.readTree(request(mvc, number, "PYTHON", SOURCE, learner, 200)
                .getResponse().getContentAsString()).path("data").asLong());

        db.update("UPDATE problems SET visibility = 'PRIVATE' WHERE id = ?", problem);
        request(mvc, number, "PYTHON", SOURCE, learner, 404);
        assertEquals(before, db.queryForObject("SELECT COUNT(*) FROM submissions WHERE problem_id = ?", Long.class, problem));
        assertEquals(0, db.queryForObject("SELECT submit_count FROM users WHERE id = ?", Integer.class, learner.id()));
        assertEquals(0, db.queryForObject("SELECT submission_count FROM problems WHERE id = ?", Integer.class, problem));
    }

    private void assertMiss(MvcResult result) throws Exception {
        assertTrue(json.readTree(result.getResponse().getContentAsString()).path("data").isNull());
    }

    private long record(Account owner, long problem, String language, String source, String hash) {
        return db.queryForObject("""
                INSERT INTO submissions(user_id, problem_id, language, source_code, source_sha256)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """, Long.class, owner.id(), problem, language, source, hash);
    }

    private Account account(String role) {
        String username = "sr" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        long id = db.queryForObject("INSERT INTO users(username,password,role) VALUES (?,'unused',?) RETURNING id",
                Long.class, username, role);
        return new Account(id, jwt.generateAccessToken(id, username, role, 0));
    }

    private MvcResult request(MockMvc mvc, int number, String language, String code, Account account, int expected) throws Exception {
        var request = post(RECOVER).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("problemNumber", number, "language", language, "sourceCode", code)));
        if (account != null) request.header("Authorization", "Bearer " + account.token());
        return mvc.perform(request).andExpect(status().is(expected)).andReturn();
    }

    private record Account(long id, String token) {}
}
