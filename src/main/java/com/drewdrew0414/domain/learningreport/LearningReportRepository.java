package com.drewdrew0414.domain.learningreport;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.drewdrew0414.domain.learningreport.LearningReportScores.BATCH_SIZE;
import static com.drewdrew0414.domain.learningreport.LearningReportScores.PRACTICE_DAYS;

@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class LearningReportRepository {
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int DAILY_ACCOUNT_LIMIT = 5;
    private static final int DAILY_TOTAL_LIMIT = 100;

    private static final String KOREAN_SOLVED_DATE =
            "CAST((s.solved_at AT TIME ZONE 'UTC') AT TIME ZONE 'Asia/Seoul' AS date)";
    private static final String PUBLIC_SOLVES = """
            FROM user_solved_problems s
            JOIN problems p ON p.id = s.problem_id
            WHERE s.user_id = ? AND p.status = 'PUBLISHED' AND p.visibility = 'PUBLIC' AND p.group_id IS NULL
            """;
    private static final String UNASSIGNED = """
            AND NOT EXISTS (
                SELECT 1 FROM learning_report_problems batch
                WHERE batch.user_id = s.user_id AND batch.problem_id = s.problem_id
            )
            """;
    private static final String ATTEMPT_EVIDENCE = """
            WITH accepted AS (
                SELECT id, submitted_at, language
                FROM submissions
                WHERE user_id = ? AND problem_id = ? AND contest_id IS NULL AND status = 'ACCEPTED'
                ORDER BY submitted_at, id
                LIMIT 1
            ), earlier_attempts AS (
                SELECT a.id, a.submitted_at, a.status
                FROM submissions a CROSS JOIN accepted
                WHERE a.user_id = ? AND a.problem_id = ? AND a.contest_id IS NULL
                  AND (a.submitted_at, a.id) < (accepted.submitted_at, accepted.id)
            ), failures AS (
                SELECT status, ROW_NUMBER() OVER (ORDER BY submitted_at, id) AS position
                FROM earlier_attempts
                WHERE status IN ('WRONG_ANSWER', 'RUNTIME_ERROR', 'COMPILATION_ERROR',
                                 'TIME_LIMIT_EXCEEDED', 'MEMORY_LIMIT_EXCEEDED')
            )
            SELECT accepted.language, failure_stats.*,
                   EXISTS(SELECT 1 FROM earlier_attempts WHERE status IN ('QUEUED', 'RUNNING', 'SYSTEM_ERROR'))
                       AS earlier_attempt_uncertain
            FROM accepted CROSS JOIN LATERAL (
                SELECT COUNT(*) AS failure_count,
                       MIN(position) FILTER (WHERE status IN ('WRONG_ANSWER', 'RUNTIME_ERROR', 'COMPILATION_ERROR'))
                           AS first_ordinary_error,
                       MIN(position) FILTER (WHERE status IN ('TIME_LIMIT_EXCEEDED', 'MEMORY_LIMIT_EXCEEDED'))
                           AS first_limit_error,
                       COUNT(*) FILTER (WHERE status = 'WRONG_ANSWER') AS wrong_answers,
                       COUNT(*) FILTER (WHERE status = 'COMPILATION_ERROR') AS compilation_errors,
                       COUNT(*) FILTER (WHERE status = 'RUNTIME_ERROR') AS runtime_errors,
                       COUNT(*) FILTER (WHERE status = 'TIME_LIMIT_EXCEEDED') AS time_limits,
                       COUNT(*) FILTER (WHERE status = 'MEMORY_LIMIT_EXCEEDED') AS memory_limits
                FROM failures
            ) failure_stats
            """;

    private final JdbcTemplate db;
    private final ObjectMapper json;

    public LearningReportRepository(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public LearningReportService.Dashboard dashboard(long userId, LocalDate today, boolean gatewayAvailable) {
        int unassigned = countUnassigned(userId);
        OpenReport pending = openReport(userId, false);
        int availableBatches = unassigned / BATCH_SIZE + (pending == null ? 0 : 1);
        String state = state(pending, gatewayAvailable, availableBatches == 0 || hasBudget(userId, today));
        return new LearningReportService.Dashboard(
                dailyActivity(userId, today), BATCH_SIZE - (unassigned % BATCH_SIZE),
                availableBatches, state, storedReports(userId));
    }

    @Transactional
    public Decision begin(long userId, LocalDate today) {
        // Per-account lock only spans reads and reservation, never the external HTTP request.
        var account = db.queryForList("SELECT id FROM users WHERE id = ? AND status = 'ACTIVE' FOR UPDATE", userId);
        if (account.isEmpty()) return Decision.only("unavailable");

        OpenReport pending = openReport(userId, true);
        String state = state(pending, true, true);
        if (!"idle".equals(state)) return Decision.only(state);
        if (pending == null && countUnassigned(userId) < BATCH_SIZE) return Decision.only("idle");
        if (!hasBudget(userId, today)) return Decision.only("retry_later");

        // Capture only the normalized facts at reservation. Future retry uses precisely the same batch.
        NewReport next = pending == null ? nextReport(userId) : null;
        if (next == null && pending == null) return Decision.only("idle");
        if (!reserveBudget(userId, today)) return Decision.only("retry_later");

        UUID token = UUID.randomUUID();
        if (next == null) {
            db.update("""
                    UPDATE learning_reports
                    SET status = 'RUNNING', claim_token = ?, claim_until = clock_timestamp() + INTERVAL '2 minutes',
                        retry_after = NULL
                    WHERE user_id = ? AND report_number = ?
                    """, token, userId, pending.number());
            return Decision.running(new Claim(userId, pending.number(), token, pending.facts(), previousSummary(userId)));
        }

        int reportNumber = Objects.requireNonNull(db.queryForObject("""
                SELECT COALESCE(MAX(report_number), 0) + 1 FROM learning_reports WHERE user_id = ?
                """, Integer.class, userId));
        String facts = json.writeValueAsString(next.facts());
        var draft = new LearningReportService.Report(1, reportNumber,
                next.problems().stream().map(SolvedProblem::number).toList(), next.facts().radar(),
                next.facts().problemSolvingScore(), null, null);
        db.update("""
                INSERT INTO learning_reports (user_id, report_number, status, report_json, facts_json, claim_token, claim_until)
                VALUES (?, ?, 'RUNNING', CAST(? AS jsonb), CAST(? AS jsonb), ?, clock_timestamp() + INTERVAL '2 minutes')
                """, userId, reportNumber, json.writeValueAsString(draft), facts, token);
        for (int i = 0; i < next.problems().size(); i++)
            db.update("""
                    INSERT INTO learning_report_problems (user_id, report_number, problem_id, position)
                    VALUES (?, ?, ?, ?)
                    """, userId, reportNumber, next.problems().get(i).id(), i + 1);
        return Decision.running(new Claim(userId, reportNumber, token, facts, previousSummary(userId)));
    }

    public void commit(Claim claim, String userSummary, String carryForwardSummary, String createdAt) {
        db.update("""
                UPDATE learning_reports
                SET status = 'READY', report_json = report_json || jsonb_build_object(
                        'userSummary', CAST(? AS text), 'createdAt', CAST(? AS text)),
                    facts_json = NULL, carry_forward_summary = ?, completed_at = clock_timestamp(),
                    claim_token = NULL, claim_until = NULL
                WHERE user_id = ? AND report_number = ? AND claim_token = ? AND status = 'RUNNING'
                """, userSummary, createdAt, carryForwardSummary, claim.userId(), claim.number(), claim.token());
    }

    public void fail(Claim claim) {
        db.update("""
                UPDATE learning_reports
                SET status = 'RETRY', retry_after = clock_timestamp() + INTERVAL '15 minutes',
                    claim_token = NULL, claim_until = NULL
                WHERE user_id = ? AND report_number = ? AND claim_token = ? AND status = 'RUNNING'
                """, claim.userId(), claim.number(), claim.token());
    }

    private int countUnassigned(long userId) {
        return Objects.requireNonNull(db.queryForObject("SELECT COUNT(*) " + PUBLIC_SOLVES + UNASSIGNED,
                Integer.class, userId));
    }

    private List<LearningReportService.ActivityDay> dailyActivity(long userId, LocalDate today) {
        return db.query("SELECT " + KOREAN_SOLVED_DATE + " AS activity_day, COUNT(*) AS solved_count " +
                        PUBLIC_SOLVES + " AND s.solved_at >= ? AND s.solved_at < ? GROUP BY 1 ORDER BY 1",
                (rs, row) -> new LearningReportService.ActivityDay(
                        rs.getObject("activity_day", LocalDate.class).toString(), rs.getInt("solved_count")),
                userId, utcStart(today.minusDays(364)), utcStart(today.plusDays(1)));
    }

    private List<LearningReportService.Report> storedReports(long userId) {
        return db.query("""
                SELECT report_json::text AS data
                FROM learning_reports WHERE user_id = ? AND status = 'READY' ORDER BY report_number DESC
                """, (rs, row) -> json.readValue(rs.getString("data"), LearningReportService.Report.class), userId);
    }

    boolean generationRunning(long userId) {
        OpenReport pending = openReport(userId, false);
        return pending != null && "RUNNING".equals(pending.status()) && pending.leaseActive();
    }

    private OpenReport openReport(long userId, boolean forUpdate) {
        return db.query("""
                SELECT report_number, status, facts_json::text AS facts,
                       claim_until > clock_timestamp() AS lease_active,
                       retry_after > clock_timestamp() AS cooling_down
                FROM learning_reports WHERE user_id = ? AND status IN ('RUNNING', 'RETRY')
                """ + (forUpdate ? " FOR UPDATE" : ""),
                (rs, row) -> new OpenReport(rs.getInt("report_number"), rs.getString("status"),
                        rs.getBoolean("lease_active"), rs.getBoolean("cooling_down"), rs.getString("facts")), userId)
                .stream().findFirst().orElse(null);
    }

    private static String state(OpenReport pending, boolean gatewayAvailable, boolean hasBudget) {
        if (pending != null && "RUNNING".equals(pending.status()) && pending.leaseActive()) return "running";
        if (!gatewayAvailable) return "unavailable";
        if ((pending != null && pending.coolingDown()) || !hasBudget) return "retry_later";
        return "idle";
    }

    private boolean hasBudget(long userId, LocalDate today) {
        return Boolean.TRUE.equals(db.queryForObject("""
                SELECT COALESCE((SELECT attempts FROM learning_report_daily_budget WHERE usage_date = ?), 0) < ?
                   AND COALESCE((SELECT attempts FROM learning_report_user_daily_budget WHERE user_id = ? AND usage_date = ?), 0) < ?
                """, Boolean.class, today, DAILY_TOTAL_LIMIT, userId, today, DAILY_ACCOUNT_LIMIT));
    }

    private boolean reserveBudget(long userId, LocalDate today) {
        db.update("INSERT INTO learning_report_daily_budget (usage_date, attempts) VALUES (?, 0) ON CONFLICT DO NOTHING",
                today);
        int changed = db.update("""
                UPDATE learning_report_daily_budget SET attempts = attempts + 1 WHERE usage_date = ? AND attempts < ?
                """, today, DAILY_TOTAL_LIMIT);
        if (changed == 0) return false;
        // The account lock keeps this counter serialized for this user across service instances.
        db.update("""
                INSERT INTO learning_report_user_daily_budget (user_id, usage_date, attempts) VALUES (?, ?, 1)
                ON CONFLICT (user_id, usage_date) DO UPDATE SET attempts = learning_report_user_daily_budget.attempts + 1
                """, userId, today);
        return true;
    }

    private String previousSummary(long userId) {
        return db.query("""
                SELECT carry_forward_summary FROM learning_reports
                WHERE user_id = ? AND status = 'READY' ORDER BY report_number DESC LIMIT 1
                """, (rs, row) -> rs.getString("carry_forward_summary"), userId).stream().findFirst().orElse("");
    }

    private NewReport nextReport(long userId) {
        var problems = db.query("""
                SELECT p.id, p.problem_number, p.rank_type, p.rank_int, s.solved_at,
                       ARRAY(SELECT t.name FROM problem_tags pt JOIN tags t ON t.id = pt.tag_id WHERE pt.problem_id = p.id) AS tags
                """ + PUBLIC_SOLVES + UNASSIGNED + " ORDER BY s.solved_at, s.problem_id LIMIT " + BATCH_SIZE,
                (rs, row) -> new SolvedProblem(rs.getLong("id"), rs.getInt("problem_number"),
                        rs.getObject("solved_at", LocalDateTime.class), rs.getString("rank_type"),
                        rs.getInt("rank_int"), tags(rs)), userId);
        if (problems.size() != BATCH_SIZE) return null;
        var evidence = problems.stream().map(problem -> new LearningReportScores.ProblemEvidence(
                problem.rankType(), problem.rankLevel(), problem.tags(), attempts(userId, problem.id()))).toList();
        int activeDays = practiceDays(userId, problems.getLast().solvedAt());
        return new NewReport(problems, LearningReportScores.evaluate(evidence, activeDays));
    }

    private static List<String> tags(ResultSet rs) throws SQLException {
        Array array = rs.getArray("tags");
        try {
            return List.of((String[]) array.getArray());
        } finally {
            array.free();
        }
    }

    private LearningReportScores.AttemptEvidence attempts(long userId, long problemId) {
        return db.query(ATTEMPT_EVIDENCE, (rs, row) -> new LearningReportScores.AttemptEvidence(
                rs.getLong("failure_count"), rs.getObject("first_ordinary_error", Long.class),
                rs.getObject("first_limit_error", Long.class), rs.getLong("wrong_answers"),
                rs.getLong("compilation_errors"), rs.getLong("runtime_errors"), rs.getLong("time_limits"),
                rs.getLong("memory_limits"), rs.getString("language"), rs.getBoolean("earlier_attempt_uncertain")),
                userId, problemId, userId, problemId)
                .stream().findFirst().orElse(null);
    }

    private int practiceDays(long userId, LocalDateTime lastSolveUtc) {
        LocalDate end = lastSolveUtc.atOffset(ZoneOffset.UTC).atZoneSameInstant(SEOUL).toLocalDate();
        return Objects.requireNonNull(db.queryForObject("SELECT COUNT(DISTINCT " + KOREAN_SOLVED_DATE + ") " +
                PUBLIC_SOLVES + " AND s.solved_at >= ? AND s.solved_at <= ?", Integer.class,
                userId, utcStart(end.minusDays(PRACTICE_DAYS - 1)), lastSolveUtc));
    }

    private static LocalDateTime utcStart(LocalDate koreanDate) {
        return koreanDate.atStartOfDay(SEOUL).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    record Claim(long userId, int number, UUID token, String facts, String previousSummary) {}

    record Decision(String state, Claim claim) {
        static Decision only(String state) { return new Decision(state, null); }
        static Decision running(Claim claim) { return new Decision("running", claim); }
    }

    private record OpenReport(int number, String status, boolean leaseActive, boolean coolingDown, String facts) {}
    private record SolvedProblem(long id, int number, LocalDateTime solvedAt, String rankType, int rankLevel,
                                 List<String> tags) {}
    private record NewReport(List<SolvedProblem> problems, LearningReportScores.Facts facts) {}
}
