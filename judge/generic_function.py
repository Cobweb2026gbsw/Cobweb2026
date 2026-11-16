"""Typed FUNCTION adapters, generated and executed only inside the bounded judge container.

The signature contains identifiers/types, never compiler flags or executable commands. JSON is
decoded here; child runtimes receive numeric tokens. A string is its byte count followed by
ASCII byte values, so quotes, spaces, NUL and newlines do not need language-specific escaping.
"""
import json
import math
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

TYPES = ("int", "float", "bool", "string", "int[]", "string[]", "int[][]", "string[][]")
KINDS = dict(zip(TYPES, ("I", "F", "B", "S", "IA", "SA", "IAA", "SAA")))
# These limits are shared with FunctionSignature, not inferred from a submitted program.
# Count *all* array slots, including rows, so a million empty rows cannot bypass the cap.
MAX_JSON_BYTES = 16 * 1024 * 1024
MAX_WIRE_BYTES = 64 * 1024 * 1024
MAX_ARRAY_SLOTS = 1_000_000
MAX_STRING_BYTES = 16384
LANGUAGES = ("PYTHON", "C", "CPP", "JAVA", "JAVASCRIPT", "TYPESCRIPT", "CSHARP",
             "GO", "RUST", "KOTLIN", "RUBY", "LLVM_IR", "PASCAL", "PHP", "DART")
RESERVED = frozenset('''abstract and array as assert async await base begin bool boolean break
byte case catch char class const continue crate data debugger decimal def default defer del delete
do double dynamic elif else elseif end enum eval except export extends external false final finally
float fn for foreach from fun func function global go goto if implements import in inline instanceof
int interface internal is lambda let long loop main match mod module mut namespace native new nil
none not null object of operator or out override package params pass private protected public raise
readonly record ref repeat require return self short signed sizeof sol solution static string struct
super switch then this throw throws trait true try type typeof uint ulong union unsigned until use
using val var virtual void volatile when while with yield'''.split())
PORTABLE_RESERVED = frozenset('''result synchronized transient strictfp box dyn impl macro pub where move unsafe select chan map
fallthrough range checked unchecked fixed event implicit explicit lock stackalloc ushort extern
div shl shr xor unit program implementation constructor destructor procedure packed resourcestring
threadvar absolute assembler forward inherited interrupt otherwise overload pascal register safecall
stdcall specialize published automated property typealias vararg companion crossinline infix inner
noinline open reified sealed suspend tailrec delegate sbyte nonlocal alias rescue retry redo undef
unless alignas alignof and_eq asm auto bitand bitor char8_t char16_t char32_t compl concept consteval
constexpr constinit const_cast co_await co_return co_yield decltype dynamic_cast friend mutable
noexcept not_eq nullptr or_eq reinterpret_cast requires static_assert static_cast template thread_local
typename wchar_t xor_eq typedef restrict callable clone declare die echo empty enddeclare endfor
endforeach endif endswitch endwhile exit include include_once insteadof isset list print require_once
unlet unset covariant extension factory get hide late mixin on part set show sync'''.split())


def load_json(stream):
    def unique(pairs):
        result={}
        for key,value in pairs:
            if key in result:
                raise ValueError("Duplicate JSON field")
            result[key]=value
        return result
    def finite_constant(value):
        raise ValueError("Non-finite JSON constant")
    text=stream.read(MAX_JSON_BYTES+1)
    if len(text.encode('utf-8'))>MAX_JSON_BYTES:
        raise ValueError("FUNCTION JSON limit exceeded")
    return json.loads(text,object_pairs_hook=unique,parse_constant=finite_constant)


def signature(value):
    if not isinstance(value, dict) or set(value) != {"name", "parameters", "returnType"}:
        raise ValueError("Invalid FUNCTION signature")
    if value["name"] not in ("sol", "solution") or value["returnType"] not in TYPES:
        raise ValueError("Invalid FUNCTION signature")
    parameters = value["parameters"]
    if not isinstance(parameters, list) or len(parameters) > 6:
        raise ValueError("Invalid FUNCTION parameters")
    names = set()
    for p in parameters:
        if (not isinstance(p, dict) or set(p) != {"name", "type"} or
                not isinstance(p["name"], str) or not re.fullmatch(r"[a-z][A-Za-z0-9_]{0,31}", p["name"]) or
                p["name"].lower() in RESERVED or p["name"].lower() in PORTABLE_RESERVED or p["name"].lower().endswith("_length") or
                p["name"].lower() in names or p["type"] not in TYPES):
            raise ValueError("Invalid FUNCTION parameter")
        names.add(p["name"].lower())
    return value


def validate(value, kind, budget=None):
    if kind not in TYPES:
        raise ValueError("Invalid FUNCTION type")
    if budget is None:
        budget = [MAX_ARRAY_SLOTS]
    if kind == "int":
        valid = type(value) is int and -(2 ** 31) <= value < 2 ** 31
    elif kind == "float":
        try:
            valid = type(value) in (int, float) and math.isfinite(value)
        except OverflowError:
            valid = False
    elif kind == "bool":
        valid = type(value) is bool
    elif kind == "string":
        valid = isinstance(value, str) and value.isascii() and len(value) <= MAX_STRING_BYTES
    else:
        valid = isinstance(value, list) and len(value) <= budget[0]
        if valid:
            budget[0] -= len(value)
            for item in value:
                validate(item, kind[:-2], budget)
    if not valid:
        raise ValueError("FUNCTION value does not match " + kind)
    return value


def _tokens(value, kind):
    if kind == "bool":
        yield str(int(value))
    elif kind == "string":
        yield str(len(value))
        for c in value:
            yield str(ord(c))
    elif kind.endswith("[]"):
        yield str(len(value))
        for item in value:
            yield from _tokens(item, kind[:-2])
    else:
        yield str(value)


def encode(value, kind):
    validate(value, kind)
    return list(_tokens(value, kind))


