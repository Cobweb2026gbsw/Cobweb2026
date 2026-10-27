# Cobweb

Java 21 / Spring Boot / MySQL 8.4 기반 알고리즘 문제 풀이 MVP입니다.
회원 인증, 문제 작성·검수·조회, 17개 언어 제출, DB 채점 대기열, Docker 실행,
결과·해결 수·랭킹·프로필, 문제 제작·관리 화면, 그룹·시간제 코딩 테스트,
커뮤니티·공지와 관리자 전용 수정 이력을 포함합니다.

## 개발 실행

### 한 번에 실행하기 (권장)

Java JDK 21, Node.js 20 이상/npm과 Docker를 준비하고 Docker Desktop/Engine을 켠 뒤 실행합니다.

```sh
./run.sh
```

- 웹 서버, 별도 채점 워커, 전용 로컬 MySQL을 순서대로 실행합니다. 정상 준비 완료 후 `Ctrl+C`는 이 실행의 프로세스·채점 컨테이너·DB를
  중지하며 DB 데이터와 `.local/run/<프로젝트>/config.env`는 보존합니다. 실행용 설정을 삭제하면 기존 DB/HTTPS 비밀번호를 잃으므로 함께 백업하세요.
- 기동 도중 실패·취소하거나 준비 완료 후 웹/워커가 비정상 종료되면, 이번 실행의 프로세스를 정리하고 DB의 **시작 전 실행/정지 상태만** 복구합니다.
  이번 실행에서 처음 만든 DB 컨테이너와 비어 있는 전용 네트워크만 제거합니다. 기존 컨테이너·네트워크와 다른 프로젝트는 삭제하지 않습니다.
  **DB 내용·Flyway 변경·데이터 볼륨·설정·인증서·로그·빌드 산출물은 되돌리거나 삭제하지 않습니다.** DB 백업 복원 기능은 아닙니다.
- JAR를 실행별 `logs/<실행 ID>/app.jar`로 복사하고 SHA-256을 대조한 뒤 웹과 워커가 같은 사본을 사용합니다.
  이후 재빌드가 실행 중인 JAR를 덮지 않으며, 워커도 시작 당시의 채점 이미지 ID와 실행별 작업 디렉터리를 사용합니다.
  상태와 로그는 `.local/run/<프로젝트>/logs/<실행 ID>/`에 남습니다. 정리가 실패하면 잠금을 남기고 복구 미완료를 알립니다.
  `kill -9`, 전원 차단, Docker 자체 장애에는 자동 정리를 보장할 수 없습니다. 프로세스·컨테이너를 확인한 뒤 비어 있는 잠금 디렉터리만 정리하세요.
- 웹 시작 전 동일 JAR의 **로컬 전용 단발 마이그레이션**을 실행합니다. V17 트리거 생성에 필요한 기존 로컬 root 자격은
  이 자식 JVM의 Flyway 연결에만 환경변수로 전달하고 앱 datasource와 웹·워커는 `cobweb` 제한 계정을 유지합니다.
  전역 DB 설정/binlog를 완화하거나 앱 계정에 SUPER 권한을 추가하지 않습니다. 성공 후 컨텍스트·스케줄러·DB 풀을 닫고 정상 종료하며,
  실패·취소 시 웹/워커를 시작하지 않고 실행 상태만 복구합니다. DB 내용은 유지하며 진단은 실행별 `migrate.log`를 확인하세요.
  root 비밀번호가 있는 `config.env`를 앱에 직접 가져오지 않으며, 앱에 필요한 키만 실행별 `runtime.properties`(권한 600)에 담습니다.
  단발 마이그레이션은 이 파일만 가져오고 웹·워커만 기존 `.env`의 OAuth/메일 설정도 함께 사용합니다.
  외부 `SPRING_APPLICATION_JSON`, `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS`,
  `SPRING_CONFIG_LOCATION`/`ADDITIONAL_LOCATION`/`NAME`과 연결값 3개 외의 `SPRING_FLYWAY_*`가
  비어 있지 않으면 실행 전에 거절합니다. 연결값은 전용 로컬 DB·계정으로 덮어쓰며 외부 JVM/Flyway 옵션은 자동으로 적용하지 않습니다.
