package com.drewdrew0414.domain.submission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** 실제 Docker가 있는 CI/개발 환경에서만 실행합니다. 가짜 실행기로 격리 검증을 대체하지 않습니다. */
@EnabledIfEnvironmentVariable(named="DOCKER_JUDGE_TEST",matches="true")
class DockerJudgeTest {
    private final DockerJudge judge=new DockerJudge("cobweb-python:local","/tmp/cobweb-judge-test");
    @Test void functionReturnAndWrongAnswer(){
        assertEquals("ACCEPTED",judge.run("def sol(matrix,target): return target == (7,7)",
            "{\"matrix\":[],\"target\":[7,7]}","true","FUNCTION",1000,128,1L).status());
        assertEquals("WRONG_ANSWER",judge.run("def sol(matrix,target): return False",
            "{\"matrix\":[],\"target\":[7,7]}","true","FUNCTION",1000,128,1L).status());
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
