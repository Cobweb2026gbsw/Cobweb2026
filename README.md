# Cobweb

Java 21 / Spring Boot / MySQL 8.4 기반 알고리즘 문제 풀이 MVP입니다.
회원 인증, 문제 작성·검수·조회, 13개 언어 제출, DB 채점 대기열, Docker 실행,
결과·해결 수·랭킹·프로필, 문제 제작·관리 화면, 그룹·시간제 코딩 테스트,
커뮤니티·공지와 관리자 전용 수정 이력을 포함합니다.

## 개발 실행

1. Java 21과 Docker를 준비하고 `.env.example`을 참고해 `.env`에 값을 설정합니다.
   `JWT_SECRET`은 최소 32바이트 임의 비밀값입니다. 메일과 OAuth는 실제 제공자 설정이 필요합니다.
2. `DEV_DB_PASSWORD`, `DEV_ROOT_PASSWORD`를 설정합니다.
3. `docker compose -f compose.dev.yaml up -d`
4. `docker build -t cobweb-languages:local judge` (첫 빌드는 여러 컴파일러를 내려받으므로 시간/디스크가 필요합니다.)
5. `JUDGE_ENABLED=true ./gradlew bootRun --args='--spring.profiles.active=local --demo.seed=true'`
6. http://localhost:8080/ 에서 문제 조회·로그인·제출을 사용할 수 있습니다.

화면은 `src/main/resources/static/index.html` 하나로 관리합니다. 기존 `/app.html` 주소도
동일한 화면으로 연결됩니다. 구현 현황은 [PROGRESS.md](PROGRESS.md), 확정 보안 검토는
[보안 검토 기록](docs/security/REVIEW.md)을 참고하세요.
전체 API의 요청·응답·권한, DB와 채점 동작은 [백엔드 구현 명세](docs/BACKEND_SPEC.md)에 정리했습니다.

local 프로필은 3307 포트의 새 DB를 사용하고 Flyway V1–V13을 적용합니다.
데모 옵션은 1000번이 없을 때만 체인 레이저와 비공개 10개 테스트를 추가합니다.
작성자는 로그인할 수 없는 `cobweb-system` 계정입니다. 기존 1000번을 덮어쓰지 않습니다.
정답 코드와 테스트 입력은 공개 API로 반환하지 않습니다.

## 테스트

별도 테스트 DB `cobweb_test`를 준비합니다. 기존 개발 DB를 테스트 대상으로 지정하지 마세요.

```sh
docker run -d --name cobweb-mvp-test-mysql \
  -p 127.0.0.1:13307:3306 \
  -e MYSQL_ROOT_PASSWORD=cobweb-test-only \
  -e MYSQL_DATABASE=cobweb_test mysql:8.4
docker build -t cobweb-languages:local judge
DOCKER_JUDGE_TEST=true ./gradlew build
```

이미 해당 테스트 컨테이너가 있으면 `docker start cobweb-mvp-test-mysql`을 사용합니다.
다른 테스트 DB는 `TEST_DB_URL`, `TEST_DB_USERNAME`, `TEST_DB_PASSWORD`로 지정합니다.
Docker 테스트는 `DOCKER_JUDGE_TEST=true`일 때 실제 컨테이너를 사용합니다.
빌드 보고서는 `build/reports/tests/test/index.html`에 생성됩니다.
브라우저 인증 흐름의 가벼운 회귀 검사는 `node src/test/js/app-auth.test.cjs`로 실행합니다.

### CI와 같은 로컬 검증