- HTTP는 **http://localhost:8080**, HTTPS는 **https://localhost:8443**입니다. 두 포트는 프로토콜이 다릅니다.
  `https://localhost:8080`처럼 HTTP 포트에 TLS를 보내면 `Invalid character found in method name [0x16...]` 오류가 납니다.
- 기본 웹 바인딩은 기존 설정대로 `0.0.0.0`입니다. DB는 `127.0.0.1:3307`로만 노출됩니다.
  기본 인증서는 `localhost`와 `127.0.0.1`에만 유효하므로 서버 LAN IP/외부 도메인 접속용 인증서는 아닙니다.
- 처음 실행할 때 `.local/run/<프로젝트>/tls/localhost.p12`와 `localhost.crt`를 생성하고 이후 재사용합니다.
  키 저장소 비밀번호는 개인 `config.env`에 저장하고 프로세스 인자나 로그에 출력하지 않습니다.
  HTTPS 준비 검사는 `curl --cacert`로 이 인증서를 확인하며, 인증서 검증을 끄지 않습니다.
- **자체서명 로컬 인증서는 브라우저에 자동 신뢰되지 않습니다.** 시스템 CA 신뢰 설치나 브라우저 보안 설정 변경은 자동으로 하지 않습니다.
  HTTPS 사용 전 사용자가 인증서의 출처·호스트를 확인하고 해당 개발 환경의 신뢰 정책에 맞게 처리해야 합니다.
  브라우저 인증서 경고를 무시하도록 요구하지 않으며, 공개 배포는 유효한 도메인/인증서의 Caddy 구성을 사용하세요.
- `PUBLIC_BASE_URL`은 실행 시 HTTPS 주소로 설정됩니다. OAuth를 쓰려면 제공자에 등록한 콜백도 해당 HTTPS 주소와 일치해야 합니다.
  메일/OAuth 비밀값은 기존 `.env`를 사용하며 설정하지 않으면 실제 제공자 연동은 사용할 수 없습니다.
- 포트는 `COBWEB_PORT=18080 COBWEB_HTTPS_PORT=18443 ./run.sh`로 바꿀 수 있습니다.
  이전 13언어 이미지는 자동으로 갱신합니다. 같은 언어 세트의 실행기 수정 후 이미지를 강제로 다시 빌드하려면
  `./run.sh --rebuild-judge`, 전체 옵션은 `./run.sh --help`를 사용하세요.

### 수동 HTTP 개발 실행

1. Java 21과 Docker를 준비하고 `.env.example`을 참고해 `.env`에 값을 설정합니다.
   `JWT_SECRET`은 최소 32바이트 임의 비밀값입니다. 메일과 OAuth는 실제 제공자 설정이 필요합니다.
2. `DEV_DB_PASSWORD`, `DEV_ROOT_PASSWORD`를 설정합니다.
3. `docker compose -f compose.dev.yaml up -d`
4. `docker build -t cobweb-languages:local judge` (첫 빌드는 여러 컴파일러를 내려받으므로 시간/디스크가 필요합니다.)
5. `JUDGE_ENABLED=true ./gradlew bootRun --args='--spring.profiles.active=local --demo.seed=true'`
6. http://localhost:8080/ 에서 문제 조회·로그인·제출을 사용할 수 있습니다.

ZIP 기반 새 화면 소스는 `frontend/src/`이며 Gradle이 잠금 파일로 설치·빌드해 JAR의 `/ui/`에 포함합니다.
서비스 화면 경로와 기존 `/app.html`은 React로 연결하고, 이전 정적 화면은 `/legacy`에 보존합니다.
2026-10-21 새 React 화면의 API 이식과 로컬 재검증을 완료했습니다. Java/MySQL/Docker 전체 221개와 React 회귀 76개가 통과했고,
별도 웹/자동 워커의 HTTP 연동 13개 경로 및 브라우저의 실제 채점·그룹/코테·커뮤니티/관리자 이력을 확인했습니다.
프론트 테스트는 `npm --prefix frontend test`로 실행합니다. 파일별 순차 실행, V8 힙 192MiB,
프로세스 그룹 RSS 512MiB, 파일별 외부 20초, 출력 2MiB 제한을 적용합니다(macOS/Linux).
제한 초과·감시 실패 시 해당 테스트 프로세스 그룹만 종료하며, 테스트 자체의 타이머에 의존하지 않습니다.
구현 현황은 [PROGRESS.md](PROGRESS.md), 확정 보안 검토는
[보안 검토 기록](docs/security/REVIEW.md)을 참고하세요.
전체 API의 요청·응답·권한, DB와 채점 동작은 [백엔드 구현 명세](docs/BACKEND_SPEC.md)에 정리했습니다.