def payload(value, spec):
    if not isinstance(value, dict) or set(value) != {"args"} or not isinstance(value["args"], list):
        raise ValueError("FUNCTION input must be an args object")
    if len(value["args"]) != len(spec["parameters"]):
        raise ValueError("FUNCTION argument count does not match signature")
    budget = [MAX_ARRAY_SLOTS]
    for v, p in zip(value["args"], spec["parameters"]):
        validate(v, p["type"], budget)
    # A bytearray bounds the wire before joining, without a million-token temporary list.
    result = bytearray()
    for v, p in zip(value["args"], spec["parameters"]):
        for token in _tokens(v, p["type"]):
            encoded = token.encode("ascii")
            if len(result) + len(encoded) + 1 > MAX_WIRE_BYTES:
                raise ValueError("FUNCTION wire input limit exceeded")
            if result:
                result.append(32)
            result.extend(encoded)
    result.append(10)
    return bytes(result)


def decode(data, kind):
    if kind not in TYPES or len(data) > MAX_WIRE_BYTES:
        raise ValueError("FUNCTION output limit exceeded")
    # Iteration holds only the current numeric token. Splitting a valid 50MiB string wire
    # into sixteen million Python objects would otherwise exceed the container budget.
    tokens = (match.group() for match in re.finditer(rb"\S+",data))
    budget = [MAX_ARRAY_SLOTS]

    def number():
        text = next(tokens)
        if not (text[1:].isdigit() if text.startswith(b"-") else text.isdigit()):
            raise ValueError("Invalid integer result")
        return int(text)

    def read(t):
        if t == "int":
            return validate(number(), t)
        if t == "float":
            return validate(float(next(tokens)), t)
        if t == "bool":
            n = number()
            if n not in (0, 1):
                raise ValueError("Invalid boolean result")
            return bool(n)
        count = number()
        if count < 0 or count > (MAX_STRING_BYTES if t == "string" else budget[0]):
            raise ValueError("Invalid result length")
        if t == "string":
            values = [number() for _ in range(count)]
            if any(v < 0 or v > 127 for v in values):
                raise ValueError("Result must be ASCII")
            return "".join(map(chr, values))
        budget[0] -= count
        return [read(t[:-2]) for _ in range(count)]

    try:
        value = read(kind)
        if next(tokens, None) is not None:
            raise ValueError("Unexpected FUNCTION output")
        return validate(value, kind)
    except (StopIteration, UnicodeError) as error:
        raise ValueError("Invalid FUNCTION output") from error


def _args(spec, prefix="", suffix=""):
    return ", ".join(prefix + "cw" + KINDS[p["type"]] + "()" + suffix for p in spec["parameters"])


def _python(spec):
    return '''import sys, math, contextlib, re
cwWire=sys.stdin.buffer.read()
cwTokens=(match.group() for match in re.finditer(rb'\\S+',cwWire))
def cwI(): return int(next(cwTokens))
def cwF(): return float(next(cwTokens))
def cwB(): return cwI()!=0
def cwS(): return ''.join(chr(cwI()) for _ in range(cwI()))
def cwIA(): return [cwI() for _ in range(cwI())]
def cwSA(): return [cwS() for _ in range(cwI())]
def cwIAA(): return [cwIA() for _ in range(cwI())]
def cwSAA(): return [cwSA() for _ in range(cwI())]
cwScope={'__name__':'__submission__'}
with contextlib.redirect_stdout(sys.stderr):
 exec(compile(open('/source/main.py').read(), 'main.py', 'exec'),cwScope)
 cwResult=cwScope[NAME](ARGS)
''' .replace("NAME", repr(spec["name"])).replace("ARGS", _args(spec)) + '''
# The shared codec validates dynamic return types, including bool versus int.
sys.path.insert(0, '/opt/judge')
from generic_function import validate, _tokens
validate(cwResult, KIND)
cwChunk=[]
for cwToken in _tokens(cwResult, KIND):
 cwChunk.append(cwToken)
 if len(cwChunk)>=4096:
  sys.stdout.write(' '.join(cwChunk)+' ');cwChunk.clear()
if cwChunk: sys.stdout.write(' '.join(cwChunk))
'''.replace("KIND", repr(spec["returnType"]))


def _js(spec, source):
    return source + r'''
const cwFS=require('node:fs'),cwT=cwFS.readFileSync(0,'utf8'); let cwP=0;
function cwToken(){while(cwP<cwT.length&&cwT.charCodeAt(cwP)<=32)cwP++;const begin=cwP;while(cwP<cwT.length&&cwT.charCodeAt(cwP)>32)cwP++;return cwT.slice(begin,cwP);}
function cwI(){return Number(cwToken());} function cwF(){return cwI();}
function cwB(){return cwI()!==0;} function cwS(){const a=Buffer.allocUnsafe(cwI());for(let i=0;i<a.length;i++)a[i]=cwI();return a.toString('ascii');}
function cwIA(){return Array.from({length:cwI()},()=>cwI());} function cwSA(){return Array.from({length:cwI()},()=>cwS());}
function cwIAA(){return Array.from({length:cwI()},()=>cwIA());} function cwSAA(){return Array.from({length:cwI()},()=>cwSA());}
let cwOut='',cwBudget=1000000;
function cwFlush(){const a=Buffer.from(cwOut,'ascii');let offset=0;while(offset<a.length)offset+=cwFS.writeSync(1,a,offset,a.length-offset);cwOut='';}
function cwO(v){cwOut+=String(v)+' ';if(cwOut.length>=32768)cwFlush();}
function cwW(v,t){
 if(t==='int'){if(!Number.isInteger(v)||v< -2147483648||v>2147483647)throw Error('Expected int');cwO(v);return;}
 if(t==='float'){if(typeof v!=='number'||!Number.isFinite(v))throw Error('Expected float');cwO(v);return;}
 if(t==='bool'){if(typeof v!=='boolean')throw Error('Expected bool');cwO(v?1:0);return;}
 if(t==='string'){if(typeof v!=='string'||v.length>16384)throw Error('Expected ASCII');cwO(v.length);for(let i=0;i<v.length;i++){const c=v.charCodeAt(i);if(c>127)throw Error('Expected ASCII');cwO(c);}return;}
 if(!Array.isArray(v)||v.length>cwBudget)throw Error('Expected bounded array');cwBudget-=v.length;cwO(v.length);for(const item of v)cwW(item,t.slice(0,-2));
}
cwW(NAME(ARGS),KIND);cwFlush();
'''.replace("NAME", spec["name"]).replace("ARGS", _args(spec)).replace("KIND", json.dumps(spec["returnType"]))