`bash scripts/verify-ci-local.sh static`은 Node 인증·세션 경합·문제 편집·그룹·커뮤니티 회귀와 개발·웹·워커 Compose 설정을 검사합니다.
`quick`은 여기에 MySQL 연동 테스트를 추가하고, `full`은 채점 이미지를 빌드한 뒤 Docker를 포함한
전체 빌드와 결과 검사를 실행합니다. `all`은 두 경로를 순서대로 실행합니다. 이 스크립트는
`.env` 대신 전용 로컬 테스트 DB와 테스트용 값만 사용합니다. 기본은 `127.0.0.1:13307/cobweb_test`이며,
`TEST_DB_URL=jdbc:mysql://127.0.0.1:13308/cobweb_feature_test`도 명시적으로 지정할 수 있습니다.
재검증용 새 DB `jdbc:mysql://127.0.0.1:13309/cobweb_revalidation_test`도 허용합니다.
기존 테스트 DB에 중복 제목이 있으면 V10이 중단합니다. 기존 데이터를 임의로 고치지 말고 새 테스트 DB를 사용하세요.

별도 웹/자동 워커의 실제 HTTP 연동 검사는 `python3 scripts/verify-local-linkage.py`입니다.
이 스크립트는 서버나 DB를 만들지 않습니다. 전용 컨테이너 `cobweb-feature-verify-mysql`의
`cobweb_linkage_test`에 V1–V13과 1000번 시드를 먼저 적용하고, 웹을 `127.0.0.1:18083`(채점 OFF),
동일 DB의 별도 워커를 non-web·채점 ON·Flyway OFF로 실행해야 합니다. JAR에는 test 프로필 설정이 포함되지 않으므로
`.env` 없는 임시 실행 디렉터리와 테스트 DB/JWT/메일/OAuth 값을 명시합니다. 실제 외부 연동은 사용하지 않습니다.
검증 계정의 임시 해시는 앞서 전체 테스트를 실행한 `cobweb_revalidation_test`에서 복사하며 실제 사용자 계정은 쓰지 않습니다.
앱 URL/컨테이너/DB는 고정 검사하고 프록시·리다이렉트를 차단합니다. 테스트 시간창만 SQL로 조정하고,
나머지 제작/공개/커뮤니티·그룹/시험·답안/다시보기는 HTTP로 검사합니다. 콘텐츠·이력·DB는 삭제하지 않습니다.
2026-10-13 최종 로컬 결과는 전체 172개 및 HTTP 경로 13개 통과이며 자세한 증거와 경계는 `PROGRESS.md`를 참고하세요.

`.github/workflows/ci.yml`은 push·PR마다 빠른 검사와 13언어 Docker 전체 검사를 실행하도록
구성되어 있습니다. Docker 테스트 보고서가 없거나 언어별·FUNCTION·HTTP 워커 테스트가
건너뛰어지면 전체 검사도 실패합니다. 결과와 로그는 7일 보관합니다. 실제 GitHub 실행과
보호 브랜치의 필수 상태 검사 설정은 저장소에서 확인해야 합니다.

## API

기존 인증 API는 `/api/auth`, 이메일 인증은 `/api/email-verifications`입니다.
새 API 응답은 기존과 동일하게 `{success, data, message}` 형식을 사용합니다.

회원가입 `POST /api/auth/signup`에는 `username`, `email`, `password`와
`verificationCode`(앞서 JOIN 인증을 확인한 숫자 8자리 코드)가 필요합니다.
인증 완료 상태만 공유해서 다른 사람이 가입하는 것을 막기 위해 가입 시에도 코드를 확인합니다.
이메일 중복 409도 유효한 코드를 확인한 뒤에만 반환합니다.
코드가 만료됐다면 새 코드를 발급받아 다시 인증해야 합니다.