local 프로필은 3307 포트의 DB를 사용하고 Flyway V1–V21을 적용합니다.
기본 웹 기동에서는 Lv.0 입문 100문제와 Lv.1 SKILLED 75문제, Lv.2 ELITE 90문제를 등록합니다. 기존 사용자 문제의 번호·제목과 충돌하면
트랜잭션 전체를 중단하며 덮어쓰지 않습니다. 기존 체인 레이저는 ID·제출·테스트를 보존한 채
1100번으로 보존한 뒤 ELITE 과정 등록 시 같은 ID로 공개를 복구합니다. SKILLED는 1101~1175, 새 ELITE는 1176~1265입니다.
전체 기본 과정을 끄려면 `--curriculum.level0.enabled=false`를 명시합니다. 이때만 `demo.seed=true`의
기존 체인 레이저 데모가 동작하며, 이미 보관한 체인 레이저를 1000번에 다시 만들지는 않습니다.
작성자는 로그인할 수 없는 `cobweb-system` 계정입니다. 실제 사용자 계정과의 충돌은 거절합니다.
정답 코드와 테스트 입력은 공개 API로 반환하지 않습니다.

### OJ Level 0 — 기초 문법 100문제

문제의 단일 원본은 `src/main/resources/problems/level0.json`입니다. `1000~1099` 전부 INITIATE이며,
Lv.0-1~5를 각각 INITIATE 5~1로 나눕니다. 199개 공개 예제, 812개 비공개 테스트와 Python 정답 100개를 포함합니다.
첫 Hello World도 표준 출력 대신 인자 없는 함수가 문자열을 반환합니다. 1097·1098번은 요청한 대로 `solution`,
나머지는 `sol`을 사용합니다. 반복문·함수 사용은 학습 목표이며 채점기는 특정 구문 사용을 강제하지 않습니다.

SQL·엄랭을 제외한 15개 언어에 공개 서명 기반 시작 코드를 제공합니다. `GENERIC_V1`은 인자 0~6개와
`int`·`float`·`bool`·`string`·`int[]`·`string[]`·`int[][]`·`string[][]`을 지원합니다. 정수는 32비트, 문자열은 ASCII이며
테스트 입력은 `{"args":[...]}`입니다. JSON 반환값의 문자열·배열 순서·불리언·정수는 정확히 비교하고,
실수에는 절대/상대 오차 1e-9를 적용합니다. C·LLVM IR의 포인터/길이 규약은 `judge/function/GENERIC.md`를 참고하세요.
공개 서명만 API로 전달하며 참고 정답·비공개 테스트는 기존 관리 권한 경계 안에 둡니다.
가져오기 이력으로 재시작 때 중복을 막고 등록 후 관리자가 바꾼 문제 내용·공개 상태를 덮어쓰지 않습니다.

추가 과정의 원본은 `problems/level1.json`, `level2.json`입니다. 각 영역의 SKILLED 등급은 5·4·3·2·1,
ELITE의 여섯 영역은 5·4·3·3·2·1로 배정합니다. 채점기는 알고리즘 선택이나 특정 구문 사용 자체를 강제하지 않습니다.
일반 함수 입력·정답 JSON은 각각 UTF-8 16MiB, 모든 인자에 걸친 배열 슬롯(바깥 행+내부 원소) 합은 100만 개까지입니다.
행 길이가 다른 중첩 배열과 빈 행을 지원합니다. 문자열 하나는 16KiB ASCII, private 테스트 묶음은 32MiB,
범용 함수 제작 JSON 전체는40MiB까지입니다(일반 문제 제작은 기존2MiB).
수동 테스트는 입력·예상값·표시 출력마다 64KiB를 유지하며, 테스트 케이스/실행 출력을 같은 내부 스크롤 영역의 탭으로 전환합니다.