def _c(spec, cpp=False, llvm=False):
    prelude = r'''#include <stdio.h>
#include <stdlib.h>
#include <stdbool.h>
#include <string.h>
typedef struct { char *data; int length; } CWString;
typedef struct { int *data; int length; } CWIntArray;
typedef struct { CWString *data; int length; } CWStringArray;
static int cwI(void){int x;if(scanf("%d",&x)!=1)exit(1);return x;}
static double cwF(void){double x;if(scanf("%lf",&x)!=1)exit(1);return x;}
static bool cwB(void){return cwI()!=0;}
static char *cwS(int *n){*n=cwI();char *s=malloc((size_t)*n+1);for(int i=0;i<*n;i++)s[i]=(char)cwI();s[*n]=0;return s;}
static int *cwIA(int *n){*n=cwI();int *a=malloc(((size_t)*n+1)*sizeof(int));for(int i=0;i<*n;i++)a[i]=cwI();return a;}
static CWString *cwSA(int *n){*n=cwI();CWString *a=calloc((size_t)*n+1,sizeof(CWString));for(int i=0;i<*n;i++)a[i].data=cwS(&a[i].length);return a;}
static CWIntArray *cwIAA(int *n){*n=cwI();CWIntArray *a=calloc((size_t)*n+1,sizeof(CWIntArray));for(int i=0;i<*n;i++)a[i].data=cwIA(&a[i].length);return a;}
static CWStringArray *cwSAA(int *n){*n=cwI();CWStringArray *a=calloc((size_t)*n+1,sizeof(CWStringArray));for(int i=0;i<*n;i++)a[i].data=cwSA(&a[i].length);return a;}
static int cwBudget=1000000;
static void cwWA(int n){if(n<0||n>cwBudget)exit(1);cwBudget-=n;printf("%d ",n);}
static void cwWS(const char *s,int n){if(n<0||n>16384||(!s&&n))exit(1);printf("%d ",n);for(int i=0;i<n;i++)printf("%d ",(unsigned char)s[i]);}
'''
    if cpp:
        prelude = '#include <bits/stdc++.h>\nusing namespace std;\n' + prelude.replace("=malloc(", "=(char*)malloc(", 1)
        prelude = prelude.replace('int *a=malloc(', 'int *a=(int*)malloc(').replace('CWString *a=calloc(', 'CWString *a=(CWString*)calloc(')
        prelude = prelude.replace('CWIntArray *a=calloc(', 'CWIntArray *a=(CWIntArray*)calloc(').replace('CWStringArray *a=calloc(', 'CWStringArray *a=(CWStringArray*)calloc(')
        prelude += r'''
static string cwCS(){int n;char *s=cwS(&n);return string(s,n);}
static vector<int> cwCIA(){int n;int *a=cwIA(&n);return vector<int>(a,a+n);}
static vector<string> cwCSA(){int n=cwI();vector<string>a;while(n--)a.push_back(cwCS());return a;}
static vector<vector<int>> cwCIAA(){int n=cwI();vector<vector<int>>a;while(n--)a.push_back(cwCIA());return a;}
static vector<vector<string>> cwCSAA(){int n=cwI();vector<vector<string>>a;while(n--)a.push_back(cwCSA());return a;}
'''
    types = {"int":"int", "float":"double", "bool":"bool", "string":"char*", "int[]":"int*", "string[]":"CWString*", "int[][]":"CWIntArray*", "string[][]":"CWStringArray*"}
    if cpp:
        types.update({"string":"string", "int[]":"vector<int>", "string[]":"vector<string>", "int[][]":"vector<vector<int>>", "string[][]":"vector<vector<string>>"})
    declarations, call, proto = [], [], []
    for i,p in enumerate(spec["parameters"]):
        t=p["type"]; k=KINDS[t]; v="cwArg"+str(i)
        if not cpp and (t == "string" or t.endswith("[]")):
            declarations.append(f"int {v}N; {types[t]} {v}=cw{k}(&{v}N);")
            call.extend([v,v+"N"]); proto.extend([types[t],"int"])
        else:
            reader="cwC"+k if cpp and (t == "string" or t.endswith("[]")) else "cw"+k
            declarations.append(f"{types[t]} {v}={reader}();");call.append(v);proto.append(types[t])
    rt=spec["returnType"]; pointer=not cpp and (rt == "string" or rt.endswith("[]"))
    if pointer:
        declarations.append("int cwCount=0;");call.append("&cwCount");proto.append("int*")
    if llvm:
        prelude+=f"extern {types[rt]} {spec['name']}({', '.join(proto) if proto else 'void'});\n"
    else:
        prelude+='#include "/source/main.'+('cpp' if cpp else 'c')+'"\n'
    expression=f"{spec['name']}({', '.join(call)})"
    body="\n".join(declarations)
    if cpp:
        body+=f'\nstatic_assert(std::is_same_v<decltype({expression}),{types[rt]}>,"FUNCTION return type mismatch");'
    elif not llvm:
        body+=f'\n_Static_assert(_Generic(({expression}),{types[rt]}:1,default:0),"FUNCTION return type mismatch");'
    body+=f"\n{types[rt]} cwResult={expression};\n"
    if rt=="int": body+='printf("%d",cwResult);'
    elif rt=="float": body+='printf("%.17g",cwResult);'
    elif rt=="bool": body+='printf("%d",cwResult?1:0);'
    else:
        n="(int)cwResult.size()" if cpp else "cwCount"
        if rt=="string": body+=f"cwWS({'cwResult.data()' if cpp else 'cwResult'},{n});"
        else:
            if not cpp:
                body+=f'if(!cwResult&&{n})return 1;'
            body+=f'cwWA({n}); for(int i=0;i<{n};i++){{'
            if rt in ("int[][]", "string[][]"):
                row="cwResult[i]" if cpp else "cwResult[i].data"
                row_n="(int)cwResult[i].size()" if cpp else "cwResult[i].length"
                if not cpp:
                    body+=f'if(!{row}&&{row_n})return 1;'
                body+=f'cwWA({row_n});for(int j=0;j<{row_n};j++){{'
                body+=f'printf("%d ",{row}[j]);' if rt=="int[][]" else (f'cwWS({row}[j].data(),(int){row}[j].size());' if cpp else f'cwWS({row}[j].data,{row}[j].length);')
                body+='}'
            else:
                body+='printf("%d ",cwResult[i]);' if rt=="int[]" else ('cwWS(cwResult[i].data(),(int)cwResult[i].size());' if cpp else 'cwWS(cwResult[i].data,cwResult[i].length);')
            body+='}'
    return prelude+'\nint main(void){\n'+body+'\nreturn 0;}\n'


