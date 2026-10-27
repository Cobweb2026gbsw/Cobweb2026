// 서버가 제공하는 언어 목록이 실제 허용 범위입니다. 이 표는 파일명과 함수 안내에만 씁니다.
import {formatServerDate} from './date.js';
import {RequestError} from './errors.js';
import {genericFunctionStarter,validFunctionSignature} from './genericFunction.js';
export const FILE_NAMES={PYTHON:'main.py',C:'main.c',CPP:'main.cpp',JAVA:'Main.java',JAVASCRIPT:'main.js',TYPESCRIPT:'main.ts',CSHARP:'Main.cs',GO:'main.go',RUST:'main.rs',KOTLIN:'Main.kt',SQL:'query.sql',RUBY:'main.rb',LLVM_IR:'main.ll',PASCAL:'main.pas',PHP:'main.php',DART:'main.dart',UMJUNSIK:'main.umm'};
export const RANKS=['INITIATE','SKILLED','ELITE','EXPERT','LEGEND','MYTHIC','ABSOLUTE'];
export const STATUS={QUEUED:{label:'대기 중',tone:'gray'},RUNNING:{label:'채점 중',tone:'blue'},ACCEPTED:{label:'정답',tone:'green'},WRONG_ANSWER:{label:'오답',tone:'red'},TIME_LIMIT_EXCEEDED:{label:'시간 초과',tone:'yellow'},MEMORY_LIMIT_EXCEEDED:{label:'메모리 초과',tone:'yellow'},RUNTIME_ERROR:{label:'실행 오류',tone:'red'},COMPILATION_ERROR:{label:'컴파일 오류',tone:'red'},SYSTEM_ERROR:{label:'시스템 오류',tone:'red'}};
export const FUNCTION_SIGNATURES={PYTHON:'def sol(matrix, target) -> bool',C:'bool sol(const int matrix[15][15], int row, int col)',CPP:'bool sol(const std::vector<std::vector<int>>&, std::pair<int,int>)',JAVA:'Main 클래스의 public static boolean sol(int[][] matrix, int[] target)',JAVASCRIPT:'function sol(matrix, target) 또는 CommonJS export',TYPESCRIPT:'export function sol(matrix: number[][], target: [number,number]): boolean',CSHARP:'Solution 클래스의 public static bool Sol(int[][] matrix, int[] target)',GO:'package main의 func sol(matrix [][]int, target [2]int) bool',RUST:'fn sol(matrix: Vec<Vec<i32>>, target: (usize,usize)) -> bool',KOTLIN:'fun sol(matrix: Array<IntArray>, target: Pair<Int,Int>): Boolean',RUBY:'def sol(matrix, target)',LLVM_IR:'define i1 @sol(ptr %matrix, i32 %row, i32 %col) — 행 우선 i32[225]',PASCAL:'function sol(const matrix: TMatrix; const target: TTarget): Boolean; — TMatrix=array[0..14,0..14] of LongInt, TTarget=array[0..1] of LongInt는 제공됨',PHP:'<?php function sol(array $matrix, array $target): bool',DART:'bool sol(List<List<int>> matrix, List<int> target)'};
// 시작 코드는 공개 작성 자료입니다. 비공개 정답/테스트에서 서명을 추출하지 않습니다.
// FUNCTION 기본형은 현재 서버의 고정 matrix/target ABI와 일치해야 합니다.
const FUNCTION_STARTERS={
  PYTHON:'def sol(matrix: list[list[int]], target: tuple[int, int]) -> bool:\n    # 여기에 풀이를 작성하세요.\n    return False\n',
  C:'#include <stdbool.h>\n\nbool sol(const int matrix[15][15], int row, int col) {\n    // 여기에 풀이를 작성하세요.\n    return false;\n}\n',
  CPP:'#include <vector>\n#include <utility>\n\nbool sol(const std::vector<std::vector<int>>& matrix, std::pair<int, int> target) {\n    // 여기에 풀이를 작성하세요.\n    return false;\n}\n',
  JAVA:'public class Main {\n    public static boolean sol(int[][] matrix, int[] target) {\n        // 여기에 풀이를 작성하세요.\n        return false;\n    }\n}\n',
  JAVASCRIPT:'function sol(matrix, target) {\n    // 여기에 풀이를 작성하세요.\n    return false;\n}\n',
  TYPESCRIPT:'export function sol(matrix: number[][], target: [number, number]): boolean {\n    // 여기에 풀이를 작성하세요.\n    return false;\n}\n',
  CSHARP:'public class Solution {\n    public static bool Sol(int[][] matrix, int[] target) {\n        // 여기에 풀이를 작성하세요.\n        return false;\n    }\n}\n',
  GO:'package main\n\nfunc sol(matrix [][]int, target [2]int) bool {\n    // 여기에 풀이를 작성하세요.\n    return false\n}\n',
  RUST:'fn sol(matrix: Vec<Vec<i32>>, target: (usize, usize)) -> bool {\n    // 여기에 풀이를 작성하세요.\n    false\n}\n',
  KOTLIN:'fun sol(matrix: Array<IntArray>, target: Pair<Int, Int>): Boolean {\n    // 여기에 풀이를 작성하세요.\n    return false\n}\n',
  RUBY:'def sol(matrix, target)\n    # 여기에 풀이를 작성하세요.\n    false\nend\n',
  LLVM_IR:'define i1 @sol(ptr %matrix, i32 %row, i32 %col) {\nentry:\n    ; 여기에 풀이를 작성하세요.\n    ret i1 false\n}\n',
  PASCAL:'function sol(const matrix: TMatrix; const target: TTarget): Boolean;\nbegin\n    { 여기에 풀이를 작성하세요. }\n    Result := False;\nend;\n',
  PHP:'<?php\nfunction sol(array $matrix, array $target): bool {\n    // 여기에 풀이를 작성하세요.\n    return false;\n}\n',
  DART:'bool sol(List<List<int>> matrix, List<int> target) {\n    // 여기에 풀이를 작성하세요.\n    return false;\n}\n'
};
const STDIN_STARTERS={
  PYTHON:'import sys\n\n# 표준 입력을 읽고 풀이를 작성하세요.\n',
  C:'#include <stdio.h>\n\nint main(void) {\n    // 표준 입력과 출력을 사용하세요.\n    return 0;\n}\n',
  CPP:'#include <iostream>\n\nint main() {\n    std::ios::sync_with_stdio(false);\n    std::cin.tie(nullptr);\n    // 표준 입력과 출력을 사용하세요.\n    return 0;\n}\n',
  JAVA:'import java.io.*;\nimport java.util.*;\n\npublic class Main {\n    public static void main(String[] args) throws Exception {\n        // 표준 입력과 출력을 사용하세요.\n    }\n}\n',
  JAVASCRIPT:"const fs = require('node:fs');\nconst input = fs.readFileSync(0, 'utf8');\n\n// 여기에 풀이를 작성하세요.\n",
  TYPESCRIPT:"const fs = require('node:fs');\nconst input: string = fs.readFileSync(0, 'utf8');\n\n// 여기에 풀이를 작성하세요.\n",
  CSHARP:'using System;\n\npublic class MainClass {\n    public static void Main() {\n        // 표준 입력과 출력을 사용하세요.\n    }\n}\n',
  GO:'package main\n\nfunc main() {\n    // 표준 입력과 출력을 사용하세요.\n}\n',
  RUST:'fn main() {\n    // 표준 입력과 출력을 사용하세요.\n}\n',
  KOTLIN:'fun main() {\n    // 표준 입력과 출력을 사용하세요.\n}\n',
  RUBY:'input = STDIN.read\n\n# 여기에 풀이를 작성하세요.\n',
  LLVM_IR:'define i32 @main() {\nentry:\n    ; 표준 입력과 출력을 사용하세요.\n    ret i32 0\n}\n',
  PASCAL:'program Main;\n{$mode objfpc}\n\nbegin\n    { 표준 입력과 출력을 사용하세요. }\nend.\n',
  PHP:'<?php\n$input = stream_get_contents(STDIN);\n\n// 여기에 풀이를 작성하세요.\n',
  DART:"import 'dart:io';\n\nvoid main() {\n    // 표준 입력과 출력을 사용하세요.\n}\n",
  UMJUNSIK:'어떻게\n\n이 사람이름이냐ㅋㅋ\n'
};
export function starterCode(problem,language){
  const mode=problem?.execution_mode??problem?.executionMode;
  // 다른 문제의 초안/정답 코드나 언어 불일치 자료를 재사용하지 않습니다.
  const supported=mode==='FUNCTION'?Object.hasOwn(FUNCTION_STARTERS,language):mode==='STDIN'?Object.hasOwn(STDIN_STARTERS,language):mode==='SQL'&&language==='SQL';
  if(!supported)return '';
  const custom=Array.isArray(problem?.starterCodes)?problem.starterCodes.find(row=>row?.language===language):null;
  if(typeof custom?.sourceCode==='string'&&custom.sourceCode.trim()&&sourceBytes(custom.sourceCode)<=65536)return custom.sourceCode;
  if(mode==='FUNCTION'&&problem?.functionAbi==='GENERIC_V1')return genericFunctionStarter(problem.functionSignature,language);
  // 레거시 Python FUNCTION은 별도 입력 규격일 수 있습니다. 공개 ABI 표시가
  // 없는 문제에 matrix/target 서명을 추정해 넣지 않습니다.
  if(mode==='FUNCTION'&&problem?.functionAbi!=='CHAIN_LASER_V1')return '';
  return mode==='FUNCTION'?FUNCTION_STARTERS[language]:mode==='STDIN'?STDIN_STARTERS[language]:'-- SQLite 읽기 전용 쿼리를 작성하세요.\nSELECT 1;\n';
}
export const rankLabel=p=>`${p?.rank_type??p?.rankType??''} ${p?.rank_int??p?.rankInt??''}`.trim();
export const numberOf=p=>p?.problem_number??p?.number??p?.problemNumber;
export const titleOf=p=>p?.problem_title??p?.title??'';
export const labelStatus=s=>STATUS[s]??{label:s||'대기',tone:'gray'};
export const formatDate=formatServerDate;
export const sourceBytes=source=>new TextEncoder().encode(source||'').length;
export function languageHelp(language,mode,problem){
  if(mode==='FUNCTION'){
    if(problem?.functionAbi==='GENERIC_V1'&&validFunctionSignature(problem.functionSignature)){
      const signature=problem.functionSignature;
      return `${signature.name}(${signature.parameters.map(p=>`${p.name}: ${p.type}`).join(', ')}) → ${signature.returnType} · 함수 반환값을 채점합니다. main·입출력 처리는 작성하지 않습니다. 실수 허용 오차는 1e-9입니다.${language==='C'||language==='LLVM_IR'?' 문자열·배열은 포인터와 길이를 사용하며 반환 길이도 지정해야 합니다.':''}`;
    }
    const custom=Array.isArray(problem?.starterCodes)&&problem.starterCodes.some(row=>row?.language===language&&typeof row.sourceCode==='string'&&row.sourceCode.trim()&&sourceBytes(row.sourceCode)<=65536);
    if(custom)return '문제에 등록된 공개 시작 코드의 함수 선언·매개변수를 유지하세요. 불리언을 반환하고 main은 작성하지 않습니다. 시작 코드는 채점 입력 규격을 변경하지 않습니다.';
    if(problem&&problem.functionAbi!=='CHAIN_LASER_V1')return '문제의 함수 형식을 확인하세요. 공개 입력 규격이 확인되지 않아 매개변수를 자동 추정하지 않습니다. 불리언을 반환하고 main은 작성하지 않습니다.';
    return `${FUNCTION_SIGNATURES[language]||'지원하지 않는 함수형 언어'} · 불리언을 반환하세요. main은 작성하지 않습니다.`;
  }
  return mode==='SQL'?'SQLite 단일 읽기 쿼리. 각 케이스는 별도 메모리 DB이며 결과는 헤더 없는 TSV입니다.':`${FILE_NAMES[language]||language} · 표준 입력과 출력을 사용하세요. 외부 패키지 설치는 지원하지 않습니다.`;
}
export function emptyProblem(){return {title:'',description:'',inputDescription:'',outputDescription:'',constraints:'',rankType:'INITIATE',rankInt:5,timeLimitMs:1000,memoryLimitMb:256,executionMode:'STDIN',functionSignature:null,examples:[{input:'',output:'',explanation:''}],tests:[{input:'',expected:''}],languages:[],tags:[],sourceName:'',sourceUrl:'',referenceSolutions:[],starterCodes:[],groupId:null,visibility:'PUBLIC',authorPublic:false};}
export function problemPayload(form,catalog){
  const result={...form,title:form.title.trim(),rankInt:Number(form.rankInt),timeLimitMs:Number(form.timeLimitMs),memoryLimitMb:Number(form.memoryLimitMb),groupId:form.groupId?Number(form.groupId):null,sourceName:form.sourceName?.trim()||null,sourceUrl:form.sourceUrl?.trim()||null,authorPublic:form.authorPublic===true};
  if(!result.title||!result.description.trim())throw new RequestError('제목과 문제 본문을 입력하세요.');
  if(result.title.length>200)throw new RequestError('제목은 200자 이하여야 합니다.');
  if(result.visibility==='GROUP'&&!result.groupId)throw new RequestError('문제를 추가할 그룹을 선택하세요.');
  if(result.tags.length>10||result.tags.some(t=>t.length>50))throw new RequestError('태그는 최대 10개, 각각 50자 이하여야 합니다.');
  if(!result.languages.length||new Set(result.languages).size!==result.languages.length||result.languages.some(id=>!catalog.some(l=>l.id===id&&l.modes.includes(result.executionMode))))throw new RequestError('실행 형식에 맞는 허용 언어를 선택하세요.');
  if(result.examples.length>20||result.tests.length>50)throw new RequestError('예제는 최대 20개, 테스트는 최대 50개입니다.');
  if(result.referenceSolutions.length>catalog.length)throw new RequestError('정답 코드는 언어당 하나만 첨부하세요.');
  const used=new Set();for(const r of result.referenceSolutions){
    if(used.has(r.language)||!result.languages.includes(r.language))throw new RequestError('정답 코드는 허용 언어별 하나만 등록할 수 있습니다.');used.add(r.language);
    if(!r.sourceCode.trim()||sourceBytes(r.sourceCode)>65536)throw new RequestError('정답 코드는 비어 있지 않은 64 KiB 이하 텍스트여야 합니다.');
    if(!r.fileName||r.fileName.length>120||/[\\/\u0000-\u001f]/.test(r.fileName))throw new RequestError('파일명은 경로 없는 120자 이하여야 합니다.');
  }
  result.starterCodes=form.starterCodes??[];
  if(!Array.isArray(result.starterCodes)||result.starterCodes.length>catalog.length)throw new RequestError('시작 코드는 언어당 하나만 등록하세요.');
  const starterLanguages=new Set();
  result.starterCodes=result.starterCodes.map(row=>{
    if(!row||starterLanguages.has(row.language)||!result.languages.includes(row.language))throw new RequestError('시작 코드는 허용 언어별 하나만 등록할 수 있습니다.');
    starterLanguages.add(row.language);
    if(typeof row.sourceCode!=='string'||!row.sourceCode.trim()||sourceBytes(row.sourceCode)>65536)throw new RequestError('시작 코드는 비어 있지 않은 64 KiB 이하 텍스트여야 합니다.');
    return {language:row.language,sourceCode:row.sourceCode};
  });
  result.visibility=result.groupId?'GROUP':result.visibility;
  if(result.executionMode!=='FUNCTION')result.functionSignature=null;
  else if(result.functionSignature!=null&&!validFunctionSignature(result.functionSignature))throw new RequestError('함수 서명의 이름·매개변수·반환형을 확인하세요.');
  for(const key of ['number','status','currentVisibility','canEdit','usedInContest','groupName','functionAbi','authorUsername','authorId','author_id','author_username','author_public'])delete result[key];
  const requestLimitMb=result.executionMode==='FUNCTION'&&result.functionSignature?40:2;
  if(sourceBytes(JSON.stringify(result))>requestLimitMb*1024*1024)throw new RequestError(`문제 전체 입력은 UTF-8 ${requestLimitMb} MiB 이하여야 합니다.`);
  return result;
}
export async function readSourceFile(file){
  if(!file||file.size>65536||!file.name||file.name.length>120||/[\\/\u0000-\u001f]/.test(file.name))throw new RequestError('64 KiB 이하의 경로 없는 UTF-8 파일을 첨부하세요.');
  let sourceCode;
  try{sourceCode=new TextDecoder('utf-8',{fatal:true}).decode(await file.arrayBuffer());}
  catch{throw new RequestError('첨부 파일을 UTF-8 텍스트로 읽을 수 없습니다.');}
  if(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/.test(sourceCode))throw new RequestError('바이너리 파일은 첨부할 수 없습니다.');
  return {fileName:file.name,sourceCode};
}