문제 제작 HTTP 본문은 스트리밍40MiB 상한과 JVM당 동시1개 제한으로 보호합니다. 웹·워커·단발 마이그레이션의
JVM 힙은 각각64MiB로 시작해512MiB까지만 사용하며 OOM 시 종료합니다. 이는 Docker·DB·JVM 비힙을 포함한 전체 메모리 상한은 아닙니다.

콘텐츠 독립 검증은 `python3 -I -B -m unittest discover -s src/test/python -p 'test_level0_catalog.py'`,
실제 15언어 ABI 검증은 `DOCKER_JUDGE_TEST=true`, 100개 정답 전체 Docker 검증은
`GENERIC_CATALOG_DOCKER_TEST=true`를 추가해 Gradle 테스트를 실행합니다. 새 이미지는 `17-v3-generic-nested`로 구분해 이전 캐시를 무효화합니다.
`GET /api/ready`는 ApplicationRunner와 문제 등록 트랜잭션이 끝나기 전 503, 트래픽 수신 가능 상태가 된 뒤
200을 반환합니다. `run.sh`도 이 응답을 확인하므로 HTTP 포트가 먼저 열렸다는 이유만으로 준비 완료를 선언하지 않습니다.
분리된 `compose.worker.yaml`의 워커는 입문 과정 가져오기를 끄며 웹만 등록을 담당합니다.

### 2026-10-26 문제별 시작 코드와 코드 편집기

활성 React 화면의 일반 풀이·그룹 시험·제작 코드 입력은 CodeMirror 6를 사용합니다. 언어를 처음 선택하면 문제에 등록된
공개 `starterCodes`를 우선 채우고, 등록값이 없으면 실행 방식에 맞는 기본 틀을 사용합니다. 언어별 작성 내용은 현재 화면의
메모리에 보존하므로 다시 선택해도 덮어쓰지 않으며, 계정 전환·이탈 시 지웁니다. 제작 화면의 **공개 시작 코드 추가**로
문제별 매개변수와 언어별 선언을 직접 등록할 수 있습니다. 이는 비공개 정답 코드와 별개이며 정답에서 자동 추출하지 않습니다.

- **문법 추천**: 17개 언어의 키워드·주요 내장 이름·문법 스니펫과 현재 문서의 이름을 입력 중 또는 `Ctrl+Space`로 추천합니다.
  모든 외부 라이브러리 API나 프로젝트 전체 타입 추론을 제공하는 의미 분석/LSP는 아닙니다. 소스를 외부 언어 서버로 보내지 않습니다.
- **괄호 자동 닫기·자동 들여쓰기·문법 강조**: 언어별 규칙을 사용하며 `편집기 설정`에서 문법 추천과 함께 네 기능을 각각 켜고 끕니다.
  자동 들여쓰기는 Python 블록·중괄호 등 언어 구문에 맞춰 동작합니다. Tab은 공백 4개, Shift+Tab은 내어쓰기이며 Esc 다음 Tab으로 포커스를 이동합니다.
- 설정은 `localStorage`의 `cobweb.editor.preferences.v1`에 boolean 네 개만 저장합니다. 답안·참고 정답·시작 코드의 편집 내용이나 인증 토큰은 저장하지 않습니다.

기본 FUNCTION 시작 코드는 공개 `GENERIC_V1` 서명 또는 기존 `CHAIN_LASER_V1` 규격을 사용합니다. 규격이 확인되지 않은
레거시 FUNCTION은 매개변수를 추측하지 않습니다. **시작 코드 등록 자체는 채점 ABI 변경 기능이 아닙니다.** 범용 규격은
별도의 공개 `functionSignature`에서 인자·반환형을 지정하며, STDIN은 입력을 읽고 정답을 출력하는 프로그램이 필요합니다.
`starterCodes`는 언어당 하나, 허용 언어 내 최대 17개, 코드마다 UTF-8 64KiB 이내입니다. 생략한 수정 요청은 기존 시작 코드를
유지하고 `[]`는 명시적으로 비웁니다. V15는 시작 코드 전용 테이블만 추가하고 기존 문제·정답·테스트를 덮어쓰지 않습니다.

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