def _java(spec):
    types={"int":"int","float":"double","bool":"boolean","string":"String","int[]":"int[]","string[]":"String[]","int[][]":"int[][]","string[][]":"String[][]"}
    return r'''import java.io.*;
public class GenericHarness {
static final BufferedInputStream cwInput=new BufferedInputStream(System.in,65536);
static final PrintWriter cwOutput=new PrintWriter(new BufferedWriter(new OutputStreamWriter(System.out),65536));
static String cwToken(){try{StringBuilder s=new StringBuilder();int c;do{c=cwInput.read();}while(c>=0&&c<=32);while(c>32){s.append((char)c);c=cwInput.read();}return s.toString();}catch(IOException e){throw new UncheckedIOException(e);}}
static int cwI(){try{int c;do{c=cwInput.read();}while(c>=0&&c<=32);boolean neg=c==45;if(neg)c=cwInput.read();long n=0;while(c>32){n=n*10+c-48;c=cwInput.read();}return (int)(neg?-n:n);}catch(IOException e){throw new UncheckedIOException(e);}}
static double cwF(){return Double.parseDouble(cwToken());} static boolean cwB(){return cwI()!=0;}
static String cwS(){int n=cwI();StringBuilder s=new StringBuilder();while(n-->0)s.append((char)cwI());return s.toString();}
static int[] cwIA(){int[] a=new int[cwI()];for(int i=0;i<a.length;i++)a[i]=cwI();return a;}
static String[] cwSA(){String[] a=new String[cwI()];for(int i=0;i<a.length;i++)a[i]=cwS();return a;}
static int[][] cwIAA(){int[][] a=new int[cwI()][];for(int i=0;i<a.length;i++)a[i]=cwIA();return a;}
static String[][] cwSAA(){String[][] a=new String[cwI()][];for(int i=0;i<a.length;i++)a[i]=cwSA();return a;}
static void cwW(int v){cwOutput.print(v);cwOutput.print(' ');} static void cwW(double v){cwOutput.print(v);cwOutput.print(' ');}
static void cwW(boolean v){cwW(v?1:0);} static void cwW(String v){cwW(v.length());for(int i=0;i<v.length();i++)cwW((int)v.charAt(i));}
static void cwW(int[] a){cwW(a.length);for(int v:a)cwW(v);}static void cwW(String[] a){cwW(a.length);for(String v:a)cwW(v);}
static void cwW(int[][] a){cwW(a.length);for(int[] v:a)cwW(v);}static void cwW(String[][] a){cwW(a.length);for(String[] v:a)cwW(v);}
public static void main(String[] args)throws Exception{RESULT cwResult=Main.NAME(ARGS);cwW(cwResult);cwOutput.flush();}}
'''.replace("RESULT",types[spec["returnType"]]).replace("NAME",spec["name"]).replace("ARGS",_args(spec))


def _csharp(spec):
    types={"int":"int","float":"double","bool":"bool","string":"string","int[]":"int[]","string[]":"string[]","int[][]":"int[][]","string[][]":"string[][]"}
    return r'''using System;using System.Globalization;using System.Text;using System.IO;
public static class GenericHarness {
static readonly Stream cwInput=Console.OpenStandardInput();static readonly byte[] cwBuffer=new byte[65536];static int cwPosition,cwLength;static StringBuilder cwOutput=new StringBuilder();
static int cwRead(){if(cwPosition>=cwLength){cwLength=cwInput.Read(cwBuffer,0,cwBuffer.Length);cwPosition=0;if(cwLength==0)return -1;}return cwBuffer[cwPosition++];}
static string cwToken(){int c;do{c=cwRead();}while(c>=0&&c<=32);if(c<0)throw new EndOfStreamException();var token=new StringBuilder();while(c>32){token.Append((char)c);c=cwRead();}return token.ToString();}
static int cwI(){int c;do{c=cwRead();}while(c>=0&&c<=32);if(c<0)throw new EndOfStreamException();bool negative=c==45;if(negative)c=cwRead();long n=0;while(c>32){n=n*10+c-48;c=cwRead();}return (int)(negative?-n:n);}
static double cwF()=>double.Parse(cwToken(),CultureInfo.InvariantCulture);static bool cwB()=>cwI()!=0;
static void cwO(string v){cwOutput.Append(v).Append(' ');if(cwOutput.Length>=32768){Console.Write(cwOutput.ToString());cwOutput.Clear();}}
static string cwS(){var a=new char[cwI()];for(int i=0;i<a.Length;i++)a[i]=(char)cwI();return new string(a);}
static int[] cwIA(){var a=new int[cwI()];for(int i=0;i<a.Length;i++)a[i]=cwI();return a;}
static string[] cwSA(){var a=new string[cwI()];for(int i=0;i<a.Length;i++)a[i]=cwS();return a;}
static int[][] cwIAA(){var a=new int[cwI()][];for(int i=0;i<a.Length;i++)a[i]=cwIA();return a;}
static string[][] cwSAA(){var a=new string[cwI()][];for(int i=0;i<a.Length;i++)a[i]=cwSA();return a;}
static void cwW(int v)=>cwO(v.ToString(CultureInfo.InvariantCulture));static void cwW(double v)=>cwO(v.ToString("R",CultureInfo.InvariantCulture));
static void cwW(bool v)=>cwW(v?1:0);static void cwW(string v){cwW(v.Length);foreach(char c in v)cwW((int)c);}
static void cwW(int[] a){cwW(a.Length);foreach(int v in a)cwW(v);}static void cwW(string[] a){cwW(a.Length);foreach(string v in a)cwW(v);}
static void cwW(int[][] a){cwW(a.Length);foreach(int[] v in a)cwW(v);}static void cwW(string[][] a){cwW(a.Length);foreach(string[] v in a)cwW(v);}
public static void Main(){RESULT cwResult=Solution.NAME(ARGS);cwW(cwResult);Console.Write(cwOutput.ToString());}}
'''.replace("RESULT",types[spec["returnType"]]).replace("NAME",spec["name"]).replace("ARGS",_args(spec))


