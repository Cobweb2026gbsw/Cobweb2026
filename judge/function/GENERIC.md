# 범용 FUNCTION 어댑터

`/source/signature.json`이 있는 FUNCTION 제출에만 적용합니다. 없는 제출의 기존 체인
레이저 ABI와 STDIN/SQL은 그대로 유지합니다. JSON `{ "args": [...] }`를 컨테이너 안에서
검증한 뒤 각 언어의 함수에 전달합니다. 호스트에서는 제출이나 생성 래퍼를 실행하지 않습니다.

형식은 `int`, `float`, `bool`, `string`, `int[]`, `string[]`, `int[][]`, `string[][]`입니다. 정수는 signed 32-bit,
실수는 finite double, 문자열은 ASCII이며 NUL·공백·따옴표·개행을 보존합니다. 배열은 최대
1,000,000칸, 문자열 하나는 최대 16384바이트입니다. **한 입력의 모든 인수에서 사용한 배열 칸 수의 합**이
1,000,000 이하여야 합니다. 중첩 배열의 바깥 행과 안쪽 원소를 모두 셉니다. 따라서 1차원 정수 배열
100만 개는 허용하지만, 그 배열에 바깥 행 1개를 더한 `[[...100만개]]`는 초과입니다. 결과에도 같은
합산 한도를 적용합니다. 빈 바깥 배열, 빈 행, 길이가 서로 다른 행을 허용합니다. 3차원 배열은 지원하지 않습니다.
함수 이름은 공개 서명의 `sol` 또는 `solution`, 매개변수는 최대 6개입니다.

정식 채점의 입력/예상 출력/실제 출력 JSON은 각각 UTF-8 16 MiB 이하, 내부 숫자 스트림은 64 MiB
이하입니다. 이 한도는 문제별 더 작은 제한을 대체하지 않습니다. 화면의 직접 테스트 입력은 별도의
64 KiB 제한을 유지합니다. 실행 오류 로그 제한도 별도이며, 대용량 기능이 로그 공개를 허용하지 않습니다.
이미 ASCII로 검증한 결과는 불필요한 유니코드 이스케이프를 추가하지 않습니다. 따라서 JSON에서
허용되는 DEL(0x7f)도 한 바이트로 보존합니다. NUL 등 JSON이 필수로 이스케이프하는 제어 문자는
여전히 올바르게 이스케이프하며, JSON의 UTF-8 바이트 수를 기준으로 출력 상한을 검사합니다.

| 언어 | 함수/형식 |
| --- | --- |
| Python | 최상위 함수, int/float/bool/str/list |
| JavaScript/TypeScript | 최상위 함수, number/boolean/string/number[]/string[] (TS export 선택) |
| C++ | 최상위 함수, int/double/bool/std::string/std::vector<int>/std::vector<std::string> |
| Java | Main의 public static 함수, int/double/boolean/String/int[]/String[] |
| C# | Solution의 public static 함수, int/double/bool/string/int[]/string[] |
| Go | package main, 최상위 함수, int/float64/bool/string/[]int/[]string |
| Rust | 최상위 함수, i32/f64/bool/String/Vec<i32>/Vec<String> (소유값) |
| Kotlin | 최상위 함수, Int/Double/Boolean/String/IntArray/Array<String> |
| Ruby/PHP | 최상위 함수, 각 언어의 정수/실수/불리언/문자열/배열 |
| Dart | 최상위 함수, int/double/bool/String/List<int>/List<String> |
| Pascal | 함수, LongInt/Double/Boolean/AnsiString/TIntArray/TStringArray |

| 언어 | int[][] | string[][] |
| --- | --- | --- |
| Python | list[list[int]] | list[list[str]] |
| JavaScript/TypeScript | number[][] | string[][] |
| C++ | std::vector<std::vector<int>> | std::vector<std::vector<std::string>> |
| Java | int[][] | String[][] |
| C# | int[][] | string[][] |
| Go | [][]int | [][]string |
| Rust | Vec<Vec<i32>> | Vec<Vec<String>> |
| Kotlin | Array<IntArray> | Array<Array<String>> |
| Ruby/PHP | 중첩 배열 | 중첩 배열 |
| Dart | List<List<int>> | List<List<String>> |
| Pascal | TIntMatrix | TStringMatrix |

Pascal은 래퍼가 `TIntArray = array of LongInt`, `TStringArray = array of AnsiString`,
`TIntMatrix = array of TIntArray`, `TStringMatrix = array of TStringArray`를
선언하고 제출을 include합니다. 함수는 관리되는 동적 배열/문자열을 값으로 반환합니다.

C와 LLVM IR은 각 string/array 인수를 **포인터 다음 signed 32-bit 길이**로 받습니다.
string은 `char *`, int[]는 `int *`, string[]는 `CWString *`입니다. 래퍼가 C 소스보다 먼저
`typedef struct { char *data; int length; } CWString;`을 선언합니다(제출에서 재선언하지 않습니다).
LLVM의 대응 레이아웃은 `{ ptr, i32 }`입니다. 스칼라는 C의 int/double/bool,
LLVM의 i32/double/i1입니다.

