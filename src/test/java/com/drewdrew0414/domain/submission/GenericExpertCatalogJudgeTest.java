package com.drewdrew0414.domain.submission;

import com.drewdrew0414.global.config.Level0Data;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** 이전 165문제 전체 재채점과 별개로 EXPERT 80문제만 선택 실행합니다. */
@EnabledIfEnvironmentVariable(named="EXPERT_CATALOG_DOCKER_TEST",matches="true")
class GenericExpertCatalogJudgeTest {
    private final ObjectMapper json=new ObjectMapper();
    private Level0Data.Catalog catalog() throws Exception {
        return json.readValue(Files.readString(Path.of("src/main/resources/problems/level3.json")),Level0Data.Catalog.class);
    }
    private DockerJudge judge() {
        return new DockerJudge(System.getenv().getOrDefault("GENERIC_JUDGE_IMAGE","cobweb-languages:local"),"/tmp/cobweb-expert-catalog-test");
    }

    @TestFactory Stream<DynamicTest> eightyExpertReferencesMatchEveryStoredCase() throws Exception {
        var expert=catalog();assertEquals(80,expert.problems().size());
        return expert.problems().stream().map(problem->DynamicTest.dynamicTest(
            "EXPERT "+problem.ordinal()+": "+problem.title()+" ("+problem.tests().size()+" Docker cases)",()->{
                assertEquals("EXPERT",problem.rankType());
                var reference=problem.referenceSolution();assertEquals("PYTHON",reference.language());
                try(var prepared=judge().prepare(Language.PYTHON,reference.sourceCode(),"FUNCTION",problem.functionSignature().toJson(json))) {
                    assertNull(prepared.failure(),prepared::diagnostic);
                    long caseId=0;
                    for(var test:problem.tests()) {
                        var result=prepared.run(test.input(),test.expected(),problem.timeLimitMs(),problem.memoryLimitMb(),++caseId);
                        assertEquals("ACCEPTED",result.status(),()->"case "+result.caseId()+": "+prepared.diagnostic());
                    }
                }
            }));
    }

    @Test void wrongScalarAndWrongNestedArrayResultsAreNotAccepted() throws Exception {
        var expert=catalog();
        for(int ordinal:new int[]{1,5}) {
            var problem=expert.problems().get(ordinal-1);var test=problem.tests().getFirst();
            String source=ordinal==1 ? "def sol(*args):\n    return -2147483648\n" : "def sol(*args):\n    return [[-2147483648]]\n";
            try(var prepared=judge().prepare(Language.PYTHON,source,"FUNCTION",problem.functionSignature().toJson(json))) {
                assertNull(prepared.failure(),prepared::diagnostic);
                var result=prepared.run(test.input(),test.expected(),problem.timeLimitMs(),problem.memoryLimitMb(),1L);
                assertEquals("WRONG_ANSWER",result.status(),prepared::diagnostic);
            }
        }
    }
}
