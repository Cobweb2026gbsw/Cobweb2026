package com.drewdrew0414.domain.problem;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

/** 공개 본문과 비공개 테스트 데이터를 입력받는 관리자 DTO입니다. 응답에는 이 DTO를 재사용하지 않습니다. */
public class ProblemRequests {
    public record Example(@NotNull @Size(max=65535) String input,
                          @NotNull @Size(max=65535) String output, @Size(max=65535) String explanation) {}
    public record TestCase(@NotNull @Size(max=1000000) String input,
                           @NotNull @Size(max=1000000) String expected) {}
    public record ReferenceSolution(@NotBlank String language,
                                    @NotBlank @Size(max=120) String fileName,
                                    @NotBlank String sourceCode) {}
    public record Write(
        @NotBlank @Size(max=200) String title,
        @NotBlank @Size(max=1000000) String description,
        @NotNull @Size(max=65535) String inputDescription,
        @NotNull @Size(max=65535) String outputDescription,
        @Size(max=65535) String constraints,
        @Pattern(regexp="INITIATE|SKILLED|ELITE|EXPERT|LEGEND|MYTHIC|ABSOLUTE") @NotNull String rankType,
        @Min(1) @Max(5) int rankInt,
        @Min(100) @Max(10000) int timeLimitMs,
        @Min(64) @Max(512) int memoryLimitMb,
        @Pattern(regexp="STDIN|FUNCTION|SQL") @NotNull String executionMode,
        @NotNull @Size(max=20) List<@NotNull @Valid Example> examples,
        @NotNull @Size(max=50) List<@NotNull @Valid TestCase> tests,
        @Size(max=13) List<@NotBlank String> languages,
        @Size(max=10) List<@NotBlank @Size(max=50) String> tags,
        @Size(max=255) String sourceName,
        @Size(max=1000) String sourceUrl,
        @Size(max=13) List<@NotNull @Valid ReferenceSolution> referenceSolutions,
        @Positive Long groupId,
        @Pattern(regexp="PUBLIC|PRIVATE|GROUP") String visibility
    ) {
        /** 기존 12개 필드 생성자와 초기 클라이언트 요청을 유지합니다. */
        public Write(String title,String description,String inputDescription,String outputDescription,
                     String constraints,String rankType,int rankInt,int timeLimitMs,int memoryLimitMb,
                     String executionMode,List<Example> examples,List<TestCase> tests) {
            this(title,description,inputDescription,outputDescription,constraints,rankType,rankInt,
                timeLimitMs,memoryLimitMb,executionMode,examples,tests,null,null,null,null,null,null,null);
        }
    }
    public record Review(@Pattern(regexp="PUBLISHED|REJECTED|ARCHIVED") @NotNull String status,
                         @Size(max=2000) String cause) {}
    public record Visibility(@Pattern(regexp="PUBLIC|PRIVATE") @NotNull String visibility,
                             @Size(max=2000) String cause) {}
}