| 경로 | 기능 | 권한 |
|---|---|---|
| GET /api/languages | 지원 언어 ID·이름·허용 실행 방식 | 공개 |
| GET /api/problems?page=0&search=제목 | 공개 문제 목록 (30개) | 공개 |
| GET /api/problems/{번호} | 본문·예제 | 공개 문제/작성자/운영자/소속 그룹 |
| POST /api/problems | 초안 생성, 번호 자동 발급 | 로그인 |
| PUT /api/problems/{번호} | 수정 가능한 초안·비공개/보관 문제 편집 | 작성자/운영자/그룹 편집 권한 |
| POST /api/problems/{번호}/review-request | 테스트·정답 확인 후 검수 요청 | 작성자/운영자/그룹 제작·편집 권한 |
| POST /api/problems/{번호}/review | 승인·반려·보관 | OPERATOR/DEVELOPER 또는 그룹 검수 권한 |
| POST /api/submissions | 언어 선택 및 코드 제출 | 로그인 |
| GET /api/submissions | 일반 내 제출 50개 (시험 제외) | 로그인 |
| GET /api/submissions/{id} | 제출 판정·케이스 결과, 시험은 다시보기 검사 | 제출자/운영자, 시험 결과 권한 |
| GET /api/ranking?page=0 | 해결 수/제출 수 순 랭킹 | 공개 |
| GET /api/profiles/{username} | 공개 프로필과 공개 해결 문제 | 공개 |
| GET /api/me | 내 프로필 | 로그인 |
| PATCH /api/me | bio, githubUrl 수정 | 로그인 |

제출 요청 예: `{"problemNumber":1000,"language":"PYTHON","sourceCode":"def sol(matrix, target): ..."}`.
`language`를 생략한 이전 클라이언트는 Python으로 처리합니다. 알 수 없는 ID나 문제 형식과 맞지 않는
언어는 400으로 거절하며, 문제 상세의 `languages`에 실제 허용 ID가 내려옵니다.
제출 목록/상세에도 `language`를 반환합니다. 컴파일 실패는 `COMPILATION_ERROR`로 표시합니다.
문제 작성 필드는 `ProblemRequests.Write`, 검수는 `{"status":"PUBLISHED","cause":"검수 완료"}`입니다.
초안 → 검수 요청 → 공개/반려 흐름입니다. 일반 공개 문제는 운영자가 먼저 비공개로,
그룹 문제는 먼저 보관 상태로 전환해야 수정할 수 있습니다. 시험에 연결된 문제는 원본 보존을 위해 수정할 수 없습니다.

### 새 사용자 화면과 관리 기능

- `/create`: 예제·비공개 테스트 추가/삭제, 언어·태그·출처 선택, 정답 코드 붙여넣기 또는 UTF-8 파일 첨부, 초안 편집·검수 요청.
  정답 파일은 언어별 하나, 최대 64KiB입니다. 초안에는 정답/테스트가 없어도 되지만 게시 전에는 필요합니다.
  제목은 공백 정규화 및 DB unique 제약으로 일반 문제 전체 또는 각 그룹 안에서 중복을 금지합니다.
- `/admin`: 운영자 문제 목록·검색·검수·공개/비공개 전환. 그룹 문제는 전체 공개로 전환하지 않습니다.
- `/groups`: 50문제 이상 해결한 회원이 생성하며 소유 그룹 포함 동시 가입 3개까지입니다.
  기본 역할 5개+커스텀 최대 3개, 기능별 권한 11개를 설정합니다. 소유자 역할은 잠금 방지용으로 고정합니다.
  그룹 문제는 그룹 검색에서만 보이며, 검수 권한자는 그룹 관리 목록에서 승인·반려합니다.
- 그룹 코테: 서버 UTC 시작/종료 시각, 문제 선택, 별도 시험 답안과 실시간 결과 새로고침,
  관리자 다시보기 허용 옵션. 시험 답안은 일반 해결 수·제출 수·랭킹에 합산하지 않습니다.
  종료 후 다시보기는 응시 기록이 있는 회원에게만 허용합니다. 시작 후 문제·시간 변경은 금지합니다.
- `/community`, `/notices`: 하드웨어·소프트웨어·정보·문제 질문·전공 질문·일반 게시판과 운영자 공지.
  댓글→대댓글→대대댓글까지 지원하고 부모별 20개 더보기로 읽습니다.
  작성자/운영자가 수정·삭제하며 삭제는 soft delete입니다. 원문, 이전/이후 버전, 수정자,
  문제 연결 스냅샷은 DB에 보존하고 운영자만 이력 조회·복원할 수 있습니다.

