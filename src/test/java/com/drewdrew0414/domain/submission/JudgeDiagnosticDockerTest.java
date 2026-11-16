package com.drewdrew0414.domain.submission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** 실제 컴파일러/런타임의 오류를 분류하되 비공개 입력·원문은 API 진단에 남기지 않습니다. */
@EnabledIfEnvironmentVariable(named="DOCKER_JUDGE_TEST",matches="true")
class JudgeDiagnosticDockerTest {
    private final DockerJudge judge=new DockerJudge(System.getenv().getOrDefault("GENERIC_JUDGE_IMAGE","cobweb-languages:local"),"/tmp/cobweb-safe-diagnostic-test");
    @Test void actualCCompilerFailureReportsCategoryAndOwnSourceLocation() {
        try(var prepared=judge.prepare(Language.C,"int main(void) {\n    return missing_variable;\n}\n","STDIN")) {
            assertEquals("COMPILATION_ERROR",prepared.failure());
            var diagnostic=prepared.publicDiagnostic();assertNotNull(diagnostic);
            assertEquals(JudgeDiagnostic.Kind.UNDEFINED_NAME,diagnostic.errorType());
            assertTrue(diagnostic.locations().stream().anyMatch(row->row.file().equals("main.c") && row.line()==2));
            assertFalse(diagnostic.toJson().contains("missing_variable"));
            assertFalse(diagnostic.toJson().contains("/source/"));
            assertEquals(diagnostic,prepared.run("private-input","private-answer",2000,256,1L).diagnostic());
        }
    }
    @Test void pythonValueErrorEchoingHiddenInputDoesNotLeakItsArgumentsOrSource() {
        String source="value = input()\nraise ValueError(value)\n";
        var result=judge.run(Language.PYTHON,source,"PRIVATE_DIAGNOSTIC_STDIN_9fb7\n","PRIVATE_EXPECTED", "STDIN",2000,256,2L);
        assertEquals("RUNTIME_ERROR",result.status());assertNotNull(result.diagnostic());
        assertEquals(JudgeDiagnostic.Kind.INVALID_VALUE,result.diagnostic().errorType());
        assertTrue(result.diagnostic().locations().stream().anyMatch(row->row.file().equals("main.py") && row.line()==2));
        for(String forbidden:new String[]{"PRIVATE_DIAGNOSTIC_STDIN_9fb7","PRIVATE_EXPECTED","raise ValueError","/source/","/opt/judge"})
            assertFalse(result.diagnostic().toJson().contains(forbidden),forbidden);
    }
    @Test void javascriptArbitraryErrorAndSqlSchemaErrorsHaveOnlySafeFixedReasons() {
        String javascript="const fs = require('fs');\nthrow new Error(fs.readFileSync(0, 'utf8'));\n";
        var js=judge.run(Language.JAVASCRIPT,javascript,"PRIVATE_JS_INPUT_ff91","private-expected","STDIN",2000,256,3L);
        assertEquals("RUNTIME_ERROR",js.status());assertNotNull(js.diagnostic());
        assertEquals(JudgeDiagnostic.Kind.RUNTIME_FAILED,js.diagnostic().errorType());
        assertFalse(js.diagnostic().toJson().contains("PRIVATE_JS_INPUT_ff91"));
        var sql=judge.run(Language.SQL,"SELECT private_unknown_column FROM data;","CREATE TABLE data(value TEXT);","private-expected","SQL",2000,256,4L);
        assertEquals("RUNTIME_ERROR",sql.status());assertNotNull(sql.diagnostic());
        assertEquals(JudgeDiagnostic.Kind.SQL_ERROR,sql.diagnostic().errorType());
        assertFalse(sql.diagnostic().toJson().contains("private_unknown_column"));
    }
}
