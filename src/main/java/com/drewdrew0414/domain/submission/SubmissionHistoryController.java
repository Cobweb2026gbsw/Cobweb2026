package com.drewdrew0414.domain.submission;

import com.drewdrew0414.global.common.ApiResponse;
import com.drewdrew0414.global.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/submissions/history")
@RequiredArgsConstructor
public class SubmissionHistoryController {
    private final SubmissionHistory history;

    @GetMapping
    public Object page(@AuthenticationPrincipal CustomUserDetails user,@RequestParam(defaultValue="") String q,
            @RequestParam(defaultValue="") String result,@RequestParam(defaultValue="") String language,
            @RequestParam(defaultValue="0") int page) {
        return ApiResponse.success(history.page(user,q,result,language,page));
    }

    @GetMapping("/export")
    public Object export(@AuthenticationPrincipal CustomUserDetails user,@RequestParam(defaultValue="") String q,
            @RequestParam(defaultValue="") String result,@RequestParam(defaultValue="") String language,
            @RequestParam(required=false) Long before) {
        return ApiResponse.success(history.exportBatch(user,q,result,language,before));
    }
}
