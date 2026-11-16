package com.drewdrew0414.domain.submission;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** 실제 화면의 시작 코드를 실제 격리 컴파일러로 실행합니다. 제출 코드는 호스트에서 실행하지 않습니다. */
@EnabledIfEnvironmentVariable(named="DOCKER_JUDGE_TEST",matches="true")
class GenericNestedFunctionJudgeTest {
    private static Map<?,?> starters;
    private final DockerJudge judge=new DockerJudge(System.getenv().getOrDefault("GENERIC_JUDGE_IMAGE","cobweb-languages:local"),"/tmp/cobweb-nested-test");
    static Stream<Arguments> cases(){return Arrays.stream(Language.values()).filter(l->l.supports("FUNCTION"))
        .flatMap(l->Stream.of("int[][]","string[][]").map(t->Arguments.of(l,t)));}
    @BeforeAll static void loadStarters() throws Exception {
        var child=new ProcessBuilder("node","--input-type=module","-e","""
            import {genericFunctionStarter} from './frontend/src/lib/genericFunction.js';
            const langs=['PYTHON','C','CPP','JAVA','JAVASCRIPT','TYPESCRIPT','CSHARP','GO','RUST','KOTLIN','RUBY','LLVM_IR','PASCAL','PHP','DART'];
            const result={};for(const language of langs){for(const type of ['int[][]','string[][]'])
              result[language+':'+type]=genericFunctionStarter({name:'sol',parameters:[{name:'value',type}],returnType:type},language);
              result[language+':mixed']=genericFunctionStarter({name:'sol',parameters:['int','float','bool','string','int[][]','string[][]'].map((type,i)=>({name:String.fromCharCode(97+i),type})),returnType:'string[][]'},language);}
            console.log(JSON.stringify(result));
            """).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            var output=java.util.concurrent.CompletableFuture.supplyAsync(()->{
                try{return child.getInputStream().readNBytes(524289);}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
            });
            assertTrue(child.waitFor(10,TimeUnit.SECONDS));assertEquals(0,child.exitValue());
            byte[] bytes=output.get(2,TimeUnit.SECONDS);assertTrue(bytes.length<=524288);
            starters=new tools.jackson.databind.ObjectMapper().readValue(new String(bytes,StandardCharsets.UTF_8),Map.class);
            assertEquals(45,starters.size());
        }finally{if(child.isAlive())child.destroyForcibly();}
    }
    private static String signature(String type){return "{\"name\":\"sol\",\"parameters\":[{\"name\":\"value\",\"type\":\""+type+"\"}],\"returnType\":\""+type+"\"}";}
    private static String returnArgument(String source,Language language,String name){
        return switch(language){
            case C->source.replace("*result_length = 0;","*result_length = "+name+"_length;").replace("return NULL;","return "+name+";");
            case LLVM_IR->source.replace("store i32 0, ptr %result_length","store i32 %"+name+"_length, ptr %result_length").replace("ret ptr null","ret ptr %"+name);
            case PASCAL->source.replace("SetLength(Result, 0);","Result := "+name+";");
            case RUST->source.replace("vec![]",name);
            case RUBY->source.replace("    []\nend","    "+name+"\nend");
            case PHP->source.replace("return [];","return $"+name+";");
            default->source.replaceFirst("(?m)^([ \\t]*)return [^\\n]+", "$1return "+name+";");
        };
    }
    @ParameterizedTest(name="{0}: actual nested {1} starter",quoteTextArguments=false)
    @MethodSource("cases") void actualFrontendNestedStartersCompileAndReturnTheirDefault(Language language,String type){
        String source=(String)starters.get(language+":"+type);assertNotNull(source);assertFalse(source.contains("undefined"));
        try(var prepared=judge.prepare(language,source,"FUNCTION",signature(type))){
            assertNull(prepared.failure(),prepared::diagnostic);
            String value=type.equals("int[][]")?"[[1,-2],[],[2147483647]]":"[[\"a b\\u0000\"],[],[\"\",\" z\"]]";
            assertEquals("ACCEPTED",prepared.run("{\"args\":["+value+"]}","[]",10000,512,1L).status(),prepared::diagnostic);
            assertEquals("ACCEPTED",prepared.run("{\"args\":[[]]}","[]",10000,512,2L).status(),prepared::diagnostic);
        }
        try(var prepared=judge.prepare(language,returnArgument(source,language,"value"),"FUNCTION",signature(type))){
            assertNull(prepared.failure(),prepared::diagnostic);
            String value=type.equals("int[][]")?"[[1,-2],[],[2147483647]]":"[[\"a b\\u0000\"],[],[\"\",\" z\"]]";
            assertEquals("ACCEPTED",prepared.run("{\"args\":["+value+"]}",value,10000,512,3L).status(),prepared::diagnostic);
        }
    }
    @ParameterizedTest(name="{0}: six mixed native arguments")
    @EnumSource(value=Language.class,names={"SQL","UMJUNSIK"},mode=EnumSource.Mode.EXCLUDE)
    void sixArgumentsRetainTheirNativeTypesAndOrder(Language language){
        var parameters=new java.util.ArrayList<Map<String,String>>();
        String[] types={"int","float","bool","string","int[][]","string[][]"};
        for(int i=0;i<types.length;i++)parameters.add(Map.of("name",Character.toString('a'+i),"type",types[i]));
        String spec=new tools.jackson.databind.ObjectMapper().writeValueAsString(Map.of("name","sol","parameters",parameters,"returnType","string[][]"));
        String source=returnArgument((String)starters.get(language+":mixed"),language,"f");
        try(var prepared=judge.prepare(language,source,"FUNCTION",spec)){
            assertNull(prepared.failure(),prepared::diagnostic);
            assertEquals("ACCEPTED",prepared.run("{\"args\":[7,2.5,true,\"sp ace\",[[4],[]],[[\"x\"],[],[\"y\"]]]}","[[\"x\"],[],[\"y\"]]",10000,512,1L).status(),prepared::diagnostic);
        }
    }
    @Test void millionIntegerInputAndLargeJsonResultPassThroughActualJavaDockerComparison(){
        String value="["+"0,".repeat(999999)+"0]";
        try(var prepared=judge.prepare(Language.PYTHON,"def sol(value): return value","FUNCTION",signature("int[]"))){
            assertEquals("ACCEPTED",prepared.run("{\"args\":["+value+"]}",value,10000,512,1L).status(),prepared::diagnostic);
        }
    }
    @Test void manualTestOutputStaysBoundedEvenThoughFormalGenericOutputIsLarger(){
        String value="["+"0,".repeat(39999)+"0]";
        try(var prepared=judge.prepare(Language.PYTHON,"def sol(value): return value","FUNCTION",signature("int[]"))){
            var output=prepared.runForTesting("{\"args\":["+value+"]}",value,10000,512);
            assertEquals("RUNTIME_ERROR",output.result().status());
            assertEquals(JudgeDiagnostic.Kind.OUTPUT_LIMIT,output.result().diagnostic().errorType());
            assertTrue(output.actual().getBytes(StandardCharsets.UTF_8).length<=65536);
        }
    }
    @Test void nestedComparisonPreservesRowBoundariesEmptyRowsAndOrder(){
        assertTrue(DockerJudge.matchesGenericResult("[[1],[],[2,3]]","[[1],[],[2,3]]"));
        assertFalse(DockerJudge.matchesGenericResult("[[1,2],[],[3]]","[[1],[],[2,3]]"));
        assertFalse(DockerJudge.matchesGenericResult("[[1],[2,3]]","[[1],[],[2,3]]"));
        assertFalse(DockerJudge.matchesGenericResult("[[\"x\"]] trailing","[[\"x\"]]"));
    }
}
