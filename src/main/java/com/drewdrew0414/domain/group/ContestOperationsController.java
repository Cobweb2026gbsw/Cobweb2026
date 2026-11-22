package com.drewdrew0414.domain.group;

import com.drewdrew0414.global.common.ApiResponse;
import com.drewdrew0414.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/groups/{gid}/contests/{cid}")
@RequiredArgsConstructor
public class ContestOperationsController {
    private final ContestCopyService copies;
    private final ContestCommunicationService messages;
    private final ContestScorecardService scores;

    public record Copy(@Positive long targetGroupId,@NotNull Instant startsAt,@NotNull Instant endsAt,@NotNull UUID requestId) {}
    public record Ask(@Positive Integer problemNumber,@NotBlank @Size(max=2000) String body) {}
    public record Reply(@NotBlank @Size(max=2000) String body,boolean publish) {}
    public record Notice(@NotBlank @Size(max=2000) String body) {}

    @PostMapping("/copies")
    public Object copy(@PathVariable long gid,@PathVariable long cid,@Valid @RequestBody Copy r,@AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(copies.copy(gid,cid,r.targetGroupId(),r.startsAt(),r.endsAt(),r.requestId(),user));
    }
    @GetMapping("/scorecard")
    public Object scorecard(@PathVariable long gid,@PathVariable long cid,@RequestParam(required=false) Long userId,
                            @RequestParam(defaultValue="0") @PositiveOrZero long afterUser,@AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(scores.page(gid,cid,userId,afterUser,user));
    }
    @GetMapping("/communications")
    public Object communications(@PathVariable long gid,@PathVariable long cid,@RequestParam(required=false) @Positive Long beforeId,@AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(messages.messages(gid,cid,beforeId,user));
    }
    @PostMapping("/questions")
    public Object ask(@PathVariable long gid,@PathVariable long cid,@Valid @RequestBody Ask r,@AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(messages.ask(gid,cid,r.problemNumber(),r.body(),user));
    }
    @PostMapping("/questions/{id}/answer")
    public Object answer(@PathVariable long gid,@PathVariable long cid,@PathVariable long id,@Valid @RequestBody Reply r,@AuthenticationPrincipal CustomUserDetails user) {
        messages.answer(gid,cid,id,r.body(),r.publish(),user);
        return ApiResponse.success(null);
    }
    @PostMapping("/announcements")
    public Object postNotice(@PathVariable long gid,@PathVariable long cid,@Valid @RequestBody Notice r,@AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(messages.announce(gid,cid,r.body(),user));
    }
}