코테 감시 옵션은 브라우저 내 복사·인쇄 억제, 전체화면 복귀 요구, 창 전환 신호 기록입니다.
운영체제 캡처와 다른 기기 사용을 완전히 차단할 수 없으며, 이벤트만으로 부정행위를 확정하지 않습니다.
현재 편집 화면은 공개 예제 1개와 테스트 1개를 입력받으며 API는 여러 개를 지원합니다.

## 지원 언어

초기 언어 구성은 2026-09-17, 보안 패치와 Go 갱신은 2026-10-02 ARM64 Docker 이미지에서 확인했습니다. OS 패키지 및 이미지 태그 재빌드 시
패치 버전은 달라질 수 있습니다. 운영에서는 검증한 이미지 digest와 워커 CPU 아키텍처를 통일하세요.

| API ID | 언어·도구 체인 | 파일 / 진입점 |
|---|---|---|
| PYTHON | Python 3.12.3 | main.py |
| C | GCC 13.3 / C17 | main.c / main |
| CPP | GCC 13.3 / C++20 | main.cpp / main |
| JAVA | OpenJDK 21.0.12 | Main.java / public class Main |
| JAVASCRIPT | Node.js 24.21.0 | main.js / CommonJS |
| TYPESCRIPT | TypeScript 5.9.3 + Node.js 24 | main.ts → CommonJS, Node 타입 제공 |
| CSHARP | .NET SDK 10.0.401 / net10.0 | Main.cs / Main 또는 최상위 문장 |
| GO | Go 1.26.8 | main.go / package main |
| RUST | rustc 1.75.0 / edition 2021 | main.rs / fn main |
| KOTLIN | Kotlin 2.2.21 / JVM 21 | Main.kt / fun main |
| SQL | SQLite 3.45.1 | query.sql / 단일 읽기 쿼리 |
| RUBY | Ruby 3.2.3 | main.rb |
| LLVM_IR | Clang/LLVM 18.1.3 | main.ll / main 진입점 |

현재 단일 소스 파일과 설치된 표준 라이브러리만 지원합니다. 제출 중 npm/NuGet/Go module/Cargo
다운로드, 사용자 빌드 스크립트·컴파일 옵션·다중 파일 업로드는 허용하지 않습니다.
언어를 추가하려면 Java 허용 목록, 고정 실행기, 이미지, 실제 Docker 회귀 테스트를 함께 변경해야 합니다.

## 채점 규칙

- STDIN: SQL 이외 12개 언어. 표준 입력을 읽고 표준 출력으로 답을 반환합니다.
- FUNCTION: 현재 고정 입력 규격에 맞는 문제는 SQL을 제외한 12개 언어에서 `sol` 함수를 제출할 수 있습니다.
  1000번 체인 레이저도 같은 언어 목록을 허용합니다. 기존 비정형 함수형 문제는 데이터 수정 없이
  Python만 노출합니다. 제출 파일에 `main`을 작성하지 말고 아래 고정 시그니처를 사용하세요.
- SQL: SQLite만 허용합니다. 테스트 입력은 `CREATE TABLE`/`INSERT` 초기화 SQL이고 제출은
  `SELECT`/`WITH` 등 단일 읽기 쿼리입니다. 매 케이스마다 새 메모리 DB를 만들어 원래 서비스 DB와 격리합니다.
  결과는 헤더 없는 TSV, NULL은 `NULL`, 개행은 LF입니다. 탭/개행/따옴표가 든 값은 CSV 방식으로 인용합니다.
  순서가 중요한 문제는 `ORDER BY`를 명시하세요. 쓰기/ATTACH/확장 로딩/CLI 명령은 거절합니다.
