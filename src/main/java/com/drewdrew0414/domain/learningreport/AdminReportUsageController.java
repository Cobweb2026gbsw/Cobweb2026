package com.drewdrew0414.domain.learningreport;

import com.drewdrew0414.domain.problem.ProblemService;
import com.drewdrew0414.global.common.ApiResponse;
import com.drewdrew0414.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/report-usage")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AdminReportUsageController {
    private final LearningReportRepository reports;
    public AdminReportUsageController(LearningReportRepository reports) { this.reports = reports; }

    public record Control(@NotNull Boolean paused) {}

    @GetMapping
    public ApiResponse<LearningReportRepository.Usage> usage(@AuthenticationPrincipal CustomUserDetails actor) {
        ProblemService.requireOperator(actor);
        return ApiResponse.success(reports.usage(LocalDate.now(LearningReportRepository.SEOUL)));
    }

    @PatchMapping
    public ApiResponse<Void> change(@Valid @RequestBody Control control, @AuthenticationPrincipal CustomUserDetails actor) {
        ProblemService.requireOperator(actor);
        reports.setPaused(control.paused(), actor.getUserId());
        return ApiResponse.success(null);
    }
}
