package com.drewdrew0414.domain.submission;

import com.drewdrew0414.global.config.Level0Data;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** 신규 문제의 참고 풀이와 저장된 모든 케이스를 실제 격리 경로에서 대조합니다. */
@EnabledIfEnvironmentVariable(named="GENERIC_CATALOG_DOCKER_TEST",matches="true")
class GenericAlgorithmCatalogJudgeTest {
    @TestFactory Stream<DynamicTest> allAlgorithmReferencesAndStoredCases() throws Exception {
        var json=new ObjectMapper();
        var skilled=json.readValue(Files.readString(Path.of("src/main/resources/problems/level1.json")),Level0Data.Catalog.class);
        var elite=json.readValue(Files.readString(Path.of("src/main/resources/problems/level2.json")),Level0Data.Catalog.class);
        assertEquals(75,skilled.problems().size());assertEquals(90,elite.problems().size());
        return Stream.concat(skilled.problems().stream(),elite.problems().stream()).map(problem->DynamicTest.dynamicTest(
            problem.rankType()+" "+problem.ordinal()+": "+problem.title()+" ("+problem.tests().size()+" Docker cases)",()->{
                var judge=new DockerJudge(System.getenv().getOrDefault("GENERIC_JUDGE_IMAGE","cobweb-languages:local"),"/tmp/cobweb-algorithm-catalog-test");
                var reference=problem.referenceSolution();assertEquals("PYTHON",reference.language());
                try(var prepared=judge.prepare(Language.PYTHON,reference.sourceCode(),"FUNCTION",problem.functionSignature().toJson(json))){
                    assertNull(prepared.failure(),prepared::diagnostic);
                    long index=0;
                    for(var test:problem.tests()){
                        var result=prepared.run(test.input(),test.expected(),problem.timeLimitMs(),problem.memoryLimitMb(),++index);
                        assertEquals("ACCEPTED",result.status(),()->"case "+result.caseId()+": "+prepared.diagnostic());
                    }
                }
            }));
    }
}
