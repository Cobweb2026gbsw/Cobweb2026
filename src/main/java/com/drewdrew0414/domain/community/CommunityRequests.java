package com.drewdrew0414.domain.community;

import jakarta.validation.constraints.*;

public class CommunityRequests {
    public record PostWrite(@NotBlank @Size(max=200) String title,
                            @NotBlank @Size(max=20000) String body,
                            @NotNull @Pattern(regexp="HARDWARE|SOFTWARE|NEWS|PROBLEM_QNA|MAJOR_QNA|GENERAL|NOTICE") String category,
                            @Positive Integer problemNumber) {}
    public record PostUpdate(@NotBlank @Size(max=200) String title,
                             @NotBlank @Size(max=20000) String body,
                             @NotNull @Pattern(regexp="HARDWARE|SOFTWARE|NEWS|PROBLEM_QNA|MAJOR_QNA|GENERAL|NOTICE") String category,
                             @Positive Integer problemNumber,
                             @NotNull @Min(1) Long version,
                             Boolean preserveProblemLink) {
        /** 기존 전체 교체 요청은 유지하고, 숨겨진 연결의 제목/본문 편집에만 보존 의도를 명시합니다. */
        public PostUpdate(String title,String body,String category,Integer problemNumber,Long version) {
            this(title,body,category,problemNumber,version,false);
        }
    }
    public record CommentWrite(@Positive Long parentId,@NotBlank @Size(max=5000) String body) {}
    public record CommentUpdate(@NotBlank @Size(max=5000) String body,@NotNull @Min(1) Long version) {}
    public record Restore(@NotNull @Min(1) Long version,@Size(max=2000) String reason) {}
}