def _go(spec):
    types={"int":"int","float":"float64","bool":"bool","string":"string","int[]":"[]int","string[]":"[]string","int[][]":"[][]int","string[][]":"[][]string"}
    return r'''package main
import("bufio";"fmt";"os")
var cwInput=bufio.NewReader(os.Stdin)
var cwOutput=bufio.NewWriterSize(os.Stdout,65536)
func cwI()int{var x int;if _,e:=fmt.Fscan(cwInput,&x);e!=nil{panic(e)};return x}
func cwF()float64{var x float64;if _,e:=fmt.Fscan(cwInput,&x);e!=nil{panic(e)};return x}
func cwB()bool{return cwI()!=0}
func cwS()string{a:=make([]byte,cwI());for i:=range a{a[i]=byte(cwI())};return string(a)}
func cwIA()[]int{a:=make([]int,cwI());for i:=range a{a[i]=cwI()};return a}
func cwSA()[]string{a:=make([]string,cwI());for i:=range a{a[i]=cwS()};return a}
func cwIAA()[][]int{a:=make([][]int,cwI());for i:=range a{a[i]=cwIA()};return a}
func cwSAA()[][]string{a:=make([][]string,cwI());for i:=range a{a[i]=cwSA()};return a}
func cwW(v interface{}){switch x:=v.(type){case int:fmt.Fprint(cwOutput,x," ");case float64:fmt.Fprintf(cwOutput,"%.17g ",x);case bool:if x{fmt.Fprint(cwOutput,"1 ")}else{fmt.Fprint(cwOutput,"0 ")};case string:fmt.Fprint(cwOutput,len(x)," ");for _,b:=range []byte(x){fmt.Fprint(cwOutput,b," ")};case []int:fmt.Fprint(cwOutput,len(x)," ");for _,v:=range x{cwW(v)};case []string:fmt.Fprint(cwOutput,len(x)," ");for _,v:=range x{cwW(v)};case [][]int:fmt.Fprint(cwOutput,len(x)," ");for _,v:=range x{cwW(v)};case [][]string:fmt.Fprint(cwOutput,len(x)," ");for _,v:=range x{cwW(v)};default:panic("Invalid return type")}}
func main(){var cwResult RESULT=NAME(ARGS);cwW(cwResult);cwOutput.Flush()}
'''.replace("RESULT",types[spec["returnType"]]).replace("NAME",spec["name"]).replace("ARGS",_args(spec))


def _rust(spec):
    types={"int":"i32","float":"f64","bool":"bool","string":"String","int[]":"Vec<i32>","string[]":"Vec<String>","int[][]":"Vec<Vec<i32>>","string[][]":"Vec<Vec<String>>"}
    # The reader is passed explicitly, avoiding unsafe mutable global state.
    bridge="mod cw_submission { include!(\"/source/main.rs\"); pub fn cw_invoke("+", ".join("cw_arg"+str(i)+":"+types[p["type"]] for i,p in enumerate(spec["parameters"]))+")->"+types[spec["returnType"]]+" { "+spec["name"]+"("+", ".join("cw_arg"+str(i) for i in range(len(spec["parameters"])))+") } }\n"
    return bridge+r'''use std::io::{self,Read};
fn cw_i(t:&mut std::str::SplitWhitespace)->i32{t.next().unwrap().parse().unwrap()}
fn cw_f(t:&mut std::str::SplitWhitespace)->f64{t.next().unwrap().parse().unwrap()}
fn cw_b(t:&mut std::str::SplitWhitespace)->bool{cw_i(t)!=0}
fn cw_s(t:&mut std::str::SplitWhitespace)->String{let n=cw_i(t);String::from_utf8((0..n).map(|_|cw_i(t)as u8).collect()).unwrap()}
fn cw_ia(t:&mut std::str::SplitWhitespace)->Vec<i32>{let n=cw_i(t);(0..n).map(|_|cw_i(t)).collect()}
fn cw_sa(t:&mut std::str::SplitWhitespace)->Vec<String>{let n=cw_i(t);(0..n).map(|_|cw_s(t)).collect()}
fn cw_iaa(t:&mut std::str::SplitWhitespace)->Vec<Vec<i32>>{let n=cw_i(t);(0..n).map(|_|cw_ia(t)).collect()}
fn cw_saa(t:&mut std::str::SplitWhitespace)->Vec<Vec<String>>{let n=cw_i(t);(0..n).map(|_|cw_sa(t)).collect()}
trait CwWrite{fn cw_write(&self,w:&mut impl std::io::Write);}
impl CwWrite for i32{fn cw_write(&self,w:&mut impl std::io::Write){write!(w,"{} ",self).unwrap();}} impl CwWrite for f64{fn cw_write(&self,w:&mut impl std::io::Write){write!(w,"{} ",self).unwrap();}}
impl CwWrite for bool{fn cw_write(&self,w:&mut impl std::io::Write){write!(w,"{} ",if *self{1}else{0}).unwrap();}}
impl CwWrite for String{fn cw_write(&self,w:&mut impl std::io::Write){write!(w,"{} ",self.len()).unwrap();for b in self.bytes(){write!(w,"{} ",b).unwrap();}}}
impl<T:CwWrite> CwWrite for Vec<T>{fn cw_write(&self,w:&mut impl std::io::Write){write!(w,"{} ",self.len()).unwrap();for v in self{v.cw_write(w);}}}
fn main(){let mut cw_input=String::new();io::stdin().read_to_string(&mut cw_input).unwrap();let mut cw_t=cw_input.split_whitespace();let cw_result:RESULT=cw_submission::cw_invoke(ARGS);let mut cw_out=std::io::BufWriter::new(io::stdout());cw_result.cw_write(&mut cw_out);}
'''.replace("RESULT",types[spec["returnType"]]).replace("NAME",spec["name"]).replace("ARGS",", ".join("cw_"+KINDS[p["type"]].lower()+"(&mut cw_t)" for p in spec["parameters"]))