- 새 FUNCTION 문제의 각 테스트 입력은 `{"matrix":[...],"target":[행,열]}`이며 `matrix`는
  0/1 값으로 이뤄진 정확한 15×15 배열, target의 두 좌표는 각각 0–14여야 합니다. 정답은 정확히
  JSON `true` 또는 `false`입니다. 생성·수정 시 규격 위반 테스트는 400으로 거절합니다.
- 현재 비교는 양 끝 공백을 제거한 문자열 일치입니다. EFFICIENCY/사용자 지정 검사기는 아직 지원하지 않습니다.

함수형 테스트 입력은 기존 JSON 형식을 유지합니다. 고정 실행기가 행렬과 목표 좌표를 각 언어의
자료형으로 변환한 뒤 함수를 한 번 호출하며, 결과는 반드시 불리언이어야 합니다. JavaScript는
함수 선언 또는 CommonJS export를, TypeScript는 `export function sol`을 사용합니다.

| 언어 | FUNCTION 제출 시그니처 |
|---|---|
| Python | `def sol(matrix, target) -> bool` (`target`은 tuple) |
| C | `bool sol(const int matrix[15][15], int target_row, int target_col)` |
| C++ | `bool sol(const std::vector<std::vector<int>>&, std::pair<int,int>)` |
| Java | `Main` 클래스의 `public static boolean sol(int[][], int[])` |
| JavaScript | `function sol(matrix, target)` 또는 CommonJS export |
| TypeScript | `export function sol(matrix: number[][], target: [number,number]): boolean` |
| C# | `Solution` 클래스의 `public static bool Sol(int[][], int[])` |
| Go | `package main`의 `func sol(matrix [][]int, target [2]int) bool` |
| Rust | `fn sol(matrix: Vec<Vec<i32>>, target: (usize,usize)) -> bool` |
| Kotlin | `fun sol(matrix: Array<IntArray>, target: Pair<Int,Int>): Boolean` |
| Ruby | `def sol(matrix, target)` |
| LLVM IR | `define i1 @sol(ptr %matrix, i32 %row, i32 %col)` (`ptr`: 행 우선 `i32[225]`) |

SQL은 함수형 풀이가 아니라 SQL 전용 문제에서만 지원합니다. 함수형 어댑터는 단일 파일을 고정된
컴파일·실행 명령으로 처리하며 제출자가 빌드 옵션이나 파일 경로를 선택할 수 없습니다.
- Docker 네트워크 없음, 읽기 전용 루트, 권한 제거, 비특권 사용자, 메모리·CPU·프로세스·출력 제한을 적용합니다.
- 컴파일은 제출당 한 번, 최대 CPU 60초/벽시계 65초/메모리 1GiB를 사용합니다.
  컴파일 실패·컴파일 제한 초과는 COMPILATION_ERROR, Docker 준비 실패는 SYSTEM_ERROR입니다.
- 산출물은 256MiB tmpfs 볼륨에만 저장하고 매 케이스의 새 컨테이너에는 읽기 전용으로 제공합니다.
  컴파일 임시 공간은 256MiB, 실행 임시 공간은 32MiB이며 둘 다 noexec/nosuid입니다.
  컨테이너당 프로세스/스레드 제한은 JVM/.NET을 고려해 128개, 출력 저장량은 스트림당 64KiB입니다.
  정답은 어떤 컨테이너에도 전달하지 않습니다. 메모리 제한에는 런타임 자체의 메모리도 포함됩니다.
- 정상 종료 시 컴파일 컨테이너/볼륨/소스를 정리합니다. 워커 비정상 종료 시 유지 컨테이너는
  30분 후 종료하지만 볼륨 메타데이터·로컬 소스가 남을 수 있으므로 운영 정리 정책이 필요합니다.
- 실제 실행은 컨테이너 내부에서 문제의 밀리초 벽시계 제한으로 종료하며 CPU 제한도 보조로
  적용합니다. 결과의 `execution_time_ms`는 Docker 기동 비용을 포함하므로 사용자 코드만의
  정밀 실행 시간으로 해석하면 안 됩니다.