`bash scripts/verify-ci-local.sh static`은 Node 인증·세션 경합·문제 편집·그룹·커뮤니티·코드 들여쓰기·Markdown의 7종 회귀와 개발·웹·워커 Compose 설정을 검사합니다.
`static`에는 React 회귀, Python 회귀 43개(엄랭 원본 해시·입출력·오류 처리 23개, 범용 함수 13개, Lv.0 카탈로그 7개)와 `run.sh` 격리 회귀 43개도 포함합니다.
런처 회귀는 모의 명령을 사용하므로 실제 Docker·DB·서비스를 변경하지 않습니다. 별도 실행은 `bash src/test/bash/run-sh.test.sh`입니다.
`quick`은 여기에 MySQL 연동 테스트를 추가하고, `full`은 채점 이미지를 빌드한 뒤 Docker를 포함한
전체 빌드와 결과 검사를 실행합니다. `all`은 두 경로를 순서대로 실행합니다. 이 스크립트는
`.env` 대신 전용 로컬 테스트 DB와 테스트용 값만 사용합니다. 기본은 `127.0.0.1:13307/cobweb_test`이며,
`TEST_DB_URL=jdbc:mysql://127.0.0.1:13308/cobweb_feature_test`도 명시적으로 지정할 수 있습니다.
재검증용 새 DB `jdbc:mysql://127.0.0.1:13309/cobweb_revalidation_test`도 허용합니다.
기존 테스트 DB에 중복 제목이 있으면 V10이 중단합니다. 기존 데이터를 임의로 고치지 말고 새 테스트 DB를 사용하세요.

`scripts/verify-local-linkage.py`는 **과거 V17·체인 레이저 환경용** 별도 웹/자동 워커 HTTP 연동 검사입니다.
서버나 DB를 만들지 않으며, 대상은 전용 컨테이너 `cobweb-feature-verify-mysql`의 `cobweb_linkage_test`와
`127.0.0.1:18083`으로 고정합니다. 스크립트가 요구하는 스키마는 최소 V17이 아니라 **V1–V17 모두 성공·최대 버전 정확히 17**입니다.
1000번이 공개 체인 레이저 FUNCTION이고 비공개 10케이스를 갖는 당시 fixture가 필요합니다.
Lv.0 가져오기를 끈 웹/워커와 해당 스키마에 맞는 앱을 사용하는 격리된 레거시 검증에만 적용하세요.
현재 V21·Lv.0 기본 환경에는 그대로 실행할 수 없으며, 맞추려고 기존 DB를 다운그레이드하거나 문제를 되돌리지 마세요.
현재 범용 함수의 HTTP→DB 큐→실제 워커·판정 재사용은 전체 Gradle 검사의 `Level0GenericWorkerIntegrationTest`로 확인합니다.
레거시 검사는 `.env` 없는 임시 디렉터리와 테스트용 DB/JWT/메일/OAuth 설정·검증 계정을 사용하며 실제 외부 연동을 사용하지 않습니다.
프록시·리다이렉트를 차단하고 테스트 시간창만 SQL로 조정하며, 제작/공개/커뮤니티·그룹/시험·답안/다시보기는 HTTP로 검사합니다.
콘텐츠·이력·DB는 삭제하지 않습니다.
2026-10-21 최종 로컬 결과는 Java/MySQL/Docker 221개(실패·오류·건너뜀 0), React 76개, 런처 격리 27개,
엄랭 Python 23개, 레거시 Node 7종 및 Compose 3종 통과입니다. React 테스트 그룹의 관측 최대 RSS는 221MiB였으며 npm 감사의 알려진 취약점은 0개였습니다.
실제 외부 OAuth·SMTP, 운영 배포·부하, AMD64 실행과 원격 CI는 별도 검증입니다.
17언어·15개 FUNCTION 어댑터와 실제 HTTP/워커 채점의 상세 증거·검증 경계는 `PROGRESS.md`를 참고하세요.

`.github/workflows/ci.yml`은 push·PR마다 빠른 검사와 17언어 Docker 전체 검사를 실행하도록
구성되어 있습니다. Docker 테스트 보고서가 없거나 언어별·FUNCTION·HTTP 워커 테스트가
건너뛰어지면 전체 검사도 실패합니다. 결과와 로그는 7일 보관합니다. 실제 GitHub 실행과
보호 브랜치의 필수 상태 검사 설정은 저장소에서 확인해야 합니다.

## API