def _kotlin(spec):
    types={"int":"Int","float":"Double","bool":"Boolean","string":"String","int[]":"IntArray","string[]":"Array<String>","int[][]":"Array<IntArray>","string[][]":"Array<Array<String>>"}
    return r'''private val cwT=System.`in`.bufferedReader().readText();private var cwP=0
private val cwOutput=StringBuilder()
private fun cwToken():String{while(cwP<cwT.length&&cwT[cwP]<=' ')cwP++;val begin=cwP;while(cwP<cwT.length&&cwT[cwP]>' ')cwP++;return cwT.substring(begin,cwP)}
private fun cwI():Int=cwToken().toInt()
private fun cwF():Double=cwToken().toDouble()
private fun cwB():Boolean=cwI()!=0
private fun cwS():String=buildString{repeat(cwI()){append(cwI().toChar())}}
private fun cwIA():IntArray=IntArray(cwI()){cwI()}
private fun cwSA():Array<String> = Array(cwI()){cwS()}
private fun cwIAA():Array<IntArray> = Array(cwI()){cwIA()}
private fun cwSAA():Array<Array<String>> = Array(cwI()){cwSA()}
private fun cwO(v:Any){cwOutput.append(v).append(' ');if(cwOutput.length>=32768){print(cwOutput.toString());cwOutput.setLength(0)}}
private fun cwW(v:Any){when(v){is Int->cwO(v);is Double->cwO(v);is Boolean->cwW(if(v)1 else 0);is String->{cwW(v.length);v.forEach{cwW(it.code)}};is IntArray->{cwW(v.size);v.forEach{cwW(it)}};is Array<*>->{cwW(v.size);v.forEach{cwW(it!!)}};else->error("Invalid return")}}
fun main(){val cwResult:RESULT = NAME(ARGS);cwW(cwResult);print(cwOutput.toString())}
'''.replace("RESULT",types[spec["returnType"]]).replace("NAME",spec["name"]).replace("ARGS",_args(spec))


def _ruby(spec):
    return r'''$cwT=STDIN.read;$cwP=0
def cwToken;$cwP+=1 while $cwP<$cwT.bytesize&&$cwT.getbyte($cwP)<=32;b=$cwP;$cwP+=1 while $cwP<$cwT.bytesize&&$cwT.getbyte($cwP)>32;$cwT.byteslice(b,$cwP-b);end
def cwI;cwToken.to_i;end
def cwF;cwToken.to_f;end
def cwB;cwI()!=0;end
def cwS;Array.new(cwI()){cwI()}.pack('C*');end
def cwIA;Array.new(cwI()){cwI()};end
def cwSA;Array.new(cwI()){cwS()};end
def cwIAA;Array.new(cwI()){cwIA()};end
def cwSAA;Array.new(cwI()){cwSA()};end
$cwOut='';$cwBudget=1000000
def cwO(v);$cwOut<<v.to_s<<' ';if $cwOut.bytesize>=32768;STDOUT.write($cwOut);$cwOut.clear;end;end
def cwW(v,t)
case t
when 'int';raise 'Expected int' unless v.instance_of?(Integer)&&v>=-2147483648&&v<=2147483647;cwO(v)
when 'float';raise 'Expected float' unless v.is_a?(Numeric)&&v.finite?;cwO(v)
when 'bool';raise 'Expected bool' unless v==true||v==false;cwO(v ? 1 : 0)
when 'string';raise 'Expected ASCII' unless v.instance_of?(String)&&v.ascii_only?&&v.bytesize<=16384;cwO(v.bytesize);v.each_byte{|c|cwO(c)}
else;raise 'Expected bounded array' unless v.instance_of?(Array)&&v.length<=$cwBudget;$cwBudget-=v.length;cwO(v.length);v.each{|x|cwW(x,t[0...-2])}
end;end
load '/source/main.rb'
cwW(NAME(ARGS),KIND);STDOUT.write($cwOut)
'''.replace("NAME",spec["name"]).replace("ARGS",_args(spec)).replace("KIND",repr(spec["returnType"]))


def _php(spec):
    return r'''<?php
$cwT=stream_get_contents(STDIN);$cwP=0;$cwOut='';$cwBudget=1000000;
function cwToken(){global $cwT,$cwP;$cwP+=strspn($cwT," \t\r\n",$cwP);$n=strcspn($cwT," \t\r\n",$cwP);$v=substr($cwT,$cwP,$n);$cwP+=$n;return $v;}
function cwI(){return (int)cwToken();}function cwF(){return (float)cwToken();}function cwB(){return cwI()!=0;}
function cwS(){$n=cwI();$s='';while($n--)$s.=chr(cwI());return $s;}
function cwIA(){$n=cwI();$a=[];while($n--)$a[]=cwI();return $a;}function cwSA(){$n=cwI();$a=[];while($n--)$a[]=cwS();return $a;}
function cwIAA(){$n=cwI();$a=[];while($n--)$a[]=cwIA();return $a;}function cwSAA(){$n=cwI();$a=[];while($n--)$a[]=cwSA();return $a;}
function cwO($v){global $cwOut;$cwOut.=(string)$v.' ';if(strlen($cwOut)>=32768){echo $cwOut;$cwOut='';}}
function cwW($v,$t){global $cwBudget;if($t==='int'){if(!is_int($v)||$v< -2147483648||$v>2147483647)throw new Exception('Expected int');cwO($v);return;}
if($t==='float'){if((!is_float($v)&&!is_int($v))||!is_finite((float)$v))throw new Exception('Expected float');cwO($v);return;}
if($t==='bool'){if(!is_bool($v))throw new Exception('Expected bool');cwO($v?1:0);return;}
if($t==='string'){if(!is_string($v)||strlen($v)>16384||preg_match('/[^\x00-\x7f]/',$v))throw new Exception('Expected ASCII');cwO(strlen($v));for($i=0;$i<strlen($v);$i++)cwO(ord($v[$i]));return;}
if(!is_array($v)||!array_is_list($v)||count($v)>$cwBudget)throw new Exception('Expected bounded array');$cwBudget-=count($v);cwO(count($v));foreach($v as $x)cwW($x,substr($t,0,-2));}
require '/source/main.php';
cwW(NAME(ARGS),KIND);echo $cwOut;
'''.replace("NAME",spec["name"]).replace("ARGS",_args(spec)).replace("KIND",repr(spec["returnType"]))


