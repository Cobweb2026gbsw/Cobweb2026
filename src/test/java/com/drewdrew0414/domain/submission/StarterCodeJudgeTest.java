package com.drewdrew0414.domain.submission;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

/** 화면에 실제로 표시하는 시작 코드가 채점 이미지의 진입점/자료형과 일치하는지 검사합니다. */
@EnabledIfEnvironmentVariable(named="DOCKER_JUDGE_TEST",matches="true")
class StarterCodeJudgeTest {
    private static Map<?,?> templates;
    private final DockerJudge judge=new DockerJudge("cobweb-languages:local","/tmp/cobweb-starter-test");

    @BeforeAll static void readActualFrontendTemplates() throws Exception {
        // 실행할 JS와 경로는 테스트 상수입니다. 사용자 코드/비밀 설정을 실행하거나 읽지 않습니다.
        var node=new ProcessBuilder("node","--input-type=module","-e","""
            import {starterCode} from './frontend/src/lib/problemModel.js';
            const languages=['PYTHON','C','CPP','JAVA','JAVASCRIPT','TYPESCRIPT','CSHARP',
              'GO','RUST','KOTLIN','SQL','RUBY','LLVM_IR','PASCAL','PHP','DART','UMJUNSIK'];
            console.log(JSON.stringify(Object.fromEntries(languages.map(language=>[language,{
              standard:starterCode({execution_mode:language==='SQL'?'SQL':'STDIN'},language),
              function:starterCode({execution_mode:'FUNCTION',functionAbi:'CHAIN_LASER_V1'},language)
            }]))));
            """).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            assertTrue(node.waitFor(10,TimeUnit.SECONDS),"시작 코드 로딩 시간 초과");
            assertEquals(0,node.exitValue(),"실제 프런트 시작 코드 로딩 실패");
            templates=new tools.jackson.databind.ObjectMapper().readValue(
                new String(node.getInputStream().readAllBytes(),StandardCharsets.UTF_8),Map.class);
            assertEquals(Language.CATALOG_SIZE,templates.size());
        } finally {if(node.isAlive())node.destroyForcibly();}
    }

    @ParameterizedTest(name="{0}: actual public standard starter")
    @EnumSource(Language.class)
    void standardStarterIsValid(Language language) {
        String source=(String)((Map<?,?>)templates.get(language.name())).get("standard");
        assertFalse(source.isBlank());
        String mode=language==Language.SQL?"SQL":"STDIN";
        try(var prepared=judge.prepare(language,source,mode)) {
            assertNull(prepared.failure(),prepared.diagnostic());
            assertEquals("ACCEPTED",prepared.run("",language==Language.SQL?"1":"",3000,256,1L).status(),prepared.diagnostic());
        }
    }

    @ParameterizedTest(name="{0}: actual public FUNCTION starter")
    @EnumSource(value=Language.class,names={"SQL","UMJUNSIK"},mode=EnumSource.Mode.EXCLUDE)
    void functionStarterMatchesJudgeAbi(Language language) throws Exception {
        String source=(String)((Map<?,?>)templates.get(language.name())).get("function");
        assertFalse(source.isBlank());
        String input=new tools.jackson.databind.ObjectMapper().writeValueAsString(
            Map.of("matrix",new int[15][15],"target",new int[]{0,0}));
        try(var prepared=judge.prepare(language,source,"FUNCTION")) {
            assertNull(prepared.failure(),prepared.diagnostic());
            // 빈 골격의 false는 정답 구현이 아닙니다. 여기서는 함수 ABI만 확인합니다.
            assertEquals("ACCEPTED",prepared.run(input,"false",3000,256,1L).status(),prepared.diagnostic());
        }
    }
}
