# Cobweb

Java 21 / Spring Boot / MySQL 8.4 기반 알고리즘 문제 풀이 MVP입니다.
회원 인증, 문제 작성·검수·조회, Python 제출, DB 채점 대기열, Docker 실행,
결과·해결 수·랭킹·프로필과 사용자 화면을 포함합니다.

## 개발 실행

1. Java 21과 Docker를 준비하고 `.env.example`을 참고해 `.env`에 값을 설정합니다.
   `JWT_SECRET`은 최소 32바이트 임의 비밀값입니다. 메일과 OAuth는 실제 제공자 설정이 필요합니다.
2. `DEV_DB_PASSWORD`, `DEV_ROOT_PASSWORD`를 설정합니다.
3. `docker compose -f compose.dev.yaml up -d`
4. `docker build -t cobweb-python:local judge`
5. `JUDGE_ENABLED=true ./gradlew bootRun --args='--spring.profiles.active=local --demo.seed=true'`
6. http://localhost:8080/ 에서 문제 조회·로그인·제출을 사용할 수 있습니다.

화면은 `src/main/resources/static/index.html` 하나로 관리합니다. 기존 `/app.html` 주소도
동일한 화면으로 연결됩니다. 구현 현황은 [PROGRESS.md](PROGRESS.md), 확정 보안 검토는
[보안 검토 기록](docs/security/REVIEW.md)을 참고하세요.

local 프로필은 3307 포트의 새 DB를 사용하고 Flyway V1/V2를 적용합니다.
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
docker build -t cobweb-python:local judge
DOCKER_JUDGE_TEST=true ./gradlew build
```

이미 해당 테스트 컨테이너가 있으면 `docker start cobweb-mvp-test-mysql`을 사용합니다.
다른 테스트 DB는 `TEST_DB_URL`, `TEST_DB_USERNAME`, `TEST_DB_PASSWORD`로 지정합니다.
Docker 테스트는 `DOCKER_JUDGE_TEST=true`일 때 실제 컨테이너를 사용합니다.
빌드 보고서는 `build/reports/tests/test/index.html`에 생성됩니다.

## API

기존 인증 API는 `/api/auth`, 이메일 인증은 `/api/email-verifications`입니다.
새 API 응답은 기존과 동일하게 `{success, data, message}` 형식을 사용합니다.

회원가입 `POST /api/auth/signup`에는 `username`, `email`, `password`와
`verificationCode`(앞서 JOIN 인증을 확인한 숫자 8자리 코드)가 필요합니다.
인증 완료 상태만 공유해서 다른 사람이 가입하는 것을 막기 위해 가입 시에도 코드를 확인합니다.
코드가 만료됐다면 새 코드를 발급받아 다시 인증해야 합니다.

| 경로 | 기능 | 권한 |
|---|---|---|
| GET /api/problems?page=0&search=제목 | 공개 문제 목록 (30개) | 공개 |
| GET /api/problems/{번호} | 본문·예제 | 공개 문제/작성자/운영자/소속 그룹 |
| POST /api/problems | 초안 생성, 번호 자동 발급 | 로그인 |
| PUT /api/problems/{번호} | 초안·반려 문제 수정 | 작성자/운영자 |
| POST /api/problems/{번호}/review-request | 검수 요청 | 작성자/운영자 |
| POST /api/problems/{번호}/review | 승인·반려·보관 | OPERATOR/DEVELOPER |
| POST /api/submissions | Python 코드 제출 | 로그인 |
| GET /api/submissions | 최근 내 제출 50개 | 로그인 |
| GET /api/submissions/{id} | 제출 판정 및 케이스 결과 | 제출자/운영자 |
| GET /api/ranking?page=0 | 해결 수/제출 수 순 랭킹 | 공개 |
| GET /api/profiles/{username} | 공개 프로필과 공개 해결 문제 | 공개 |
| GET /api/me | 내 프로필 | 로그인 |
| PATCH /api/me | bio, githubUrl 수정 | 로그인 |

제출 요청 예: `{"problemNumber":1000,"sourceCode":"def sol(matrix, target): ..."}`.
문제 작성 필드는 `ProblemRequests.Write`, 검수는 `{"status":"PUBLISHED","cause":"검수 완료"}`입니다.
초안 → 검수 요청 → 공개/반려 흐름이며, 공개된 본문·테스트는 변경할 수 없습니다.
현재 편집 화면은 공개 예제 1개와 테스트 1개를 입력받으며 API는 여러 개를 지원합니다.

## 채점 규칙

- Python 3.12만 지원합니다. STDIN은 표준 입출력, FUNCTION은 `sol(matrix, target) -> bool`입니다.
- FUNCTION 입력은 `{"matrix":[...],"target":[행,열]}`, 정답은 JSON `true` 또는 `false`입니다.
- 현재 비교는 양 끝 공백을 제거한 문자열 일치입니다. EFFICIENCY/사용자 지정 검사기는 아직 지원하지 않습니다.
- Docker 네트워크 없음, 읽기 전용 루트, 권한 제거, 비특권 사용자, 메모리·CPU·프로세스·출력 제한을 적용합니다.
- 각 케이스는 새 컨테이너입니다. 정답은 컨테이너에 전달하지 않습니다.
- 시간은 컨테이너 준비를 포함한 벽시계 측정값이며 CPU 제한은 초 단위로 올림 처리됩니다.
  밀리초 단위의 엄밀한 경쟁 프로그래밍 채점에는 별도 실행 계측기가 필요합니다.
- 메모리 초과/종료 신호의 세부 분류와 실제 최대 메모리 측정은 향후 개선 대상입니다.
- 활성 워커당 동시 제출 1개, 사용자당 대기/진행 최대 5개입니다.
- 재시작으로 20분 이상 방치된 실행은 SYSTEM_ERROR 처리합니다. 토큰으로 중복 결과 반영을 막습니다.
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
3. 기존 테이블이 V1과 정확히 일치할 때만 Flyway baseline을 버전 1로 기록한 뒤 V2를 적용합니다.
4. 인증 테이블 5개만 있는 구형 DB는 바로 baseline 1 처리하면 안 됩니다. 누락 테이블 생성과
   이메일 중복/토큰 중복 정리 등 별도 전환이 먼저 필요합니다.

기존 데이터 정리나 실제 DB 스키마 변경을 자동으로 수행하지 않습니다.

## 배포

`DOCKER_JUDGE_TEST=true ./gradlew build` 후 `docker compose -f compose.prod.yaml build`로 이미지를 만듭니다.
배포 서버에서 채점 이미지도 빌드하고, `.env`에 DB_PASSWORD/MYSQL_ROOT_PASSWORD/SITE_DOMAIN과
인증 환경변수를 설정한 후 `docker compose -f compose.prod.yaml up -d`를 실행합니다.
Caddy가 도메인 TLS를 처리합니다. 실제 도메인의 DNS와 80/443 접근이 준비되어야 합니다.
OAuth 제공자 콘솔에도 운영 콜백 URL을 등록해야 합니다.

웹 컨테이너에는 Docker 소켓을 제공하지 않습니다. worker만 Docker를 제어합니다.
공개 서비스는 전용 격리 워커 호스트에서 실행하고 Docker/커널 패치를 유지하세요.
DB 백업·복구, 관측 및 부하 시험은 운영 환경에서 추가로 준비해야 합니다.
CI는 MySQL과 채점 이미지를 생성한 뒤 동일 테스트 및 JAR 빌드를 수행합니다.