def _dart(spec):
    return r'''import 'dart:io';import '/source/main.dart' as submission;
late String cwT;int cwP=0;
String cwToken(){while(cwP<cwT.length&&cwT.codeUnitAt(cwP)<=32){cwP++;}final begin=cwP;while(cwP<cwT.length&&cwT.codeUnitAt(cwP)>32){cwP++;}return cwT.substring(begin,cwP);}
int cwI()=>int.parse(cwToken());double cwF()=>double.parse(cwToken());bool cwB()=>cwI()!=0;
String cwS()=>String.fromCharCodes(List.generate(cwI(),(_)=>cwI()));
List<int> cwIA()=>List.generate(cwI(),(_)=>cwI());List<String> cwSA()=>List.generate(cwI(),(_)=>cwS());
List<List<int>> cwIAA()=>List.generate(cwI(),(_)=>cwIA());List<List<String>> cwSAA()=>List.generate(cwI(),(_)=>cwSA());
StringBuffer cwOutput=StringBuffer();int cwBudget=1000000;
void cwO(Object v){cwOutput.write(v);cwOutput.write(' ');if(cwOutput.length>=32768){stdout.write(cwOutput.toString());cwOutput.clear();}}
void cwW(dynamic v,String t){if(t=='int'){if(v is! int||v< -2147483648||v>2147483647)throw StateError('Expected int');cwO(v);return;}
if(t=='float'){if(v is! num||!v.isFinite)throw StateError('Expected float');cwO(v);return;}
if(t=='bool'){if(v is! bool)throw StateError('Expected bool');cwO(v?1:0);return;}
if(t=='string'){if(v is! String||v.length>16384||v.codeUnits.any((x)=>x>127))throw StateError('Expected ASCII');cwO(v.length);for(final c in v.codeUnits){cwO(c);}return;}
if(v is! List||v.length>cwBudget)throw StateError('Expected bounded List');cwBudget-=v.length;cwO(v.length);for(final item in v){cwW(item,t.substring(0,t.length-2));}}
void main(){cwT=File('/dev/stdin').readAsStringSync();cwW(submission.NAME(ARGS),KIND);stdout.write(cwOutput.toString());}
'''.replace("NAME",spec["name"]).replace("ARGS",_args(spec)).replace("KIND",json.dumps(spec["returnType"]))


def _pascal(spec):
    types={"int":"LongInt","float":"Double","bool":"Boolean","string":"AnsiString","int[]":"TIntArray","string[]":"TStringArray","int[][]":"TIntMatrix","string[][]":"TStringMatrix"}
    prelude=r'''program GenericHarness;
{$mode objfpc}{$H+}
type TIntArray=array of LongInt; TStringArray=array of AnsiString;
TIntMatrix=array of TIntArray; TStringMatrix=array of TStringArray;
function cwI:LongInt;begin Read(cwI);end;
function cwF:Double;begin Read(cwF);end;
function cwB:Boolean;begin cwB:=cwI()<>0;end;
function cwS:AnsiString;var n,i:LongInt;s:AnsiString;begin n:=cwI();SetLength(s,n);for i:=1 to n do s[i]:=AnsiChar(cwI());cwS:=s;end;
function cwIA:TIntArray;var n,i:LongInt;a:TIntArray;begin n:=cwI();SetLength(a,n);for i:=0 to n-1 do a[i]:=cwI();cwIA:=a;end;
function cwSA:TStringArray;var n,i:LongInt;a:TStringArray;begin n:=cwI();SetLength(a,n);for i:=0 to n-1 do a[i]:=cwS();cwSA:=a;end;
function cwIAA:TIntMatrix;var n,i:LongInt;a:TIntMatrix;begin n:=cwI();SetLength(a,n);for i:=0 to n-1 do a[i]:=cwIA();cwIAA:=a;end;
function cwSAA:TStringMatrix;var n,i:LongInt;a:TStringMatrix;begin n:=cwI();SetLength(a,n);for i:=0 to n-1 do a[i]:=cwSA();cwSAA:=a;end;
procedure cwWS(const s:AnsiString);var i:LongInt;begin Write(Length(s),' ');for i:=1 to Length(s)do Write(Ord(s[i]),' ');end;
{$I /source/main.pas}
'''
    declarations=[f"cwArg{i}:{types[p['type']]};" for i,p in enumerate(spec["parameters"])]
    body=''.join(f"cwArg{i}:=cw{KINDS[p['type']]}();" for i,p in enumerate(spec["parameters"]))
    body+=f"cwResult:={spec['name']}({','.join('cwArg'+str(i) for i in range(len(spec['parameters'])))});"
    rt=spec["returnType"]
    if rt=="bool": body+="if cwResult then Write('1')else Write('0');"
    elif rt in ("int","float"): body+="Write(cwResult);"
    elif rt=="string": body+="cwWS(cwResult);"
    else:
        body+="Write(Length(cwResult),' ');for cwIndex:=0 to Length(cwResult)-1 do "
        if rt in ("int[][]", "string[][]"):
            body+="begin Write(Length(cwResult[cwIndex]),' ');for cwInner:=0 to Length(cwResult[cwIndex])-1 do "
            body+="Write(cwResult[cwIndex][cwInner],' ');end;" if rt=="int[][]" else "cwWS(cwResult[cwIndex][cwInner]);end;"
        else:
            body+="Write(cwResult[cwIndex],' ');" if rt=="int[]" else "cwWS(cwResult[cwIndex]);"
    return prelude+'var '+''.join(declarations)+f"cwResult:{types[rt]};cwIndex,cwInner:LongInt;\nbegin "+body+'end.\n'