기존 인증 API는 `/api/auth`, 이메일 인증은 `/api/email-verifications`입니다.
새 API 응답은 기존 `{success, data, message}`를 유지하고 오류 분류 `errorCode`와 진단용 `errorId`를 추가합니다(성공 시 null).
비공개 자료는 원저작자·허용된 그룹 관리자·플랫폼 운영자만 접근하며, 타인은 존재를 숨기는 404를 받습니다.
그룹의 일반 회원 권한은 현재 ACTIVE 계정과 ACCEPTED 그룹에서만 유효합니다. 운영자 관리 권한은 시험 응시를 대신하지 않습니다.
API·인증 필터·프런트 요청/렌더 경계에서 오류를 분류하며, 서버 내부 메시지·SQL·토큰·제출 코드는 응답에 포함하지 않습니다.

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

- 일반 풀이·코테 풀이·제작 정답 코드 입력란은 Tab 공백 4칸, Shift+Tab 내어쓰기, Enter 자동 들여쓰기를 지원합니다.
  여러 줄 선택도 처리하며 IME 조합은 방해하지 않습니다. Esc 다음 Tab으로 입력란 밖으로 이동할 수 있습니다.
- 문제 설명·입출력 설명·제한·예제와 검수 화면은 코드 블록, 인라인 코드, 강조, 표, 목록 등 Markdown을 표시합니다.
  일반 입출력 예제의 공백은 그대로 보존합니다. 고정 버전 lexer의 토큰에서 허용 DOM만 생성하고 raw HTML은 실행하지 않습니다.
  `javascript:`/`data:` 등 실행형 링크는 만들지 않고, 이미지는 자동 외부 요청 대신 링크로 표시합니다.
- `/create`: 예제·비공개 테스트 추가/삭제, 언어·태그·출처 선택, 정답 코드 붙여넣기 또는 UTF-8 파일 첨부, 초안 편집·검수 요청.
  정답 파일은 언어별 하나, 최대 64KiB입니다. 초안에는 정답/테스트가 없어도 되지만 게시 전에는 필요합니다.
  제목은 공백 정규화 및 DB unique 제약으로 일반 문제 전체 또는 각 그룹 안에서 중복을 금지합니다.
- `/admin`: 운영자 문제 목록·검색·검수·공개/비공개 전환. 그룹 문제는 전체 공개로 전환하지 않습니다.
- `/groups`: 50문제 이상 해결한 회원이 일반 그룹을 생성하며 소유 그룹 포함 일반 그룹 동시 가입 3개까지입니다.
  모든 계정이 자동으로 속하는 내장 `COBWEB GROUP`은 이 상한에서 제외하므로 총 4개까지 표시될 수 있습니다.
  내장 그룹에서는 탈퇴·강퇴·사용자 OWNER 지정·이름/상태/고유 키 변경을 허용하지 않습니다. 플랫폼 운영 권한은 기존대로입니다.
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
제작 화면의 추가 버튼으로 공개 예제 최대 20개·비공개 테스트 최대 50개를 입력할 수 있습니다.

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
| PASCAL | Free Pascal 3.2.2 / Object Pascal 모드 | main.pas / program Main |
| PHP | PHP CLI 8.3.6 (Ubuntu 보안 패키지) | main.php / `<?php`로 시작 |
| DART | Dart SDK 3.13.5 / AOT 네이티브 실행 | main.dart / void main() |
| UMJUNSIK | 엄랭 v2 / 공식 Python 구현 기반 | main.umm / `어떻게` … `이 사람이름이냐ㅋㅋ` |

