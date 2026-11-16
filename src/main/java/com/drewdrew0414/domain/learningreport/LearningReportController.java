package com.drewdrew0414.domain.learningreport;

import com.drewdrew0414.domain.problem.ProblemService;
import com.drewdrew0414.global.common.ApiResponse;
import com.drewdrew0414.global.security.CustomUserDetails;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/learning-reports")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class LearningReportController {
    private final LearningReportService reports;

    public LearningReportController(LearningReportService reports) {
        this.reports = reports;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<LearningReportService.Dashboard>> dashboard(
            @AuthenticationPrincipal CustomUserDetails user) {
        var report = reports.dashboard(ProblemService.requireUser(user));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(report));
    }

    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<LearningReportService.Generation>> generate(
            @AuthenticationPrincipal CustomUserDetails user) {
        // User identity, problem IDs, prompt and model selection all come from the server.
        var generation = reports.generate(ProblemService.requireUser(user));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(generation));
    }
}