def generate(language, spec, directory):
    """Write wrappers only to /work (compile) or /tmp (interpreted runtime)."""
    spec=signature(spec)
    if language not in LANGUAGES:
        raise ValueError("Unsupported generic FUNCTION language")
    directory=Path(directory)
    extension={"PYTHON":"py","C":"c","CPP":"cpp","JAVA":"java","JAVASCRIPT":"js","TYPESCRIPT":"ts",
               "CSHARP":"cs","GO":"go","RUST":"rs","KOTLIN":"kt","RUBY":"rb","LLVM_IR":"c","PASCAL":"pas","PHP":"php","DART":"dart"}[language]
    name="GenericHarness" if language in ("JAVA","CSHARP") else "generic_harness"
    path=directory/(name+'.'+extension)
    builders={"PYTHON":_python,"JAVA":_java,"CSHARP":_csharp,"GO":_go,"RUST":_rust,
              "KOTLIN":_kotlin,"RUBY":_ruby,"PASCAL":_pascal,"PHP":_php,"DART":_dart}
    if language in ("C","CPP","LLVM_IR"):
        code=_c(spec,cpp=language=="CPP",llvm=language=="LLVM_IR")
    elif language in ("JAVASCRIPT","TYPESCRIPT"):
        code=_js(spec,Path('/source/main.'+extension).read_text())
    else:
        code=builders[language](spec)
    path.write_text(code)
    return str(path)


def compile_command(language, spec):
    path=generate(language,spec,'/work')
    commands={
        "C":["gcc","-std=c17","-O2",path,"-o","/work/main","-lm"],
        "CPP":["g++","-std=c++20","-O2",path,"-o","/work/main"],
        "JAVA":["javac","-J-Xmx512m","-d","/work","/source/Main.java",path],
        "TYPESCRIPT":["node","/opt/typescript/node_modules/typescript/bin/tsc",path,"--target","ES2022","--module","commonjs","--types","node","--typeRoots","/opt/typescript/node_modules/@types","--outDir","/work","--noEmitOnError","--skipLibCheck"],
        "GO":["go","build","-o","/work/main","/work/main.go",path],
        "RUST":["rustc","--edition=2021","-O",path,"-o","/work/main"],
        "KOTLIN":["/opt/kotlinc/bin/kotlinc","-J-Xmx512m","/source/Main.kt",path,"-include-runtime","-d","/work/main.jar"],
        "LLVM_IR":["clang-18","-O2","/source/main.ll",path,"-o","/work/main"],
        "PASCAL":["fpc","-Mobjfpc","-O2","-FE/work","-FU/work","-o/work/main",path],
        "DART":["dart","compile","exe",path,"-o","/work/main"],
    }
    if language=="GO":
        Path('/work/main.go').write_bytes(Path('/source/main.go').read_bytes())
    if language=="CSHARP":
        project=Path('/work/Generic.csproj')
        project.write_text('<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup><OutputType>Exe</OutputType><TargetFramework>net10.0</TargetFramework><EnableDefaultCompileItems>false</EnableDefaultCompileItems><NuGetAudit>false</NuGetAudit></PropertyGroup><ItemGroup><Compile Include="/source/Main.cs"/><Compile Include="/work/GenericHarness.cs"/></ItemGroup></Project>')
        return ["dotnet","build",str(project),"--configfile","/opt/csharp/NuGet.Config","-o","/work","-p:BaseIntermediateOutputPath=/tmp/obj/","-p:UseSharedCompilation=false","--disable-build-servers","-v:q"]
    return commands[language]


def run(language, spec, memory, java, node, php):
    incoming=load_json(sys.stdin)
    data=payload(incoming,spec)
    del incoming
    if language in ("PYTHON","JAVASCRIPT","RUBY","PHP"):
        path=generate(language,spec,'/tmp')
        command={"PYTHON":["python3","-I",path],"JAVASCRIPT":node+[path],"RUBY":["ruby","--disable-gems",path],"PHP":php+[path]}[language]
    else:
        command={"JAVA":java+["-cp","/work","GenericHarness"],"TYPESCRIPT":node+["/work/generic_harness.js"],
                 "CSHARP":["dotnet","/work/Generic.dll"],"KOTLIN":java+["-jar","/work/main.jar"]}.get(language,["/work/main"])
    with tempfile.TemporaryFile() as input_file:
        input_file.write(data); input_file.seek(0)
        del data
        with subprocess.Popen(command,stdin=input_file,stdout=subprocess.PIPE) as child:
            # Numeric byte tokens expand strings, but both the wire and final JSON are bounded.
            output=child.stdout.read(MAX_WIRE_BYTES+1)
            if len(output)>MAX_WIRE_BYTES:
                child.kill();raise ValueError('FUNCTION output limit exceeded')
            if child.wait()!=0:
                raise ValueError('FUNCTION solution failed')
    # All decoded strings are already ASCII. In particular DEL (0x7f) is a legal
    # one-byte JSON character and must not expand sixfold into an optional escape.
    result=json.dumps(decode(output,spec['returnType']),allow_nan=False,ensure_ascii=False,separators=(',',':'))
    if len(result.encode('ascii'))>MAX_JSON_BYTES:
        raise ValueError('FUNCTION JSON output limit exceeded')
    # No extra newline: the documented JSON-byte boundary is also the stdout boundary.
    sys.stdout.write(result)
