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

/** All catalog references run inside the real isolated judge, once prepared per problem. */
@EnabledIfEnvironmentVariable(named="GENERIC_CATALOG_DOCKER_TEST",matches="true")
class GenericLevel0CatalogJudgeTest {
    @TestFactory Stream<DynamicTest> allHundredReferencesAndAll812StoredCases() throws Exception {
        var json=new ObjectMapper();
        var catalog=json.readValue(Files.readString(Path.of("src/main/resources/problems/level0.json")),Level0Data.Catalog.class);
        assertEquals(100,catalog.problems().size());
        assertEquals(812,catalog.problems().stream().mapToInt(p->p.tests().size()).sum());
        return catalog.problems().stream().map(problem->DynamicTest.dynamicTest(
            "Lv.0 "+problem.ordinal()+": "+problem.title()+" ("+problem.tests().size()+" Docker cases)",()->{
                var reference=problem.referenceSolution();
                assertEquals("PYTHON",reference.language());
                var judge=new DockerJudge(System.getenv().getOrDefault("GENERIC_JUDGE_IMAGE","cobweb-languages:local"),"/tmp/cobweb-catalog-test");
                try(var prepared=judge.prepare(Language.PYTHON,reference.sourceCode(),"FUNCTION",problem.functionSignature().toJson(json))){
                    assertNull(prepared.failure(),prepared::diagnostic);
                    long caseId=0;
                    for(var test:problem.tests()){
                        var result=prepared.run(test.input(),test.expected(),problem.timeLimitMs(),problem.memoryLimitMb(),++caseId);
                        assertEquals("ACCEPTED",result.status(),()->"case "+result.caseId()+": "+prepared.diagnostic());
                    }
                }
            }));
    }
}
