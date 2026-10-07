package com.drewdrew0414.domain.submission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

/** 실제 Docker가 있는 CI/개발 환경에서만 실행합니다. 가짜 실행기로 격리 검증을 대체하지 않습니다. */
@EnabledIfEnvironmentVariable(named="DOCKER_JUDGE_TEST",matches="true")
class DockerJudgeTest {
    private final DockerJudge judge=new DockerJudge("cobweb-languages:local","/tmp/cobweb-judge-test");
    private static String functionInput() {
        String row="["+String.join(",",Collections.nCopies(15,"0"))+"]";
        return "{\"matrix\":["+String.join(",",Collections.nCopies(15,row))+"],\"target\":[7,7]}";
    }
    @Test void functionReturnAndWrongAnswer(){
        assertEquals("ACCEPTED",judge.run("def sol(matrix,target): return target == (7,7)",
            functionInput(),"true","FUNCTION",1000,128,1L).status());
        assertEquals("WRONG_ANSWER",judge.run("def sol(matrix,target): return False",
            functionInput(),"true","FUNCTION",1000,128,1L).status());
    }
    @Test void legacyPythonFunctionShapeRemainsAvailableButCompiledAdapterRejectsIt(){
        String invalid="{\"matrix\":[],\"target\":[7,7]}";
        assertEquals("ACCEPTED",judge.run(Language.PYTHON,"def sol(matrix,target): return True",
            invalid,"true","FUNCTION",1000,128,1L).status());
        assertEquals("RUNTIME_ERROR",judge.run(Language.C,
            "#include <stdbool.h>\nbool sol(const int m[15][15],int r,int c){return true;}",
            invalid,"true","FUNCTION",1000,128,1L).status());
    }
    @Test void infiniteLoopIsKilled(){
        var status=judge.run("while True: pass","","","STDIN",1000,128,1L).status();
        assertEquals("TIME_LIMIT_EXCEEDED",status);
    }
    @Test void filesystemIsReadOnly(){
        assertEquals("RUNTIME_ERROR",judge.run("open('/escape','w').write('x')","","","STDIN",1000,128,1L).status());
    }
    @Test void ignoredLargeInputCannotBlockWorker(){
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(15), () ->
            assertEquals("TIME_LIMIT_EXCEEDED",judge.run("import time; time.sleep(60)",
                "x".repeat(1000000),"","STDIN",1000,128,1L).status()));
    }
    @Test void sleepingSubmissionExceedingWallClockLimitTimesOut(){
        assertEquals("TIME_LIMIT_EXCEEDED",judge.run("import time; time.sleep(2); print('ok')",
            "","ok","STDIN",300,128,1L).status());
        assertEquals("TIME_LIMIT_EXCEEDED",judge.run("import time\ndef sol(matrix,target):\n time.sleep(2)\n return True",
            functionInput(),"true","FUNCTION",300,128,1L).status());
    }
    @Test void outputDoesNotGrowWithoutBound(){
        assertNotEquals("ACCEPTED",judge.run("print('x'*1000000)","","","STDIN",1000,128,1L).status());
    }
    @Test void networkIsUnavailable(){
        assertEquals("RUNTIME_ERROR",judge.run("import socket; socket.create_connection(('1.1.1.1',443),timeout=1)",
            "","","STDIN",2000,128,1L).status());
    }
    @Test void outputLimitMarkerCannotBeAcceptedAsAnAnswer(){
        assertNotEquals("ACCEPTED",judge.run("import os\ntry: os.write(1,b'x'*1000000)\nexcept OSError: pass\nos._exit(0)","",
            "[OUTPUT LIMIT EXCEEDED]","STDIN",1000,128,1L).status());
    }
    @Test void excessiveStderrCannotBeAccepted(){
        assertNotEquals("ACCEPTED",judge.run("import os\ntry: os.write(2,b'x'*1000000)\nexcept OSError: pass\nos.write(1,b'ok')\nos._exit(0)","",
            "ok","STDIN",1000,128,1L).status());
    }
}
