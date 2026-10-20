package com.drewdrew0414.domain.community;

import com.drewdrew0414.global.common.ApiResponse;
import com.drewdrew0414.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/community") @RequiredArgsConstructor
public class CommunityController {
    private final CommunityService service;

    @GetMapping("/posts")
    public Object posts(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="") String search,
                        @RequestParam(defaultValue="") String category,
                        @AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(service.posts(page,search,category,false,user));
    }
    @GetMapping("/notices")
    public Object notices(@RequestParam(defaultValue="0") int page,
                          @AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(service.posts(page,"","NOTICE",false,user));
    }
    @GetMapping("/posts/{id}")
    public Object post(@PathVariable long id,@AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(service.post(id,false,user));
    }
    @GetMapping("/posts/{id}/comments")
    public Object comments(@PathVariable long id,@RequestParam(required=false) Long parentId,
                           @RequestParam(defaultValue="0") int page,
                           @AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(service.comments(id,parentId,page,user));
    }
    @PostMapping("/posts")
    public Object createPost(@Valid @RequestBody CommunityRequests.PostWrite request,
                             @AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(service.createPost(request,user));
    }
    @PutMapping("/posts/{id}")
    public Object updatePost(@PathVariable long id,@Valid @RequestBody CommunityRequests.PostUpdate request,
                             @AuthenticationPrincipal CustomUserDetails user) {
        service.updatePost(id,request,user); return ApiResponse.success(null);
    }
    @DeleteMapping("/posts/{id}")
    public Object deletePost(@PathVariable long id,@RequestParam long version,
                             @AuthenticationPrincipal CustomUserDetails user) {
        service.deletePost(id,version,user); return ApiResponse.success(null);
    }
    @PostMapping("/posts/{id}/comments")
    public Object createComment(@PathVariable long id,@Valid @RequestBody CommunityRequests.CommentWrite request,
                                @AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(service.createComment(id,request,user));
    }
    @PutMapping("/comments/{id}")
    public Object updateComment(@PathVariable long id,@Valid @RequestBody CommunityRequests.CommentUpdate request,
                                @AuthenticationPrincipal CustomUserDetails user) {
        service.updateComment(id,request,user); return ApiResponse.success(null);
    }
    @DeleteMapping("/comments/{id}")
    public Object deleteComment(@PathVariable long id,@RequestParam long version,
                                @AuthenticationPrincipal CustomUserDetails user) {
        service.deleteComment(id,version,user); return ApiResponse.success(null);
    }
}
