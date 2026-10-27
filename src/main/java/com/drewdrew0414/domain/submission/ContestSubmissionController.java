package com.drewdrew0414.domain.submission;

import com.drewdrew0414.global.common.ApiResponse;
import com.drewdrew0414.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.drewdrew0414.global.exception.ApiException;

/** 그룹 경로와 시험 소속을 확인하고 일반 제출과 분리된 시험 답안을 접수합니다. */
@RestController @RequiredArgsConstructor
@RequestMapping("/api/groups/{groupId}/contests/{contestId}/submissions")
public class ContestSubmissionController {
    private final SubmissionService submissions;
    private final JdbcTemplate db;

    @PostMapping
    public Object submit(@PathVariable long groupId,@PathVariable long contestId,
                         @Valid @RequestBody SubmissionController.Request request,
                         @AuthenticationPrincipal CustomUserDetails user) {
        if(db.queryForObject("SELECT COUNT(*) FROM group_contests WHERE id=? AND group_id=?",
                Long.class,contestId,groupId)==0)
            throw new ApiException(HttpStatus.NOT_FOUND,"시험을 찾을 수 없습니다.");
        return ApiResponse.success(submissions.submitContest(contestId,request.problemNumber(),
            request.sourceCode(),Language.parse(request.language()),user));
    }
}