- 메모리 초과/종료 신호의 세부 분류와 실제 최대 메모리 측정은 향후 개선 대상입니다.
- 활성 워커당 동시 제출 1개, 사용자당 대기/진행 최대 5개입니다.
- 채점 중 별도 스레드에서 30초마다 토큰 조건부 heartbeat를 갱신합니다. heartbeat가 20분 이상
  갱신되지 않은 작업은 다음 claim에서 재큐잉하며 총 3회 실행도 중단되면 SYSTEM_ERROR로 마무리합니다.
  재할당 때 새 토큰을 발급해 이전 워커의 결과와 통계 반영을 막습니다. 정상 오답·실행 오류는
  자동 재시도하지 않습니다. heartbeat가 없는 구 작업은 claimed_at·submitted_at을 기준으로 복구합니다.
- 정답 제출 횟수는 모든 정답 제출을 세고, 사용자 해결 수는 같은 문제를 한 번만 셉니다.
- 임시 랭크 정책: 10문제당 한 단계, 50문제당 큰 등급 상승 (최고 ABSOLUTE 1).
  운영 전 최종 정책을 확정해야 합니다.

## 기존 DB 마이그레이션

새 DB의 기준은 `src/main/resources/db/migration/`입니다. 중복된 루트의 `table.sql`은
제거하고 Flyway 파일만 유지합니다. 이미 적용된 마이그레이션은 수정하지 않고 새 버전을 추가합니다.
문제 시드 역시 `src/main/resources/demo/chain-laser.sql` 한곳에서 관리합니다.
Flyway 기본값은 기존 로컬 DB 자동 변경 방지를 위해 꺼져 있고 local/prod/test에서 켜집니다.
`baseline-on-migrate`는 자동 활성화하지 않습니다.

1. 기존 DB를 백업하고 복제 DB에서 검증합니다.
2. V1의 18개 테이블·제약·인덱스를 기존 구조와 비교합니다.
3. 기존 테이블이 V1과 정확히 일치할 때만 Flyway baseline을 버전 1로 기록한 뒤 V2–V13을 적용합니다.
4. 인증 테이블 5개만 있는 구형 DB는 바로 baseline 1 처리하면 안 됩니다. 누락 테이블 생성과
   이메일 중복/토큰 중복 정리 등 별도 전환이 먼저 필요합니다.

기존 데이터 정리나 실제 DB 스키마 변경을 자동으로 수행하지 않습니다.
V3는 SQL 실행 형식/케이스 컴파일 오류 상태를 추가하고 기존 STDIN 문제에 12개 언어를 허용합니다.
V6는 기존 FUNCTION 문제에도 SQL 외 12개 언어를 허용합니다.
V4는 저장된 refresh·비밀번호 재설정 토큰을 SHA-256 지문으로 바꾸고 컬럼을 64자로 줄입니다.
V5는 access 토큰 버전과 공유 인증 시도 제한 테이블을 추가합니다. V7은 이메일 인증 코드 원문 열을
서버 키 기반 HMAC 지문 열로 바꾸며 기존 미완료 인증 코드 기록을 삭제합니다. 해당 사용자는 새 코드를
요청해야 합니다. V8은 기존 FUNCTION 테스트의 언어 어댑터 호환 여부를 표시합니다. 비호환 문제는
Python만 제출 가능하며 데이터는 삭제하지 않습니다. V5 이후의 이전 access 토큰은 버전 클레임이 없으므로 즉시 무효화됩니다.
V9는 채점 heartbeat·실행 시도 횟수를 추가하고 중단된 작업의 횟수 제한 재시도를 지원합니다.
V10은 범위별 고유 제목·공개 의도·비공개 정답 코드를, V11은 그룹 역할과 시험 답안·이벤트를,
V12는 커뮤니티·댓글과 삭제되지 않는 수정 이력을 추가합니다.
V13은 기존 제출·판정 시각 값을 유지하면서 저장 컬럼을 DATETIME(6)으로 확장해 시험의 소수초 경계와 맞춥니다.
V10 적용 전 `SELECT COALESCE(group_id,0),problem_title,COUNT(*) FROM problems GROUP BY COALESCE(group_id,0),problem_title HAVING COUNT(*)>1;`로 충돌을 확인하세요.
충돌을 자동 삭제·재명명하지 않습니다. 운영 DB 백업 후 제목 정책을 정하고 승인된 별도 전환을 해야 합니다.
기존 쿠키·재설정 링크의 원문은 새 코드에서 계속 사용할 수 있지만 이전 앱 코드는 지문을 조회할 수
없으므로, 기존 앱과 워커를 중지한 뒤 백업 → 마이그레이션 → 새 앱/워커 기동 순서로 갱신하세요.

