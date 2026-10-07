package com.drewdrew0414.domain.submission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 실제 ARM64/AMD64 채점 이미지에서 입력 변경·컴파일·읽기 전용 산출물 재사용까지 확인합니다. */
@EnabledIfEnvironmentVariable(named="DOCKER_JUDGE_TEST",matches="true")
class MultiLanguageJudgeTest {
    private final DockerJudge judge=new DockerJudge("cobweb-languages:local","/tmp/cobweb-multilang-test");
    private static final Map<Language,String> SOURCES=Map.ofEntries(
        Map.entry(Language.PYTHON,"a,b=map(int,input().split());print(a+b)"),
        Map.entry(Language.C,"#include <stdio.h>\nint main(void){int a,b;scanf(\"%d %d\",&a,&b);printf(\"%d\\n\",a+b);return 0;}"),
        Map.entry(Language.CPP,"#include <iostream>\nint main(){int a,b;std::cin>>a>>b;std::cout<<a+b<<'\\n';}"),
        Map.entry(Language.JAVA,"import java.util.*;public class Main{public static void main(String[]args){Scanner s=new Scanner(System.in);System.out.println(s.nextInt()+s.nextInt());}}"),
        Map.entry(Language.JAVASCRIPT,"const a=require('fs').readFileSync(0,'utf8').trim().split(/\\s+/).map(Number);console.log(a[0]+a[1]);"),
        Map.entry(Language.TYPESCRIPT,"import {readFileSync} from 'node:fs';const a:number[]=readFileSync(0,'utf8').trim().split(/\\s+/).map(Number);console.log(a[0]+a[1]);"),
        Map.entry(Language.CSHARP,"var a=Console.ReadLine()!.Split(' ',StringSplitOptions.RemoveEmptyEntries);Console.WriteLine(int.Parse(a[0])+int.Parse(a[1]));"),
        Map.entry(Language.GO,"package main\nimport (\"fmt\";\"os\")\nfunc main(){var a,b int;fmt.Fscan(os.Stdin,&a,&b);fmt.Println(a+b)}"),
        Map.entry(Language.RUST,"use std::io::{self,Read};fn main(){let mut s=String::new();io::stdin().read_to_string(&mut s).unwrap();let a:Vec<i32>=s.split_whitespace().map(|x|x.parse().unwrap()).collect();println!(\"{}\",a[0]+a[1]);}"),
        Map.entry(Language.KOTLIN,"fun main(){val a=readln().trim().split(Regex(\"\\\\s+\")).map{it.toInt()};println(a[0]+a[1])}"),
        Map.entry(Language.SQL,"SELECT SUM(v) FROM numbers;"),
        Map.entry(Language.RUBY,"a,b=STDIN.read.split.map(&:to_i);puts a+b"),
        Map.entry(Language.LLVM_IR,"""
            @fmtin = private constant [6 x i8] c"%d %d\\00"
            @fmtout = private constant [4 x i8] c"%d\\0A\\00"
            declare i32 @scanf(ptr, ...)
            declare i32 @printf(ptr, ...)
            define i32 @main() {
              %a = alloca i32
              %b = alloca i32
              call i32 (ptr, ...) @scanf(ptr @fmtin, ptr %a, ptr %b)
              %x = load i32, ptr %a
              %y = load i32, ptr %b
              %sum = add i32 %x, %y
              call i32 (ptr, ...) @printf(ptr @fmtout, i32 %sum)
              ret i32 0
            }
            """)
    );
    private static final Map<Language,String> FUNCTION_SOURCES=Map.ofEntries(
        Map.entry(Language.PYTHON,"def sol(matrix,target): return matrix[target[0]][target[1]] == 1"),
        Map.entry(Language.C,"#include <stdbool.h>\nbool sol(const int m[15][15],int r,int c){return m[r][c]==1;}"),
        Map.entry(Language.CPP,"#include <vector>\n#include <utility>\nbool sol(const std::vector<std::vector<int>>& m,std::pair<int,int> t){return m[t.first][t.second]==1;}"),
        Map.entry(Language.JAVA,"public class Main{public static boolean sol(int[][] m,int[] t){return m[t[0]][t[1]]==1;}}"),
        Map.entry(Language.JAVASCRIPT,"function sol(m,t){return m[t[0]][t[1]]===1;}"),
        Map.entry(Language.TYPESCRIPT,"export function sol(m:number[][],t:[number,number]):boolean{return m[t[0]][t[1]]===1;}"),
        Map.entry(Language.CSHARP,"public static class Solution {public static bool Sol(int[][] m,int[] t)=>m[t[0]][t[1]]==1;}"),
        Map.entry(Language.GO,"package main\nfunc sol(m [][]int,t [2]int) bool{return m[t[0]][t[1]]==1}"),
        Map.entry(Language.RUST,"fn sol(m: Vec<Vec<i32>>, t: (usize,usize)) -> bool { m[t.0][t.1] == 1 }"),
        Map.entry(Language.KOTLIN,"fun sol(m: Array<IntArray>, t: Pair<Int,Int>): Boolean = m[t.first][t.second] == 1"),
        Map.entry(Language.RUBY,"def sol(m,t)\n  m[t[0]][t[1]] == 1\nend"),
        Map.entry(Language.LLVM_IR,"""
            define i1 @sol(ptr %matrix, i32 %r, i32 %c) {
              %row = mul i32 %r, 15
              %index = add i32 %row, %c
              %cell = getelementptr i32, ptr %matrix, i32 %index
              %value = load i32, ptr %cell
              %result = icmp eq i32 %value, 1
              ret i1 %result
            }
            """)
    );
    @ParameterizedTest(name="{0}: compile once, run isolated cases")
    @EnumSource(Language.class)
    void everyLanguageRunsAndComparesRealInput(Language language) {
        try(var prepared=judge.prepare(language,SOURCES.get(language),language==Language.SQL?"SQL":"STDIN")) {
            assertNull(prepared.failure(),prepared.diagnostic());
            for(int a:new int[]{2,4}) {
                String input=language==Language.SQL?
                    "CREATE TABLE numbers(v INTEGER); INSERT INTO numbers VALUES("+a+"),(3);":a+" 3\n";
                assertEquals("ACCEPTED",prepared.run(input,""+(a+3),3000,256,1L).status(),prepared.diagnostic());
                assertEquals("WRONG_ANSWER",prepared.run(input,"wrong",3000,256,1L).status(),prepared.diagnostic());
            }
        }
    }
    @ParameterizedTest(name="{0}: FUNCTION adapter")
    @EnumSource(value=Language.class,names="SQL",mode=EnumSource.Mode.EXCLUDE)
    void everyNonSqlLanguageRunsFunctionCases(Language language) {
        try(var prepared=judge.prepare(language,FUNCTION_SOURCES.get(language),"FUNCTION")) {
            assertNull(prepared.failure(),prepared.diagnostic());
            int[][] matrix=new int[15][15];
            for(boolean value:new boolean[]{true,false}) {
                matrix[2][3]=value?1:0;
                String input=toFunctionInput(matrix,2,3);
                assertEquals("ACCEPTED",prepared.run(input,Boolean.toString(value),3000,256,1L).status(),prepared.diagnostic());
            }
        }
    }
    private String toFunctionInput(int[][] matrix,int row,int column) {
        try { return new tools.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
            "matrix",matrix,"target",new int[]{row,column})); }
        catch(Exception e) { throw new AssertionError(e); }
    }
    @ParameterizedTest
    @EnumSource(value=Language.class,names={"C","CPP","JAVA","TYPESCRIPT","CSHARP","GO","RUST","KOTLIN","LLVM_IR"})
    void invalidSourceIsCompilationError(Language language) {
        try(var prepared=judge.prepare(language,"this is not valid source @@@","STDIN")) {
            assertEquals("COMPILATION_ERROR",prepared.failure(),prepared.diagnostic());
        }
    }
    @Test void sqlCannotWriteOrAttachFilesOrRunShellCommands() {
        for(String code:new String[]{"ATTACH DATABASE '/tmp/test.db' AS other;","DELETE FROM numbers;",
                "SELECT load_extension('/tmp/plugin');",".shell echo escaped","SELECT 1; SELECT 2;"}) {
            assertEquals("RUNTIME_ERROR",judge.run(Language.SQL,code,"CREATE TABLE numbers(v INTEGER);","",
                "SQL",1000,128,1L).status(),code);
        }
    }
    @Test void compiledArtifactsCannotBeChangedByTestCases() {
        String code="#include <stdio.h>\nint main(){FILE *f=fopen(\"/work/changed\",\"w\");puts(f?\"unsafe\":\"readonly\");return 0;}";
        try(var prepared=judge.prepare(Language.C,code,"STDIN")) {
            assertNull(prepared.failure(),prepared.diagnostic());
            assertEquals("ACCEPTED",prepared.run("","readonly",1000,128,1L).status(),prepared.diagnostic());
        }
    }
    @Test void compiledInfiniteLoopIsKilled() {
        assertEquals("TIME_LIMIT_EXCEEDED",judge.run(Language.C,"int main(){for(;;){}}","","","STDIN",1000,128,1L).status());
    }
    @Test void compiledMemoryLimitIsEnforced() {
        String code="#include <stdlib.h>\nint main(){for(;;){volatile char *p=malloc(1024*1024);if(!p)return 1;for(int i=0;i<1024*1024;i+=4096)p[i]=1;}}";
        assertEquals("MEMORY_LIMIT_EXCEEDED",judge.run(Language.C,code,"","","STDIN",3000,64,1L).status());
    }
    @Test void sqliteExpensiveQueryIsKilled() {
        assertEquals("TIME_LIMIT_EXCEEDED",judge.run(Language.SQL,
            "WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n) SELECT SUM(x) FROM n;",
            "","","SQL",1000,128,1L).status());
    }
}