엄랭은 [공식 저장소](https://github.com/rycont/umjunsik-lang)의 Python 인터프리터를 고정 커밋으로 포함합니다.
MIT 라이선스·출처·원본 해시와 채점용 CLI의 보완 내역은 `judge/vendor/umjunsik/`에서 확인할 수 있습니다.
별도 웹 서비스나 임의 다운로드 없이 다른 언어와 동일한 격리 컨테이너에서 실행합니다.
추가 4언어의 위 버전은 2026-10-20 ARM64 이미지에서 확인했습니다.

현재 단일 소스 파일과 설치된 표준 라이브러리만 지원합니다. 제출 중 npm/NuGet/Go module/Cargo
다운로드, 사용자 빌드 스크립트·컴파일 옵션·다중 파일 업로드는 허용하지 않습니다.
언어를 추가하려면 Java 허용 목록, 고정 실행기, 이미지, 실제 Docker 회귀 테스트를 함께 변경해야 합니다.

## 채점 규칙

- STDIN: SQL 이외 16개 언어. 표준 입력을 읽고 표준 출력으로 답을 반환합니다.
- FUNCTION: `GENERIC_V1` 공개 서명이 있는 문제와 기존 체인 레이저 규격은 SQL·엄랭을 제외한 15개 언어를 지원합니다.
  서명이 없는 기존 비정형 문제는 데이터 수정 없이 Python만 노출합니다. 제출 파일에는 `main`을 작성하지 않고
  문제에 표시된 함수 이름과 인자·반환형을 사용하세요. 아래 고정 시그니처는 보관한 체인 레이저 전용입니다.
- SQL: SQLite만 허용합니다. 테스트 입력은 `CREATE TABLE`/`INSERT` 초기화 SQL이고 제출은
  `SELECT`/`WITH` 등 단일 읽기 쿼리입니다. 매 케이스마다 새 메모리 DB를 만들어 원래 서비스 DB와 격리합니다.
  결과는 헤더 없는 TSV, NULL은 `NULL`, 개행은 LF입니다. 탭/개행/따옴표가 든 값은 CSV 방식으로 인용합니다.
  순서가 중요한 문제는 `ORDER BY`를 명시하세요. 쓰기/ATTACH/확장 로딩/CLI 명령은 거절합니다.
- 공개 `functionSignature`가 없는 FUNCTION 문제의 각 테스트 입력은 `{"matrix":[...],"target":[행,열]}`이며 `matrix`는
  0/1 값으로 이뤄진 정확한 15×15 배열, target의 두 좌표는 각각 0–14여야 합니다. 정답은 정확히
  JSON `true` 또는 `false`입니다. 생성·수정 시 규격 위반 테스트는 400으로 거절합니다.
- 범용 함수 이외의 비교는 양 끝 공백을 제거한 문자열 일치입니다. EFFICIENCY/사용자 지정 검사기는 아직 지원하지 않습니다.

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
| Pascal | `function sol(const matrix: TMatrix; const target: TTarget): Boolean;` 함수 조각 |
| PHP | `<?php function sol(array $matrix, array $target): bool { ... }` |
| Dart | `bool sol(List<List<int>> matrix, List<int> target) { ... }` |

Pascal FUNCTION의 `TMatrix = array[0..14,0..14] of LongInt`와 `TTarget = array[0..1] of LongInt`는
하네스가 선언합니다. 전체 `program` 대신 함수 선언·본문만 제출하고 `Result`에 불리언을 대입합니다.
엄랭은 함수 호출 규약이 없으므로 STDIN만 지원합니다. SQL은 SQL 전용 문제에서만 지원합니다.
함수형 어댑터는 단일 파일을 고정된
컴파일·실행 명령으로 처리하며 제출자가 빌드 옵션이나 파일 경로를 선택할 수 없습니다.
- Docker 네트워크 없음, 읽기 전용 루트, 권한 제거, 비특권 사용자, 메모리·CPU·프로세스·출력 제한을 적용합니다.
- 컴파일은 제출당 한 번, 최대 CPU 60초/벽시계 65초/메모리 1GiB를 사용합니다.
  컴파일 실패·컴파일 제한 초과는 COMPILATION_ERROR, Docker 준비 실패는 SYSTEM_ERROR입니다.
- 산출물은 256MiB tmpfs 볼륨에만 저장하고 매 케이스의 새 컨테이너에는 읽기 전용으로 제공합니다.
  컴파일 임시 공간은256MiB, 실행 임시 공간은 일반32MiB·범용 함수96MiB이며 모두 noexec/nosuid입니다.
  컨테이너당 프로세스/스레드 제한은 JVM/.NET을 고려해 128개입니다. stderr/일반 stdout은 64KiB,
  범용 함수 stdout만 16MiB까지 보관합니다. 수동 테스트 응답의 실제 출력은 여전히 64KiB입니다.
  정답은 어떤 컨테이너에도 전달하지 않습니다. 메모리 제한에는 런타임 자체의 메모리도 포함됩니다.
- 정상 종료 시 컴파일 컨테이너/볼륨/소스를 정리합니다. 워커 비정상 종료 시 유지 컨테이너는
  30분 후 종료하지만 볼륨 메타데이터·로컬 소스가 남을 수 있으므로 운영 정리 정책이 필요합니다.
- 실제 실행은 컨테이너 내부에서 문제의 밀리초 벽시계 제한으로 종료하며 CPU 제한도 보조로
  적용합니다. 결과의 `execution_time_ms`는 Docker 기동 비용을 포함하므로 사용자 코드만의
  정밀 실행 시간으로 해석하면 안 됩니다.
- 메모리 초과/종료 신호의 세부 분류와 실제 최대 메모리 측정은 향후 개선 대상입니다.
- 활성 워커당 동시 제출 1개, 사용자당 대기/진행 최대 5개입니다.
- 제출 원문은 DB `submissions.source_code`에 공백·개행·유니코드 정규화 없이 보존합니다. 빈 코드와 UTF-8 64KiB 초과는
  API에서 거절하며 DB도 `OCTET_LENGTH` 제약으로 빈 원문·초과 저장을 막습니다. 잘라서 저장하지 않습니다.
- 동일 코드 재제출도 새 제출 ID·접수 횟수를 남깁니다. 워커는 같은 사용자·문제·언어·일반/동일 시험 범위에서
  원문 문자와 UTF-8 바이트가 완전히 같고 실제 Docker 이미지 ID·판정기 바이트코드·테스트·시간/메모리·ABI까지
  같을 때만 완료 판정을 복제합니다. 해시만 일치해서 재사용하지 않으며 QUEUED·RUNNING·SYSTEM_ERROR나
  불완전 결과는 제외합니다. 접근 권한을 다시 확인하고 시험 종료/권한 취소 시에는 원래 격리 채점으로 진행합니다.
  재사용 결과는 상세의 `reusedFromSubmissionId`로 구분하며 케이스 결과와 당시 소요 시간도 보존합니다.
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
입문 과정은 `src/main/resources/problems/level0.json`, 보존한 체인 레이저 데모는 `src/main/resources/demo/chain-laser.sql`에서 각각 관리합니다.
Flyway 기본값은 기존 로컬 DB 자동 변경 방지를 위해 꺼져 있고 local/prod/test에서 켜집니다.
`baseline-on-migrate`는 자동 활성화하지 않습니다.

1. 기존 DB를 백업하고 복제 DB에서 검증합니다.
2. V1의 18개 테이블·제약·인덱스를 기존 구조와 비교합니다.
3. 기존 테이블이 V1과 정확히 일치할 때만 Flyway baseline을 버전 1로 기록한 뒤 V2–V21을 적용합니다.
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
V14는 기존 STDIN 문제에 Pascal·PHP·Dart·엄랭을, 공통 어댑터에 호환되는 FUNCTION 문제에
Pascal·PHP·Dart를 추가합니다. SQL·기존 비정형 FUNCTION의 언어 범위는 유지합니다.
V15는 공개 시작 코드의 `problem_starter_codes` 테이블만 추가합니다. 비공개 정답에서 코드를 복사하지 않고 기존 행도 변경하지 않습니다.
V16은 제출 원문 바이트 제약과 본인 동일 코드 판정 재사용 메타데이터를 추가합니다. 과거 판정의 컨텍스트는 추정하지 않아
기존 완료 기록은 재사용하지 않습니다. 기존 빈 코드/64KiB 초과 원문이 있으면 제약 적용을 중단하고 데이터를 보존합니다.
V17은 내장 `COBWEB GROUP`과 모든 회원의 기본 MEMBER 소속을 추가하고 DB 고유 키·트리거로 내장 정체성을 보호합니다.
V18은 문제 공개 범위와 별개인 제작자 표시 동의 `author_public`을 추가합니다. 기존/새 문제는 비공개가 기본이며 제작·수정 화면에서 공개를 선택하면
난이도 오른쪽에 실제 제작 계정명을 표시합니다. 비공개 상세 API는 이름을 null로 반환하고 ID·이메일은 공개하지 않습니다.
일반 풀이·코테에 함께 적용하며 구형 수정 요청의 `authorPublic` 생략/null은 기존 설정을 보존합니다. 복제한 새 초안은 비공개로 시작합니다.
기존 V1–V17 파일을 수정하지 않으므로 이미 적용한 Flyway 체크섬은 바뀌지 않습니다.
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
