package com.drewdrew0414.domain.moderation;

import com.drewdrew0414.global.common.ApiResponse;
import com.drewdrew0414.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
public class ModerationController {
    private final ProblemIssueService issues;
    private final RestrictionService restrictions;
    private final ProblemReleaseService releases;

    @GetMapping("/api/issues")
    public Object mine(@RequestParam(required=false) Long groupId,@RequestParam(required=false) Long contestId,
                       @RequestParam(defaultValue="0") int page,@AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(issues.list(groupId,contestId,false,null,page,user));
    }
    @PostMapping("/api/issues")
    public Object create(@RequestParam(required=false) Long groupId,@RequestParam(required=false) Long contestId,
                         @Valid @RequestBody ProblemIssueService.NewIssue input,@AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(issues.create(groupId,contestId,input,user));
    }
    @GetMapping("/api/admin/moderation/issues")
    public Object inbox(@RequestParam(required=false) Long groupId,@RequestParam(required=false) Long contestId,
                        @RequestParam(required=false) ProblemIssueService.Status status,@RequestParam(defaultValue="0") int page,
                        @AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(issues.list(groupId,contestId,true,status,page,user));
    }
    @PatchMapping("/api/admin/moderation/issues/{id}")
    public Object respond(@PathVariable long id,@RequestParam(required=false) Long groupId,@RequestParam(required=false) Long contestId,
                          @Valid @RequestBody ProblemIssueService.Review input,@AuthenticationPrincipal CustomUserDetails user) {
        issues.review(id,groupId,contestId,input,user);
        return ApiResponse.success(null);
    }
    @GetMapping("/api/admin/moderation/restrictions")
    public Object restrictions(@RequestParam(defaultValue="") String username,@RequestParam(defaultValue="0") int page,@AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(restrictions.list(username,page,user));
    }
    @PostMapping("/api/admin/moderation/restrictions")
    public Object restrict(@Valid @RequestBody RestrictionService.Request input,@AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(restrictions.restrict(input,user));
    }
    @PostMapping("/api/admin/moderation/restrictions/{id}/revoke")
    public Object revoke(@PathVariable long id,@AuthenticationPrincipal CustomUserDetails user) {
        restrictions.revoke(id,user);
        return ApiResponse.success(null);
    }
    @GetMapping("/api/admin/moderation/releases")
    public Object releases(@RequestParam(defaultValue="0") int page,@AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(releases.list(page,user));
    }
    @PostMapping("/api/admin/moderation/releases")
    public Object schedule(@Valid @RequestBody ProblemReleaseService.Request input,@AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(releases.schedule(input,user));
    }
    @PostMapping("/api/admin/moderation/releases/{id}/cancel")
    public Object cancel(@PathVariable long id,@AuthenticationPrincipal CustomUserDetails user) {
        releases.cancel(id,user);
        return ApiResponse.success(null);
    }
}