중첩 배열은 행 포인터·행 길이 구조체 배열로 전달합니다. 래퍼가 다음 구조체도 선언합니다.
`int[][]` 인수·결과는 `CWIntArray *`, `string[][]`는 `CWStringArray *`이며, 그 다음 길이는
행 개수입니다. 각 행의 길이를 따로 보존하므로 빈 행과 비정형 배열도 손실이 없습니다.
LLVM에서는 각 행 구조체가 `{ ptr, i32 }`, 문자열 행의 data 포인터가 가리키는 각 문자열도
`{ ptr, i32 }`입니다. 구조체 패딩/정렬은 대상 플랫폼의 C ABI와 일치해야 합니다.

```c
typedef struct { int *data; int length; } CWIntArray;
typedef struct { CWString *data; int length; } CWStringArray;
// 위 typedef는 채점기가 제공하므로 제출 소스에서 재선언하지 않습니다.
CWIntArray *sol(CWIntArray *matrix, int matrix_length, int *result_length) {
    *result_length = matrix_length;
    return matrix;
}
```

string/array 결과는 포인터를 반환하고 **마지막 인수 `int *result_length`**에 길이를 씁니다.
입력 메모리는 래퍼 소유이며, 입력을 그대로 반환해도 됩니다. 새 결과는 static 또는 heap 등
함수 종료 후에도 유효한 메모리여야 합니다. 래퍼는 반환 메모리를 free하지 않으며 케이스별
프로세스 종료 시 회수됩니다. string[] 결과의 각 CWString에도 정확한 바이트 길이를 씁니다.

```c
char *sol(char *s, int s_length, int *result_length) {
    *result_length = s_length;
    return s;
}
```

```llvm
define ptr @sol(ptr %s, i32 %s_length, ptr %result_length) {
  store i32 %s_length, ptr %result_length
  ret ptr %s
}
```

컴파일용 래퍼는 제한된 `/work` tmpfs에 생성되고 실행 때에는 읽기 전용입니다. 해석 언어
래퍼는 케이스별 제한된 `/tmp`에 생성됩니다. JSON 파서는 고정 Python 실행기에만 있으며,
언어 간 내부 스트림은 숫자 토큰입니다. 문자열은 길이와 ASCII 코드들, 배열은 개수와
원소를 순서대로 전달하며 중첩 행도 같은 규칙을 재귀적으로 적용합니다. 결과는 검증 후 JSON으로
출력되어 호스트가 구조적으로 비교합니다. 대용량 입출력 때문에 Java Scanner/큰 배열 spread 호출을
사용하지 않으며 출력은 작은 버퍼로 나누어 기록합니다. Java/C#은 64 KiB 입력 버퍼를 사용하며,
Node 출력은 역압을 무시한 비동기 큐에 문자열 로프를 쌓지 않도록 동기 버퍼 쓰기를 사용합니다.
숫자 스트림도 전체 토큰 배열로 분할하지 않고
커서/반복자로 읽습니다. 예를 들어 16 MiB 안의 문자열 배열이 약 50 MiB 숫자 스트림으로 확장되어도
문자 코드마다 별도 객체 수천만 개를 보관하지 않습니다. 제출의 알고리즘·메모리 사용은 여전히
문제 시간/메모리 제한과 Docker 제한을 지켜야 합니다.

검증 명령(실제 Docker 테스트는 이미지를 먼저 빌드해야 합니다):

```sh
python3 -I -B -m unittest discover -s src/test/python -p 'test_generic_function.py'
DOCKER_JUDGE_TEST=true GENERIC_JUDGE_IMAGE=cobweb-languages:local bash gradlew test --tests '*GenericFunctionJudgeTest'
GENERIC_CATALOG_DOCKER_TEST=true GENERIC_JUDGE_IMAGE=cobweb-languages:local bash gradlew test --tests '*GenericLevel0CatalogJudgeTest'
GENERIC_NESTED_DOCKER_TEST=true GENERIC_JUDGE_IMAGE=cobweb-languages:local python3 -I -B -m unittest discover -s src/test/python -p 'test_generic_nested_docker.py' -v
# 선택: z문자열 1023개×16384바이트, 64MiB에 가까운 내부 스트림의 15언어 실제 실행
# 일반 60개는 환경변수로 10초, 문자열 스트레스 15개는 별도 45초입니다(문제 시간 제한을 바꾸지 않습니다).
GENERIC_NESTED_DOCKER_TEST=true GENERIC_WORST_WIRE_TEST=true GENERIC_WIRE_STRESS_CHAR=z GENERIC_RUNTIME_TIMEOUT_SECONDS=10 GENERIC_JUDGE_IMAGE=cobweb-languages:local python3 -I -B -m unittest discover -s src/test/python -p 'test_generic_nested_docker.py' -v
```

기존 Docker 묶음은 15언어×6자료형의 실제 반환/재사용 및 화면 시작 코드, `solution`/인자 없는 함수,
잘못된 입력·동적 반환형·여러 인자를 검증합니다. 중첩 배열과 대용량에 대한 결과는 실제 실행한
테스트 보고서에서 별도로 확인해야 합니다. 단위 테스트만으로 15개 도구체인 실행을 증명하지 않습니다.
기존 카탈로그 묶음은 100문제의 Python 참고 풀이를 문제별 한 번 준비하여 저장된 812개 케이스를
컨테이너에서 실행합니다.
