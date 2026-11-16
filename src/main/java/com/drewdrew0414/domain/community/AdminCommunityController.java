package com.drewdrew0414.domain.community;

import com.drewdrew0414.global.common.ApiResponse;
import com.drewdrew0414.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/admin/community") @RequiredArgsConstructor
public class AdminCommunityController {
    private final CommunityService service;

    @GetMapping("/posts")
    public Object posts(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="") String search,
                        @RequestParam(defaultValue="") String category,
                        @RequestParam(defaultValue="false") boolean includeDeleted,
                        @AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(service.adminPosts(page,search,category,includeDeleted,user));
    }
    @GetMapping("/posts/{id}")
    public Object post(@PathVariable long id,@AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(service.adminPost(id,user));
    }
    @GetMapping("/posts/{id}/comments")
    public Object comments(@PathVariable long id,@RequestParam(required=false) Long parentId,
                           @RequestParam(defaultValue="0") int page,
                           @AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(service.adminComments(id,parentId,page,user));
    }
    @GetMapping("/posts/{id}/history")
    public Object postHistory(@PathVariable long id,@RequestParam(defaultValue="0") int page,
                              @AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(service.postHistory(id,page,user));
    }
    @GetMapping("/comments/{id}/history")
    public Object commentHistory(@PathVariable long id,@RequestParam(defaultValue="0") int page,
                                 @AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(service.commentHistory(id,page,user));
    }
    @PostMapping("/posts/{id}/restore")
    public Object restorePost(@PathVariable long id,@Valid @RequestBody CommunityRequests.Restore request,
                              @AuthenticationPrincipal CustomUserDetails user) {
        service.restorePost(id,request,user); return ApiResponse.success(null);
    }
    @PostMapping("/comments/{id}/restore")
    public Object restoreComment(@PathVariable long id,@Valid @RequestBody CommunityRequests.Restore request,
                                 @AuthenticationPrincipal CustomUserDetails user) {
        service.restoreComment(id,request,user); return ApiResponse.success(null);
    }
}