## 배포

검증 환경에서 `DOCKER_JUDGE_TEST=true ./gradlew build`를 완료하고, 두 호스트에 동일 버전의
저장소와 빌드된 `build/libs/*-SNAPSHOT.jar`을 준비합니다. 공개 웹 호스트에서는 `.env`에
DB_PASSWORD/MYSQL_ROOT_PASSWORD/SITE_DOMAIN과 인증 환경변수를 설정한 뒤
`docker compose -f compose.prod.yaml build` 및 `docker compose -f compose.prod.yaml up -d`를 실행합니다.
Caddy가 도메인 TLS를 처리합니다. 실제 도메인의 DNS와 80/443 접근, OAuth 제공자 콘솔의 운영
콜백 URL 등록이 필요합니다. 웹 컨테이너에는 Docker 소켓을 제공하지 않습니다.

채점은 **별도 전용 호스트**에서만 `compose.worker.yaml`로 실행합니다. 먼저 웹 호스트에서
Flyway 마이그레이션을 완료하고, 워커 호스트에서 `docker build -t cobweb-languages:local judge`,
`docker compose -f compose.worker.yaml build`, `docker compose -f compose.worker.yaml up -d`를 순서대로
실행합니다. 워커 Compose에는 MySQL이나 공개 포트가 없고, 웹 앱과 **동일한 DB**를 가리키는
WORKER_DB_URL/WORKER_DB_USERNAME/WORKER_DB_PASSWORD가 필수입니다. 워커는 마이그레이션을 수행하지 않습니다.

기본 `compose.prod.yaml`의 MySQL은 호스트 포트를 열지 않으므로, `WORKER_DB_URL`만 적는다고
원격 워커가 연결되지는 않습니다. 운영자가 사설 DB 엔드포인트 또는 보호된 터널을 준비해야 합니다.
웹 호스트의 MySQL을 직접 연결할 경우에도 사설 인터페이스에만 바인드하는 운영별 Compose override와
워커 호스트 IP만 허용하는 방화벽 규칙을 별도로 적용하세요. 공용 인터페이스의 3306 포트는 열지 않습니다.
직접 JDBC 연결에는 인증서 체인과 DB 호스트 이름을 검증하는 TLS 설정(예: JDBC URL의
`sslMode=VERIFY_IDENTITY`와 신뢰할 CA)을 사용하고, 워커 전용 DB 계정에는 채점 큐·문제·결과 처리에
필요한 데이터 권한만 부여하며 DDL/관리자 권한은 주지 마세요. 연결 경로와 인증서, 방화벽, 동일 DB
접속을 실제 호스트에서 확인한 다음 워커를 기동해야 합니다. Docker 소켓은 호스트 관리자 권한에
준하므로 이 호스트에 공개 앱을 함께 두지 말고 Docker/커널 패치와 작업공간 정리·모니터링을 유지하세요.
DB 백업·복구, 관측 및 부하 시험은 운영 환경에서 추가로 준비해야 합니다.
CI의 전체 검사도 MySQL과 다중 언어 채점 이미지를 준비한 뒤 `DOCKER_JUDGE_TEST=true ./gradlew clean build`를 실행합니다.
