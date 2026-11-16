package com.drewdrew0414.domain.submission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Real compiler/runtime coverage; no submitted solution is ever executed on the host. */
@EnabledIfEnvironmentVariable(named="DOCKER_JUDGE_TEST",matches="true")
class GenericFunctionJudgeTest {
    private static Map<?,?> actualStarters;
    private final DockerJudge judge=new DockerJudge(
        System.getenv().getOrDefault("GENERIC_JUDGE_IMAGE","cobweb-languages:local"),"/tmp/cobweb-generic-test");
    private static final String[] TYPES={"int","float","bool","string","int[]","string[]"};
    private static final Map<String,String> VALUES=Map.of(
        "int","-2147483648","float","-1.23456789012345e37","bool","true",
        "string","\"a b\\\"\\n\\t\\u0000z\"","int[]","[-2147483648,0,2147483647]",
        "string[]","[\"\",\"a b\",\"quote\\\"\\n\\u0000\"]");

    static Stream<Arguments> identityCases(){
        return Arrays.stream(Language.values()).filter(l->l.supports("FUNCTION"))
            .flatMap(l->Arrays.stream(TYPES).map(t->Arguments.of(l,t)));
    }
    @BeforeAll static void loadActualFrontendStarters() throws Exception {
        // Repository frontend generator only; submitted/reference solution code is not executed here.
        var process=new ProcessBuilder("node","--input-type=module","-e","""
            import {starterCode} from './frontend/src/lib/problemModel.js';
            const languages=['PYTHON','C','CPP','JAVA','JAVASCRIPT','TYPESCRIPT','CSHARP','GO','RUST','KOTLIN','RUBY','LLVM_IR','PASCAL','PHP','DART'];
            const types=['int','float','bool','string','int[]','string[]'];
            const result={};
            for(const language of languages)for(const [index,type]of types.entries()){
              const functionSignature={name:index%2?'solution':'sol',parameters:[{name:'a',type}],returnType:type};
              result[language+':'+type]=starterCode({execution_mode:'FUNCTION',functionAbi:'GENERIC_V1',functionSignature},language);
            }
            console.log(JSON.stringify(result));
            """).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            var output=CompletableFuture.supplyAsync(()->{
                try{return process.getInputStream().readNBytes(1024*1024+1);}
                catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
            });
            assertTrue(process.waitFor(10,TimeUnit.SECONDS),"Frontend starter loading timed out");
            assertEquals(0,process.exitValue());
            byte[] bytes=output.get(2,TimeUnit.SECONDS);
            assertTrue(bytes.length<=1024*1024);
            actualStarters=new tools.jackson.databind.ObjectMapper().readValue(new String(bytes,StandardCharsets.UTF_8),Map.class);
            assertEquals(90,actualStarters.size());
        } finally {if(process.isAlive())process.destroyForcibly();}
    }
    private static String signature(String name,String kind,boolean noArgs){
        return "{\"name\":\""+name+"\",\"parameters\":"+(noArgs?"[]":"[{\"name\":\"a\",\"type\":\""+kind+"\"}]")+
            ",\"returnType\":\""+kind+"\"}";
    }
    private static String identity(Language language,String kind,String name){
        String cType=switch(kind){case "float"->"double";case "bool"->"bool";case "string"->"char*";case "int[]"->"int*";case "string[]"->"CWString*";default->"int";};
        String javaType=switch(kind){case "float"->"double";case "bool"->"boolean";case "string"->"String";case "string[]"->"String[]";default->kind;};
        return switch(language){
            case PYTHON->"def "+name+"(a): return a";
            case JAVASCRIPT->"function "+name+"(a){return a;}";
            case TYPESCRIPT->{String type=switch(kind){case "int","float"->"number";case "bool"->"boolean";case "int[]"->"number[]";default->kind;};yield "export function "+name+"(a:"+type+"):"+type+"{return a;}";}
            case C->"#include <stdbool.h>\n"+cType+" "+name+"("+cType+" a"+(kind.contains("[]")||kind.equals("string")?",int n,int *result_length){*result_length=n;return a;}":"){return a;}");
            case CPP->{String type=switch(kind){case "float"->"double";case "string"->"std::string";case "int[]"->"std::vector<int>";case "string[]"->"std::vector<std::string>";default->kind;};yield type+" "+name+"("+type+" a){return a;}";}
            case JAVA->"public class Main{public static "+javaType+" "+name+"("+javaType+" a){return a;}}";
            case CSHARP->{String type=kind.equals("float")?"double":kind;yield "public static class Solution{public static "+type+" "+name+"("+type+" a)=>a;}";}
            case GO->{String type=switch(kind){case "float"->"float64";case "int[]"->"[]int";case "string[]"->"[]string";default->kind;};yield "package main\nfunc "+name+"(a "+type+") "+type+" {return a}";}
            case RUST->{String type=switch(kind){case "int"->"i32";case "float"->"f64";case "string"->"String";case "int[]"->"Vec<i32>";case "string[]"->"Vec<String>";default->kind;};yield "fn "+name+"(a:"+type+")->"+type+"{a}";}
            case KOTLIN->{String type=switch(kind){case "int"->"Int";case "float"->"Double";case "bool"->"Boolean";case "string"->"String";case "int[]"->"IntArray";default->"Array<String>";};yield "fun "+name+"(a:"+type+"):"+type+" = a";}
            case RUBY->"def "+name+"(a)\n a\nend";
            case PHP->"<?php function "+name+"($a){return $a;}";
            case DART->{String type=switch(kind){case "float"->"double";case "string"->"String";case "int[]"->"List<int>";case "string[]"->"List<String>";default->kind;};yield type+" "+name+"("+type+" a)=>a;";}
            case PASCAL->{String type=switch(kind){case "int"->"LongInt";case "float"->"Double";case "bool"->"Boolean";case "string"->"AnsiString";case "int[]"->"TIntArray";default->"TStringArray";};yield "function "+name+"(a:"+type+"):"+type+";begin Result:=a;end;";}
            case LLVM_IR->{String type=switch(kind){case "int"->"i32";case "float"->"double";case "bool"->"i1";default->"ptr";};yield type.equals("ptr")?
                "define ptr @"+name+"(ptr %a,i32 %n,ptr %result_length){store i32 %n,ptr %result_length\nret ptr %a}":
                "define "+type+" @"+name+"("+type+" %a){ret "+type+" %a}";}
            default->throw new IllegalArgumentException("Not a FUNCTION language");
        };
    }

    @ParameterizedTest(name="{0}: generic {1} identity and isolated reuse",quoteTextArguments=false)
    @MethodSource("identityCases")
    void allTypesAcrossFifteenLanguages(Language language,String kind){
        try(var prepared=judge.prepare(language,identity(language,kind,"sol"),"FUNCTION",signature("sol",kind,false))){
            assertNull(prepared.failure(),()->language+" "+kind+": "+prepared.diagnostic());
            String value=VALUES.get(kind);
            assertEquals("ACCEPTED",prepared.run("{\"args\":["+value+"]}",value,5000,256,1L).status(),prepared::diagnostic);
            String empty=switch(kind){case "string"->"\"\"";case "int[]","string[]"->"[]";case "bool"->"false";case "float"->"0.125";default->"2147483647";};
            assertEquals("ACCEPTED",prepared.run("{\"args\":["+empty+"]}",empty,5000,256,2L).status(),prepared::diagnostic);
        }
    }

    @ParameterizedTest(name="{0}: actual frontend generic {1} starter",quoteTextArguments=false)
    @MethodSource("identityCases")
    void actualFrontendStartersMatchAllSixTypedAbis(Language language,String kind){
        String source=(String)actualStarters.get(language.name()+":"+kind);
        assertNotNull(source);assertFalse(source.isBlank());
        String name=Arrays.asList(TYPES).indexOf(kind)%2==1?"solution":"sol";
        String expected=switch(kind){case "float"->"0.0";case "bool"->"false";case "string"->"\"\"";case "int[]","string[]"->"[]";default->"0";};
        try(var prepared=judge.prepare(language,source,"FUNCTION",signature(name,kind,false))){
            assertNull(prepared.failure(),()->language+" "+kind+": "+prepared.diagnostic());
            assertEquals("ACCEPTED",prepared.run("{\"args\":["+VALUES.get(kind)+"]}",expected,5000,256,1L).status(),prepared::diagnostic);
        }
    }

    @ParameterizedTest(name="{0}: solution name and empty parameters")
    @EnumSource(value=Language.class,names={"SQL","UMJUNSIK"},mode=EnumSource.Mode.EXCLUDE)
    void solutionAndNoArguments(Language language){
        String code=switch(language){
            case PYTHON->"def solution(): return 42";
            case C,CPP->"int solution(void){return 42;}";
            case JAVA->"public class Main{public static int solution(){return 42;}}";
            case JAVASCRIPT->"function solution(){return 42;}";
            case TYPESCRIPT->"export function solution():number{return 42;}";
            case CSHARP->"public static class Solution{public static int solution()=>42;}";
            case GO->"package main\nfunc solution()int{return 42}";
            case RUST->"fn solution()->i32{42}";
            case KOTLIN->"fun solution():Int=42";
            case RUBY->"def solution;42;end";
            case PASCAL->"function solution:LongInt;begin Result:=42;end;";
            case PHP->"<?php function solution(){return 42;}";
            case DART->"int solution()=>42;";
            case LLVM_IR->"define i32 @solution(){ret i32 42}";
            default->throw new IllegalArgumentException();
        };
        try(var prepared=judge.prepare(language,code,"FUNCTION",signature("solution","int",true))){
            assertNull(prepared.failure(),prepared::diagnostic);
            assertEquals("ACCEPTED",prepared.run("{\"args\":[]}","42",5000,256,1L).status(),prepared::diagnostic);
        }
    }

    @Test void rejectsMalformedArgumentsAndWrongDynamicReturnTypes(){
        try(var prepared=judge.prepare(Language.PYTHON,"def sol(a): return a","FUNCTION",signature("sol","int",false))){
            for(String input:new String[]{"{}","{\"args\":[]}","{\"args\":[true]}","{\"args\":[2147483648]}","{\"args\":[1],\"extra\":1}"})
                assertEquals("RUNTIME_ERROR",prepared.run(input,"1",1000,128,1L).status(),input);
        }
        try(var prepared=judge.prepare(Language.PYTHON,"def sol(a): return True","FUNCTION",signature("sol","int",false))){
            assertEquals("RUNTIME_ERROR",prepared.run("{\"args\":[1]}","1",1000,128,1L).status());
        }
    }

    @Test void fourArgumentsHaveDistinctNativeTypes(){
        String spec="{\"name\":\"sol\",\"parameters\":[{\"name\":\"a\",\"type\":\"int\"},{\"name\":\"b\",\"type\":\"float\"},{\"name\":\"c\",\"type\":\"bool\"},{\"name\":\"d\",\"type\":\"string\"}],\"returnType\":\"string\"}";
        try(var prepared=judge.prepare(Language.PYTHON,"def sol(a,b,c,d):\n assert type(a) is int and type(b) is float and type(c) is bool and type(d) is str\n return d+str(a)+str(c)","FUNCTION",spec)){
            assertEquals("ACCEPTED",prepared.run("{\"args\":[-2,0.25,true,\"x \"]}","\"x -2True\"",1000,128,1L).status(),prepared::diagnostic);
        }
    }
}
