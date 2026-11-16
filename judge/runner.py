"""이미지 안의 고정 실행기. 제출 문자열을 셸 명령/빌드 옵션으로 해석하지 않습니다.

컴파일과 실행은 호스트가 서로 다른 제한/컨테이너로 호출합니다. 컴파일 결과만
크기가 제한된 볼륨에 남기며 테스트 실행에서는 해당 볼륨도 읽기 전용입니다.
"""
import contextlib
import csv
import json
import os
import shutil
import sqlite3
import sys

# -I omits script paths; import only the trusted module shipped in this image.
sys.path.insert(0, "/opt/judge")
import generic_function

phase, language, mode, memory = sys.argv[1:]
memory = int(memory)
heap = max(16, memory // 2)
java = ["java", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1", f"-Xmx{heap}m", "-Xss512k"]
node = ["node", f"--max-old-space-size={heap}"]
# CLI 전용 PHP입니다. php.ini/외부 URL include를 사용하지 않으며 Docker 제한도 그대로 적용됩니다.
php = ["php", "-n", "-d", f"memory_limit={heap}M", "-d", "display_errors=stderr",
       "-d", "log_errors=0", "-d", "html_errors=0", "-d", "allow_url_fopen=0",
       "-d", "allow_url_include=0", "-d", "expose_php=0"]
compilers = {
    "C": ["gcc", "-std=c17", "-O2", "/source/main.c", "-o", "/work/main", "-lm"],
    "CPP": ["g++", "-std=c++20", "-O2", "/source/main.cpp", "-o", "/work/main"],
    "JAVA": ["javac", "-J-Xmx512m", "-d", "/work", "/source/Main.java"],
    "TYPESCRIPT": ["node", "/opt/typescript/node_modules/typescript/bin/tsc", "/source/main.ts",
                   "--target", "ES2022", "--module", "commonjs", "--types", "node", "--typeRoots",
                   "/opt/typescript/node_modules/@types", "--outDir", "/work", "--noEmitOnError", "--skipLibCheck"],
    "CSHARP": ["dotnet", "build", "/opt/csharp/Main.csproj", "--configfile", "/opt/csharp/NuGet.Config",
               "-o", "/work", "-p:BaseIntermediateOutputPath=/tmp/obj/", "-p:UseSharedCompilation=false",
               "--disable-build-servers", "-v:q"],
    "GO": ["go", "build", "-o", "/work/main", "/source/main.go"],
    "RUST": ["rustc", "--edition=2021", "-O", "/source/main.rs", "-o", "/work/main"],
    "KOTLIN": ["/opt/kotlinc/bin/kotlinc", "-J-Xmx512m", "/source/Main.kt", "-include-runtime", "-d", "/work/main.jar"],
    "LLVM_IR": ["clang-18", "-O2", "/source/main.ll", "-o", "/work/main"],
    "PASCAL": ["fpc", "-Mobjfpc", "-O2", "-FE/work", "-FU/work", "-o/work/main", "/source/main.pas"],
    "DART": ["dart", "compile", "exe", "/source/main.dart", "-o", "/work/main"],
}
runners = {
    "PYTHON": ["python3", "-I", "/source/main.py"],
    "C": ["/work/main"], "CPP": ["/work/main"],
    "JAVA": java + ["-cp", "/work", "Main"],
    "JAVASCRIPT": node + ["/source/main.js"],
    "TYPESCRIPT": node + ["/work/main.js"],
    "CSHARP": ["dotnet", "/work/Main.dll"],
    "GO": ["/work/main"], "RUST": ["/work/main"],
    "KOTLIN": java + ["-jar", "/work/main.jar"],
    "RUBY": ["ruby", "--disable-gems", "/source/main.rb"],
    "LLVM_IR": ["/work/main"],
    "PASCAL": ["/work/main"],
    "PHP": php + ["/source/main.php"],
    "DART": ["/work/main"],
    "UMJUNSIK": ["python3", "-I", "-B", "/opt/judge/umjunsik_cli.py", "/source/main.umm"],
}

function_compilers = {
    "C": ["gcc", "-std=c17", "-O2", "/source/main.c", "/opt/judge/function/c_harness.c", "-o", "/work/main"],
    "CPP": ["g++", "-std=c++20", "-O2", "/source/main.cpp", "/opt/judge/function/cpp_harness.cpp", "-o", "/work/main"],
    "JAVA": ["javac", "-J-Xmx512m", "-d", "/work", "/source/Main.java", "/opt/judge/function/JavaHarness.java"],
    "TYPESCRIPT": compilers["TYPESCRIPT"],
    "CSHARP": ["dotnet", "build", "/opt/judge/function/Function.csproj", "--configfile", "/opt/csharp/NuGet.Config",
               "-o", "/work", "-p:BaseIntermediateOutputPath=/tmp/obj/", "-p:UseSharedCompilation=false",
               "--disable-build-servers", "-v:q"],
    "GO": ["go", "build", "-o", "/work/main", "/work/main.go", "/work/go_harness.go"],
    "RUST": ["rustc", "--edition=2021", "-O", "/opt/judge/function/rust_harness.rs", "-o", "/work/main"],
    "KOTLIN": ["/opt/kotlinc/bin/kotlinc", "-J-Xmx512m", "/source/Main.kt",
               "/opt/judge/function/KotlinHarness.kt", "-include-runtime", "-d", "/work/main.jar"],
    "LLVM_IR": ["clang-18", "-O2", "/source/main.ll", "/opt/judge/function/llvm_harness.c", "-o", "/work/main"],
    "PASCAL": ["fpc", "-Mobjfpc", "-O2", "-FE/work", "-FU/work", "-o/work/main",
               "/opt/judge/function/pascal_harness.pas"],
    "DART": ["dart", "compile", "exe", "/opt/judge/function/dart_harness.dart", "-o", "/work/main"],
}
function_runners = {
    **runners,
    "JAVA": java + ["-cp", "/work", "JavaHarness"],
    "JAVASCRIPT": node + ["/opt/judge/function/js_harness.js"],
    "TYPESCRIPT": node + ["/opt/judge/function/ts_harness.js"],
    "CSHARP": ["dotnet", "/work/Function.dll"],
    "RUBY": ["ruby", "--disable-gems", "/opt/judge/function/ruby_harness.rb"],
    "PHP": php + ["/opt/judge/function/php_harness.php"],
}

def function_input(args):
    if not isinstance(args, dict):
        raise ValueError("Invalid FUNCTION test input")
    matrix, target = args.get("matrix"), args.get("target")
    if (not isinstance(matrix, list) or len(matrix) != 15 or
            any(not isinstance(row, list) or len(row) != 15 or
                any(type(value) is not int or value not in (0, 1) for value in row)
                for row in matrix) or
            not isinstance(target, list) or len(target) != 2 or
            any(type(value) is not int or not 0 <= value < 15 for value in target)):
        raise ValueError("Invalid FUNCTION test input")
    return matrix, target

generic_signature = None
if mode == "FUNCTION" and os.path.isfile("/source/signature.json"):
    with open("/source/signature.json") as signature_file:
        generic_signature = generic_function.signature(generic_function.load_json(signature_file))

if phase == "compile" and generic_signature is not None:
    command = generic_function.compile_command(language, generic_signature)
elif phase == "compile":
    if mode == "FUNCTION":
        if language == "GO":
            shutil.copyfile("/source/main.go", "/work/main.go")
            shutil.copyfile("/opt/judge/function/go_harness.go", "/work/go_harness.go")
        command = function_compilers[language]
    else:
        command = compilers[language]
elif phase != "run":
    raise ValueError("Unknown phase")
elif generic_signature is not None:
    generic_function.run(language, generic_signature, memory, java, node, php)
    sys.exit(0)
elif mode == "FUNCTION" and language == "PYTHON":
    args = json.load(sys.stdin)
    # V6 이전 Python 전용 FUNCTION 문제의 비정형 테스트는 계속 실행할 수 있습니다.
    scope = {"__name__": "__submission__"}
    with contextlib.redirect_stdout(sys.stderr):
        exec(compile(open("/source/main.py").read(), "main.py", "exec"), scope)
        value = scope["sol"](args["matrix"], tuple(args["target"]))
    if type(value) is not bool:
        raise TypeError("sol must return bool")
    print(json.dumps(value))
    sys.exit(0)
elif mode == "FUNCTION" and language not in ("SQL", "UMJUNSIK"):
    args = json.load(sys.stdin)
    matrix, target = function_input(args)
    # 고정 어댑터는 모두 행 우선 225개 원소와 목표 행·열을 공백 구분 정수로 받습니다.
    payload = " ".join(map(str, [v for row in matrix for v in row] + target)).encode() + b"\n"
    read_fd, write_fd = os.pipe()
    try:
        os.write(write_fd, payload)
    finally:
        os.close(write_fd)
    os.dup2(read_fd, 0)
    os.close(read_fd)
    command = function_runners[language]
elif mode == "SQL" and language == "SQL":
    # 케이스 입력은 초기 스키마/데이터 SQL입니다. 서비스 MySQL에는 절대 연결하지 않습니다.
    connection = sqlite3.connect(":memory:")
    def setup_authorizer(action, arg1, arg2, database, trigger):
        if action in (sqlite3.SQLITE_ATTACH, sqlite3.SQLITE_DETACH):
            return sqlite3.SQLITE_DENY
        if action == sqlite3.SQLITE_FUNCTION and (arg2 or "").lower() == "load_extension":
            return sqlite3.SQLITE_DENY
        return sqlite3.SQLITE_OK
    connection.set_authorizer(setup_authorizer)
    connection.executescript(sys.stdin.read())
    allowed = {sqlite3.SQLITE_SELECT, sqlite3.SQLITE_READ, sqlite3.SQLITE_FUNCTION, sqlite3.SQLITE_RECURSIVE}
    def query_authorizer(action, arg1, arg2, database, trigger):
        if action not in allowed:
            return sqlite3.SQLITE_DENY
        return setup_authorizer(action, arg1, arg2, database, trigger)
    connection.set_authorizer(query_authorizer)
    # 단일 읽기 쿼리만 허용합니다. .shell/.read 같은 sqlite CLI 명령도 사용할 수 없습니다.
    rows = connection.execute(open("/source/query.sql").read())
    writer = csv.writer(sys.stdout, delimiter="\t", lineterminator="\n")
    for row in rows:
        writer.writerow(["NULL" if value is None else value for value in row])
    sys.exit(0)
elif mode == "STDIN" and language != "SQL":
    command = runners[language]
else:
    raise ValueError("Unsupported language/mode")

os.execvp(command[0], command)
