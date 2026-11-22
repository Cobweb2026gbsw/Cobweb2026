package com.drewdrew0414.domain.submission;

import com.drewdrew0414.global.common.ApiResponse;
import com.drewdrew0414.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/admin/judging") @RequiredArgsConstructor
public class AdminJudgingController {
    private final AdminJudgingService service;

    public record Create(@Positive int problemNumber, @NotNull Kind kind, @Positive Long submissionId,
                         @Positive Long groupId, @Positive Long contestId, @Size(max=500) String reason) {}
    public enum Kind { REFERENCE, REJUDGE }
    public record Decision(@NotBlank String previewToken) {}

    @PostMapping public Object create(@Valid @RequestBody Create request,@AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(service.create(request,user));
    }
    public record Scope(Long groupId,Long contestId) {}
    @GetMapping public Object history(@RequestParam(defaultValue="0") int page, Scope scope,@AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(service.history(page,scope,user));
    }
    @GetMapping("/{id}") public Object detail(@PathVariable long id,@RequestParam(defaultValue="0") int page, Scope scope,@AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(service.detail(id,page,scope,user));
    }
    @PostMapping("/{id}/apply") public Object apply(@PathVariable long id,@Valid @RequestBody Decision decision, Scope scope,@AuthenticationPrincipal CustomUserDetails user) {
        service.apply(id,decision.previewToken(),scope,user);
        return ApiResponse.success(null);
    }
    @PostMapping("/{id}/cancel") public Object cancel(@PathVariable long id,Scope scope,@AuthenticationPrincipal CustomUserDetails user) {
        service.cancel(id,scope,user);
        return ApiResponse.success(null);
    }
}
