package com.drewdrew0414.domain.submission;

import com.drewdrew0414.domain.problem.ProblemService;
import com.drewdrew0414.global.common.ApiResponse;
import com.drewdrew0414.global.exception.ApiException;
import com.drewdrew0414.global.security.CustomUserDetails;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/operations")
public class AdminOperationsController {
    private static final int PAGE_SIZE = 30;
    private static final String UTC_NOW = "(CURRENT_TIMESTAMP AT TIME ZONE 'UTC')";
    private static final String RECENT_ERRORS = "s.status = 'SYSTEM_ERROR' AND s.judged_at >= " + UTC_NOW + " - INTERVAL '24 hours'";
    private final JdbcTemplate db;
    public AdminOperationsController(JdbcTemplate db) { this.db = db; }

    @GetMapping
    public ApiResponse<Map<String, Object>> overview(@AuthenticationPrincipal CustomUserDetails actor) {
        ProblemService.requireOperator(actor);
        return ApiResponse.success(db.queryForMap("""
                SELECT COUNT(*) AS queued,
                       COALESCE(GREATEST(0, EXTRACT(EPOCH FROM ((CURRENT_TIMESTAMP AT TIME ZONE 'UTC') - MIN(s.submitted_at)))), 0)::bigint AS "oldestSeconds",
                       (SELECT COUNT(*) FROM submissions WHERE status='RUNNING') AS running,
                       (SELECT COUNT(*) FROM problems WHERE status='PENDING_REVIEW') AS "pendingReview",
                       (SELECT COUNT(*) FROM submissions s WHERE %s) AS "systemErrors"
                FROM submissions s WHERE s.status='QUEUED'
                """.formatted(RECENT_ERRORS)));
    }

    public record SubmissionPage(List<Map<String, Object>> items, boolean hasNext) {}

    @GetMapping("/submissions")
    public ApiResponse<SubmissionPage> submissions(@RequestParam(defaultValue = "QUEUED") String status,
            @RequestParam(defaultValue = "") String language, @RequestParam(defaultValue = "0") int page,
            @AuthenticationPrincipal CustomUserDetails actor) {
        ProblemService.requireOperator(actor);
        if (page < 0 || page > 1_000_000) throw new ApiException(HttpStatus.BAD_REQUEST);
        String condition = switch (status) {
            case "QUEUED" -> "s.status = 'QUEUED'";
            case "RUNNING" -> "s.status = 'RUNNING'";
            case "SYSTEM_ERROR" -> RECENT_ERRORS;
            default -> throw new ApiException(HttpStatus.BAD_REQUEST);
        };
        String sort = status.equals("SYSTEM_ERROR") ? "s.judged_at DESC, s.id DESC" : "s.submitted_at, s.id";
        String filter = language.isBlank() ? "" : Language.parse(language).name();
        // 시험 운영 지표에는 제출 원문·응시자·문제 정보를 포함하지 않습니다.
        var items = db.queryForList("""
                SELECT s.id, s.status, s.language, s.contest_id IS NOT NULL AS contest,
                       CASE WHEN s.contest_id IS NULL THEN p.problem_number END AS "problemNumber",
                       s.submitted_at AT TIME ZONE 'UTC' AS "submittedAt",
                       s.judge_heartbeat_at AT TIME ZONE 'UTC' AS "heartbeatAt",
                       s.judged_at AT TIME ZONE 'UTC' AS "judgedAt"
                FROM submissions s JOIN problems p ON p.id=s.problem_id
                WHERE %s AND (? = '' OR s.language = ?) ORDER BY %s LIMIT ? OFFSET ?
                """.formatted(condition, sort), filter, filter, PAGE_SIZE + 1, (long) page * PAGE_SIZE);
        return ApiResponse.success(new SubmissionPage(items.stream().limit(PAGE_SIZE).toList(), items.size() > PAGE_SIZE));
    }
}
