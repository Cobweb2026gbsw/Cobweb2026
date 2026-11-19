package com.drewdrew0414.domain.submission;

import com.drewdrew0414.domain.problem.CurriculumLevel;
import com.drewdrew0414.global.config.Level0Data;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 각 고급 문제의 가장 큰 저장 입력을 배포용 Python 함수 하네스로 실제 격리 채점합니다. */
@EnabledIfEnvironmentVariable(named="ADVANCED_CATALOG_DOCKER_TEST",matches="true")
class AdvancedCatalogJudgeTest {
    @TestFactory List<DynamicTest> largestInputOfEveryAdvancedProblem() throws Exception {
        var json=new ObjectMapper();
        var judge=new DockerJudge(System.getenv().getOrDefault("GENERIC_JUDGE_IMAGE","cobweb-languages:local"),
                "/tmp/cobweb-advanced-catalog-test");
        var tests=new ArrayList<DynamicTest>();
        for(var level:CurriculumLevel.values()) {
            if(!level.advanced()) continue;
            var catalog=json.readValue(Files.readString(Path.of("src/main/resources",level.resource())),Level0Data.Catalog.class);
            for(var problem:catalog.problems()) {
                tests.add(DynamicTest.dynamicTest(problem.number()+" "+problem.title(),()->{
                    var largest=problem.tests().stream().max(Comparator.comparingInt(t->t.input().length())).orElseThrow();
                    int limit=Language.PYTHON.timeLimitMs(Map.of("time_limit_ms",problem.timeLimitMs(),"language_adjusted_time",true));
                    var signature=problem.functionSignature().toJson(json);
                    try(var prepared=judge.prepare(Language.PYTHON,problem.referenceSolution().sourceCode(),"FUNCTION",signature)) {
                        assertNull(prepared.failure(),prepared::diagnostic);
                        assertEquals("ACCEPTED",prepared.run(largest.input(),largest.expected(),limit,problem.memoryLimitMb(),1L).status(),
                                prepared::diagnostic);
                    }
                }));
            }
        }
        assertEquals(105,tests.size());
        return tests;
    }
}
