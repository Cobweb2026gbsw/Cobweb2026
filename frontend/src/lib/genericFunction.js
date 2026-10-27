// 공개된 GENERIC_V1 서명만 사용합니다. 테스트·참고 정답에서 매개변수를 추측하지 않습니다.
export const GENERIC_TYPES=['int','float','bool','string','int[]','string[]','int[][]','string[][]'];
const RESERVED=new Set(('abstract and array as assert async await base begin bool boolean break byte case catch char class const continue crate data debugger decimal def default defer del delete do double dynamic elif else elseif end enum eval except export extends external false final finally float fn for foreach from fun func function global go goto if implements import in inline instanceof int interface internal is lambda let long loop main match mod module mut namespace native new nil none not null object of operator or out override package params pass private protected public raise readonly record ref repeat require return self short signed sizeof sol solution static string struct super switch then this throw throws trait true try type typeof uint ulong union unsigned until use using val var virtual void volatile when while with yield').split(' '));
const PORTABLE_RESERVED=new Set(('result synchronized transient strictfp box dyn impl macro pub where move unsafe select chan map fallthrough range checked unchecked fixed event implicit explicit lock stackalloc ushort extern div shl shr xor unit program implementation constructor destructor procedure packed resourcestring threadvar absolute assembler forward inherited interrupt otherwise overload pascal register safecall stdcall specialize published automated property typealias vararg companion crossinline infix inner noinline open reified sealed suspend tailrec delegate sbyte nonlocal alias rescue retry redo undef unless alignas alignof and_eq asm auto bitand bitor char8_t char16_t char32_t compl concept consteval constexpr constinit const_cast co_await co_return co_yield decltype dynamic_cast friend mutable noexcept not_eq nullptr or_eq reinterpret_cast requires static_assert static_cast template thread_local typename wchar_t xor_eq typedef restrict callable clone declare die echo empty enddeclare endfor endforeach endif endswitch endwhile exit include include_once insteadof isset list print require_once unlet unset covariant extension factory get hide late mixin on part set show sync').split(' '));
export function validFunctionSignature(signature){
  return Boolean(signature&&['sol','solution'].includes(signature.name)&&GENERIC_TYPES.includes(signature.returnType)&&
    Array.isArray(signature.parameters)&&signature.parameters.length<=6&&
    signature.parameters.every(p=>p&&/^[a-z][A-Za-z0-9_]{0,31}$/.test(p.name)&&!/_length$/i.test(p.name)&&!RESERVED.has(p.name.toLowerCase())&&!PORTABLE_RESERVED.has(p.name.toLowerCase())&&GENERIC_TYPES.includes(p.type))&&
    new Set(signature.parameters.map(p=>p.name.toLowerCase())).size===signature.parameters.length);
}
const types={
  PYTHON:['int','float','bool','str','list[int]','list[str]','list[list[int]]','list[list[str]]'],
  CPP:['int','double','bool','std::string','std::vector<int>','std::vector<std::string>','std::vector<std::vector<int>>','std::vector<std::vector<std::string>>'],
  JAVA:['int','double','boolean','String','int[]','String[]','int[][]','String[][]'],
  TYPESCRIPT:['number','number','boolean','string','number[]','string[]','number[][]','string[][]'],
  CSHARP:['int','double','bool','string','int[]','string[]','int[][]','string[][]'],
  GO:['int','float64','bool','string','[]int','[]string','[][]int','[][]string'],
  RUST:['i32','f64','bool','String','Vec<i32>','Vec<String>','Vec<Vec<i32>>','Vec<Vec<String>>'],
  KOTLIN:['Int','Double','Boolean','String','IntArray','Array<String>','Array<IntArray>','Array<Array<String>>'],
  PASCAL:['LongInt','Double','Boolean','AnsiString','TIntArray','TStringArray','TIntMatrix','TStringMatrix'],
  PHP:['int','float','bool','string','array','array','array','array'],
  DART:['int','double','bool','String','List<int>','List<String>','List<List<int>>','List<List<String>>']
};
const defaults={
  PYTHON:['0','0.0','False','""','[]','[]','[]','[]'],CPP:['0','0.0','false','""','{}','{}','{}','{}'],
  JAVA:['0','0.0','false','""','new int[0]','new String[0]','new int[0][]','new String[0][]'],
  JAVASCRIPT:['0','0.0','false','""','[]','[]','[]','[]'],TYPESCRIPT:['0','0.0','false','""','[]','[]','[]','[]'],
  CSHARP:['0','0.0','false','""','new int[0]','new string[0]','new int[0][]','new string[0][]'],
  GO:['0','0.0','false','""','[]int{}','[]string{}','[][]int{}','[][]string{}'],RUST:['0','0.0','false','String::new()','vec![]','vec![]','vec![]','vec![]'],
  KOTLIN:['0','0.0','false','""','intArrayOf()','emptyArray<String>()','emptyArray<IntArray>()','emptyArray<Array<String>>()'],
  RUBY:['0','0.0','false','""','[]','[]','[]','[]'],PHP:['0','0.0','false','""','[]','[]','[]','[]'],
  DART:['0','0.0','false','""','<int>[]','<String>[]','<List<int>>[]','<List<String>>[]']
};
export function genericFunctionStarter(signature,language){
  if(!validFunctionSignature(signature))return '';
  const {name,parameters,returnType}=signature,index=GENERIC_TYPES.indexOf(returnType);
  const type=t=>types[language]?.[GENERIC_TYPES.indexOf(t)],fallback=defaults[language]?.[index];
  const names=parameters.map(p=>p.name).join(', '),typed=parameters.map(p=>`${type(p.type)} ${p.name}`).join(', ');
  const note='여기에 풀이를 작성하세요.';
  switch(language){
    case 'PYTHON':return `def ${name}(${parameters.map(p=>`${p.name}: ${type(p.type)}`).join(', ')}) -> ${type(returnType)}:\n    # ${note}\n    return ${fallback}\n`;
    case 'JAVASCRIPT':return `function ${name}(${names}) {\n    // ${note}\n    return ${fallback};\n}\n`;
    case 'TYPESCRIPT':return `export function ${name}(${parameters.map(p=>`${p.name}: ${type(p.type)}`).join(', ')}): ${type(returnType)} {\n    // ${note}\n    return ${fallback};\n}\n`;
    case 'CPP':return `#include <string>\n#include <vector>\n\n${type(returnType)} ${name}(${typed}) {\n    // ${note}\n    return ${fallback};\n}\n`;
    case 'JAVA':return `public class Main {\n    public static ${type(returnType)} ${name}(${typed}) {\n        // ${note}\n        return ${fallback};\n    }\n}\n`;
    case 'CSHARP':return `public class Solution {\n    public static ${type(returnType)} ${name}(${typed}) {\n        // ${note}\n        return ${fallback};\n    }\n}\n`;
    case 'GO':return `package main\n\nfunc ${name}(${parameters.map(p=>`${p.name} ${type(p.type)}`).join(', ')}) ${type(returnType)} {\n    // ${note}\n    return ${fallback}\n}\n`;
    case 'RUST':return `fn ${name}(${parameters.map(p=>`${p.name}: ${type(p.type)}`).join(', ')}) -> ${type(returnType)} {\n    // ${note}\n    ${fallback}\n}\n`;
    case 'KOTLIN':return `fun ${name}(${parameters.map(p=>`${p.name}: ${type(p.type)}`).join(', ')}): ${type(returnType)} {\n    // ${note}\n    return ${fallback}\n}\n`;
    case 'RUBY':return `def ${name}(${names})\n    # ${note}\n    ${fallback}\nend\n`;
    case 'PHP':return `<?php\nfunction ${name}(${parameters.map(p=>`${type(p.type)} $${p.name}`).join(', ')}): ${type(returnType)} {\n    // ${note}\n    return ${fallback};\n}\n`;
    case 'DART':return `${type(returnType)} ${name}(${typed}) {\n    // ${note}\n    return ${fallback};\n}\n`;
    case 'PASCAL':{
      const result=index>=4?'SetLength(Result, 0);':`Result := ${['0','0.0','False',"''"][index]};`;
      return `{ TIntArray / TStringArray / TIntMatrix / TStringMatrix는 채점기가 제공합니다. }\nfunction ${name}(${parameters.map(p=>`${p.name}: ${type(p.type)}`).join('; ')}): ${type(returnType)};\nbegin\n    { ${note} }\n    ${result}\nend;\n`;
    }
    case 'C':{
      const cTypes=['int','double','bool','char *','int *','CWString *','CWIntArray *','CWStringArray *'];
      const params=parameters.flatMap(p=>GENERIC_TYPES.indexOf(p.type)>=3?[`${cTypes[GENERIC_TYPES.indexOf(p.type)]} ${p.name}`,`int ${p.name}_length`]:[`${cTypes[GENERIC_TYPES.indexOf(p.type)]} ${p.name}`]);
      if(index>=3)params.push('int *result_length');
      return `#include <stdbool.h>\n#include <stdlib.h>\n// 채점기 제공: CWString {char *data; int length;}, CWIntArray {int *data; int length;}, CWStringArray {CWString *data; int length;}\n// 문자열·배열은 포인터와 길이로 전달됩니다. 반환 저장소는 함수 종료 후에도 유효해야 합니다.\n${cTypes[index]} ${name}(${params.join(', ')||'void'}) {\n    // ${note}\n${index>=3?'    *result_length = 0;\n':''}    return ${index>=3?'NULL':['0','0.0','false'][index]};\n}\n`;
    }
    case 'LLVM_IR':{
      const llvm=['i32','double','i1','ptr','ptr','ptr','ptr','ptr'];
      const params=parameters.flatMap(p=>GENERIC_TYPES.indexOf(p.type)>=3?[`ptr %${p.name}`,`i32 %${p.name}_length`]:[`${llvm[GENERIC_TYPES.indexOf(p.type)]} %${p.name}`]);
      if(index>=3)params.push('ptr %result_length');
      return `; 문자열·배열 인자는 ptr, i32 길이입니다. string[] 원소는 {ptr, i32}입니다.\ndefine ${llvm[index]} @${name}(${params.join(', ')}) {\nentry:\n    ; ${note}\n${index>=3?'    store i32 0, ptr %result_length\n':''}    ret ${llvm[index]} ${index>=3?'null':['0','0.0','false'][index]}\n}\n`;
    }
    default:return '';
  }
}
