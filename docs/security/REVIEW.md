# 보안 검토 · 재현 · 수정 기록

검증일: 2026-09-17. 새 제품 기능을 추가하지 않고 기존 구현의 결함만 수정했습니다.

## 실행 근거와 한계

- 분석한 Strix 소스: usestrix/strix, commit `910c1ea4bbf22f8c01ab6c2a54bad09b94ccbc3b`.
- 시스템 프롬프트, white-box 구성 코드, 발견·반증·심각도·수정 검증·quick 모드 지침을 읽었습니다.
- 재작성한 프롬프트: `CLAUDE_STRIX_REVIEW_PROMPT.md`.
- `.env`, Git 이력, 빌드 출력과 실제 사용자 데이터를 제외한 소스 사본을 Claude에 제공했습니다.
- Claude Code 구독 로그인 사용. 별도 Strix/Anthropic API 키나 Strix Cloud는 사용하지 않았습니다.
- Claude에 Read/Glob/Grep만 허용했습니다. 셸/수정 권한, MCP, 자동 설정·훅 로딩 및 세션 저장은 비활성화했습니다.
- Claude 실행은 정상 종료했고 40턴, permission_denials 0건이었습니다.
- 원시 출력은 로컬의 `CLAUDE_REVIEW_RAW.md`에 보존하되 Git에서는 제외합니다. 이 파일 없이도 아래 재현 근거와 검증 명령을 확인할 수 있습니다. 원본의 신뢰도·심각도·안전 판정을 그대로 승인하지 않았습니다.

이는 Strix 스캔이 아닙니다. Strix 전용 도구/프록시/에이전트 그래프 대신 Claude의 읽기 전용
검토와 별도 로컬 테스트를 조합했습니다. AST/Semgrep/시크릿/의존성 스캐너는 실행하지 않았습니다.
원본의 무제한 자율 실행·수천 단계 반복·권한 우회·존재하지 않는 도구 호출 지침은 옮기지 않았습니다.

## Claude 후보의 독립 판정

| 후보 | 최종 판정 | 근거 및 조치 |
|---|---|---|
| COB-001 OTP 검증 시도 제한 없음 | 제한 부재 확인, 계정 탈취는 미입증 | 8자리/5분 TTL이 있고 실제 추측 성공률·처리량은 측정하지 않았습니다. 원본의 공격 속도와 High 신뢰도는 근거가 부족합니다. 새 제한 정책/저장 구조는 추가하지 않았습니다. |
| COB-002 로그인 실패 후 잠금 없음 | 정책/운영 보완 후보 | 카운터는 감사 기록일 수도 있습니다. 자동 영구 잠금 제안은 다른 사용자의 계정을 잠그는 공격을 만들 수 있어 적용하지 않았습니다. |
| COB-003 OAuth fragment 토큰 전달 | 방어 심화 관찰, 외부 유출 미입증 | fragment는 HTTP 요청/Referer로 전송되지 않습니다. replaceState·외부 스크립트 부재를 확인했습니다. 악성 확장 권한을 전제로 한 주장을 앱 계정 탈취로 확정하지 않았고 새 교환 API도 추가하지 않았습니다. |
| COB-004 CSP 없음 | 정보성 보완 후보 | 현재 소스의 도달 가능한 XSS가 입증되지 않았습니다. 미래 기능을 가정한 취약점은 확정하지 않았습니다. |
| COB-005 X-Forwarded-For 감사 로그 위조 | confirmed / Low / 동적 검증 | 로컬 HTTP 로그인 한 건의 위조 헤더가 DB client_ip로 저장됐습니다. 연결 상대 주소를 기록하도록 수정했고 동일 테스트가 통과했습니다. |

COB-005 수정 후 프록시 뒤의 앱 로그에는 프록시 주소가 기록됩니다. 실제 이용자 IP가 필요하면
신뢰 프록시 경계를 명시적으로 구성하고 프록시 로그와 대조해야 합니다. 임의 전달 헤더를 다시
신뢰하도록 설정하는 것은 해결책이 아닙니다. 운영 Compose의 Caddy 뒤에서 동일 위조가 가능한지는
별도로 입증하지 않았으며, 확인된 재현 범위는 직접 접속 가능한 앱 HTTP 엔드포인트입니다.

## 조정 에이전트가 별도로 재현한 결함

Claude가 지적하지 않은 결함입니다. Claude 검토 성과나 포괄적 안전 보증으로 혼동하지 않습니다.

### VAL-001: 만료된 이메일 인증의 재사용

- 불변 조건: 가입/인증 소비 시점에도 인증 기록이 만료되지 않아야 합니다.
- 수정 전: verified=true인 만료 기록으로 consumeVerification이 성공했습니다.
- 수정: isVerified에서 인증 여부와 만료를 함께 확인하며 경계 시각도 만료로 취급합니다.
- 테스트: `EmailVerificationServiceTest.consumeVerification_rejectsVerifiedButExpiredRecord`.
- 수정 전 예외 미발생으로 실패, 수정 후 거절 및 기록 미삭제 확인. 유효/미검증 기록의 기존 테스트도 통과.
- 증거 수준: 서비스 단위 재현. 외부 계정 탈취나 SMTP 실연동을 검증한 것은 아닙니다.

### VAL-002: 일회용 비밀번호 재설정 토큰의 동시 소비

- 불변 조건: 같은 resetToken은 동시 요청에서도 한 번만 성공해야 합니다.
- 수정 전: 실제 MySQL에서 두 요청을 암호 해시 지점에 모으면 두 요청 모두 성공했습니다(expected 1, actual 2).
- 수정: 토큰 조회에 PESSIMISTIC_WRITE를 적용하여 검사부터 사용 처리까지 직렬화합니다.
- 테스트: `PasswordResetConcurrencyTest.sameResetTokenCanOnlySucceedOnceUnderConcurrentUse`.
- 수정 후 성공 한 번, 경쟁 요청 거절, 최종 비밀번호 정상, 후속 재사용 거절을 확인했습니다.
- 픽스처의 DB NOW()/JVM 시간대 불일치를 먼저 제거한 뒤 수정 전 실제 이중 성공을 다시 재현했습니다.
- 전제: 공격자가 유효한 resetToken을 이미 보유해야 합니다. 토큰 획득이나 무인증 탈취를 입증한 것은 아닙니다.
- 다른 토큰들 사이의 동시성이나 인증 코드 교환 경쟁까지 이 테스트로 안전하다고 보지 않습니다.

### VAL-003: 제한된 공용 스레드 풀에서 정상 채점 입력 정체

- 불변 조건: stdout/stderr 읽기가 stdin 전송을 굶기지 않아야 합니다.
- 수정 전: common.parallelism=2에서 두 출력 읽기가 슬롯을 점유하여 정상 함수 제출이 TIME_LIMIT_EXCEEDED가 됐습니다.
- 수정: 케이스별 가상 스레드 실행기로 입출력을 분리하고 종료 시 실행기를 정리합니다.
- 동일 `DockerJudgeTest.functionReturnAndWrongAnswer`가 수정 후 ACCEPTED/WRONG_ANSWER를 정상 판정했습니다.
- 전체 테스트 JVM에도 common.parallelism=2를 적용해 회귀를 감지합니다.
- 이는 재현된 채점 가용성/정확성 결함이며 컨테이너 탈출 증거가 아닙니다.

## 실제 검증 명령

```sh
./gradlew test --tests '*EmailVerificationServiceTest' --tests '*PasswordResetConcurrencyTest'
JAVA_TOOL_OPTIONS=-Djava.util.concurrent.ForkJoinPool.common.parallelism=2 DOCKER_JUDGE_TEST=true ./gradlew test --tests '*DockerJudgeTest.functionReturnAndWrongAnswer' --rerun-tasks
./gradlew test --tests '*MvpIntegrationTest.clientCannotForgeLoginAuditAddress'
DOCKER_JUDGE_TEST=true ./gradlew build
git diff --check
```

모든 DB 테스트는 별도 cobweb_test를 사용했습니다. 운영 DB와 외부 OAuth/메일 제공자는 검사하지 않았습니다.
이 검토 단계의 전체 테스트 결과는 33개, 실패 0, 건너뜀 0이었습니다.
이후 직접 검토에서 인증 결함 네 건을 추가 재현·수정했으며 해당 단계에서 37개가 통과했습니다.
추가 발견의 근거와 범위는 아래 후속 직접 검토에 통합했습니다.

## 남은 검증 공백

- OTP 검증/로그인 제한의 구체적 정책, 유효 토큰 여러 개의 동시 소비 및 refresh/code 교환 경쟁.
- OAuth 제공자 실연동, 실제 운영 프록시 체인, 외부 공개 환경·부하·컨테이너 탈출 검증.
- 알려진 의존성 CVE와 시크릿 이력 검사. 원본 보고서의 not_applicable 판정은 잘못이며 미검사입니다.
- 원본의 no_issue_found는 읽은 경로에서 후보를 찾지 못했다는 의미일 뿐 전체 경로의 안전 판정이 아닙니다.

## 후속 직접 검토: 인증 결함 네 건

검증일: 2026-09-17. 기존 기능의 검증·수정만 수행했습니다. 이번 검토는 Claude/Strix의
추가 실행이 아니라 소스 분석과 직접 작성한 회귀 테스트를 사용했습니다.
DB 검증은 별도 MySQL 8.4 `cobweb_test`에서 했으며 실제 사용자 DB와 메일은 사용하지 않았습니다.

### 1. 로그인 완료가 재설정된 비밀번호를 과거 값으로 복구 — 높음

- 경로: `AuthService.login`과 `PasswordResetService.confirmReset`의 동시 실행.
- 원인: 로그인은 잠금 없이 User를 읽고 비밀번호를 검사한 뒤 실패 횟수/최근 로그인 시각을
  변경했습니다. Hibernate의 전체 행 UPDATE가 다른 요청에서 이미 변경한 password까지
  과거 값으로 덮어썼습니다. 재설정 토큰 자체의 잠금만으로는 이를 막지 못했습니다.
- 수정 전 재현: 이전 비밀번호 검증 직후 로그인을 잠시 멈추고 재설정을 커밋한 뒤
  로그인을 재개했습니다. 최종 DB 비밀번호가 새 비밀번호와 일치하지 않는 것을 확인했습니다.
- 영향: 이전 비밀번호를 알고 로그인과 재설정을 겹치게 할 수 있으면 비밀번호 복구 조치가
  무효화될 수 있습니다. 비밀번호를 모르는 익명 사용자의 계정 탈취를 입증한 것은 아닙니다.
- 수정: 로그인/비밀번호 재설정에서 사용자 행을 `PESSIMISTIC_WRITE`로 읽어 변경을 직렬화합니다.
  일반 조회와 JWT 인증 필터의 조회에는 잠금을 추가하지 않았습니다.
- 회귀: `AuthConcurrencyTest.inFlightLoginCannotRestorePasswordThatWasReset`.
  최종 비밀번호가 새 값이고 기존 로그인 경합에서 발급된 refresh token도 폐기되는지 확인합니다.

### 2. 동시 로그인 실패에서 DB 교착 및 감사 카운터 누락 — 중간

- 원인: 두 요청이 같은 User를 읽은 뒤 실패 로그 INSERT와 사용자 UPDATE를 경쟁했습니다.
- 수정 전 재현: 두 비밀번호 비교를 동시에 통과시키면 예상한 `PasswordMismatchException`
  대신 `CannotAcquireLockException` 및 MySQL 교착 예외가 발생했습니다.
- 영향: 정상적인 인증 거절 대신 서버 오류가 발생하고 롤백된 요청의 실패 기록이 누락됩니다.
- 수정: 위와 같은 사용자 행 잠금으로 해당 계정의 로그인 변경을 직렬화합니다.
- 회귀: `AuthConcurrencyTest.simultaneousFailuresDoNotLoseAuditCounter`.
  두 요청 모두 비밀번호 불일치로 거절되고 최종 실패 횟수가 2인지 확인합니다.

### 3. 전역 이메일 인증 상태를 다른 가입 요청이 사용 — 높음

- 경로: 코드 확인 API → `UserService.signup`.
- 원인: 이메일 단위 `verified=true`만 검사하고 최종 가입 요청자의 코드 소유 여부는
  확인하지 않았습니다. 이메일 주소는 비밀이 아니며 인증 상태만으로 요청자를 식별할 수 없습니다.
- 수정 전 재현: 테스트 이메일의 코드를 확인한 뒤, 코드가 없는 별도 익명 HTTP 요청으로
  회원가입을 보냈습니다. 거절해야 할 요청이 HTTP 200으로 성공했습니다.
- 영향: 실제 소유자가 인증한 뒤 가입하기 전의 유효 시간 안에, 이메일을 아는 다른 요청자가
  자신의 아이디/비밀번호로 가입을 선점할 수 있습니다. 기존 계정 탈취를 재현한 것은 아닙니다.
- 수정: 최종 가입 요청의 `verificationCode`를 필수 숫자 8자리로 검증하고 기존 코드와
  다시 비교한 뒤 인증 기록을 소비합니다. 유효 시간과 기존 인증 완료 조건도 유지합니다.
- 호환성: `/api/auth/signup`에 필드가 하나 추가됐습니다. 현재 단일 화면인 `index.html`과
  README의 요청 안내에도 반영했습니다(기존 두 화면은 파일 정리 과정에서 통합). DB 마이그레이션은 필요하지 않습니다.
- 회귀: `MvpIntegrationTest.verifiedEmailDoesNotAuthorizeAnUnrelatedSignupRequest`.
  누락/잘못된 코드는 400, 계정 미생성, 올바른 코드는 200 및 계정 하나 생성까지 확인합니다.

### 4. 긴 User-Agent로 로그인 감사 저장 실패 — 중간

- 원인: 클라이언트가 조절하는 User-Agent를 길이 제한 없이 `VARCHAR(255)`에 저장했습니다.
- 수정 전 재현: 600자 User-Agent로 잘못된 비밀번호 로그인을 요청하면 DB가
  `Data too long for column 'user_agent'`를 반환하고 HTTP 401 대신 409가 나왔습니다.
  로그인 실패의 일반 예외와 달리 이 DB 예외는 감사 트랜잭션을 롤백시킵니다.
- 수정: 부가 정보인 User-Agent만 최대 255개 유니코드 코드 포인트로 제한합니다.
  비밀번호 등 인증 입력은 자르지 않으며, null과 보조 문자의 UTF-16 경계를 보존합니다.
- 회귀: `MvpIntegrationTest.oversizedUserAgentCannotEraseFailedLoginAudit`.
  동일 요청이 401로 거절되고 실패 카운터 1과 감사 로그 1건이 남는지 확인합니다.

### 재실행 및 한계

```sh
./gradlew test --tests '*AuthConcurrencyTest' --tests '*PasswordReset*Test*'
./gradlew test --tests '*MvpIntegrationTest.verifiedEmailDoesNotAuthorizeAnUnrelatedSignupRequest' --tests '*MvpIntegrationTest.oversizedUserAgentCannotEraseFailedLoginAudit'
DOCKER_JUDGE_TEST=true ./gradlew build
git diff --check
```

직접 검토 단계 전체 빌드: **37개 테스트, 실패 0, 건너뜀 0**. 실제 Docker 채점 8개를 포함합니다.
추가된 회귀 테스트 네 개는 수정 전 실패와 수정 후 성공을 각각 확인했습니다.
파일 통합 후에는 단일 화면/기존 주소 호환 테스트를 추가해 `DOCKER_JUDGE_TEST=true ./gradlew clean build`의
**38개 테스트가 실패·건너뜀 없이 통과**했습니다.

동시성 테스트는 인증 계산 경계에 latch를 배치해 실행 순서를 재현합니다. 실제 서비스·트랜잭션·
MySQL을 사용하지만 운영 HTTP 부하 시험이나 공격 성공률 측정은 아닙니다.
심각도는 재현된 영향에 대한 정성적 평가이며 정식 CVSS 점수가 아닙니다.
사용자 행 잠금은 같은 계정의 인증을 직렬화하므로 대량 요청 시 지연에 대한 부하 검증이 필요합니다.
refresh 회전/로그아웃/OAuth/인증 코드 교환의 모든 경쟁 조합은 아직 검증하지 않았습니다.
기존 access token의 즉시 폐기 정책이나 로그인·OTP 속도 제한 정책도 이번 수정에 추가하지 않았습니다.
외부 OAuth/SMTP 실연동, 의존성 CVE, 공개 인프라와 컨테이너 탈출은 검증 범위 밖입니다.

## 2026-10-02 보안 및 저장 경로 재점검

이 절은 앞선 2026-09-17 검토와 별개로 실제 작업 트리를 다시 점검한 기록입니다.
별도 `cobweb_test` MySQL과 기존 다중 언어 채점 이미지에서 검증했습니다. 실제 사용자 DB나
외부 OAuth·SMTP 계정에는 접근하지 않았습니다.

### 정지된 기존 OAuth 계정의 토큰 발급

- 원인: 일반 로그인과 refresh 재발급은 `User.isLoginable()`을 검사했지만
  `OAuthService.loginOrSignup`의 기존 계정 경로는 검사하지 않고 `issueTokens`를 호출했습니다.
- 재현: 정지된 기존 계정을 반환하는 가짜 OAuth 제공자로 `loginOrSignup`을 호출하면
  `BannedUserException`이 발생하지 않았습니다. 실제 제공자 로그인 성공을 주장하는 테스트는 아닙니다.
- 수정: 기존 소셜 계정의 사용자 행을 잠가 상태를 검사하고, 공통 `issueTokens`도
  ACTIVE가 아닌 계정에는 토큰을 발급하지 않도록 방어합니다.
- 회귀: `OAuthServiceTest.loginOrSignup_doesNotIssueTokensForStoppedAccount`가 수정 전 실패,
  수정 후 성공했습니다. 사용자 행 잠금은 같은 계정의 토큰 회전과 직렬화합니다.

### 인증 토큰 원문 저장과 동시 refresh 사용

- 원인: refresh JWT와 비밀번호 재설정 UUID가 DB의 UNIQUE 열에 원문으로 저장돼 있었습니다.
  DB 읽기 권한이 노출되면 아직 유효한 토큰을 재사용할 수 있습니다.
- 재현: 새로 발급받은 토큰과 DB 필드가 같음을 통합 테스트에서 확인했습니다.
- 수정: Flyway V4가 기존 두 열을 SHA-256 지문으로 변환하고 CHAR(64)로 줄입니다.
  새 토큰도 원문은 응답 쿠키나 확인 응답으로만 전달하고 지문만 저장합니다. 두 토큰 모두
  난수가 포함된 고엔트로피 값이므로 저장 지문에서 원문을 추측하는 비용이 높습니다.
- 호환성: 마이그레이션 전에 발급된 원문도 V4가 같은 지문으로 바꾸므로 새 코드에서 사용 가능합니다.
  이전 앱 버전은 새 저장 형식을 읽지 못하므로 모든 기존 앱·워커를 중지하고 백업·V4 적용·새 버전
  기동 순서로 배포해야 합니다. 운영 DB에는 아직 적용하지 않았습니다.
- 재발급 경합: 서명된 JWT에서 사용자 ID를 한 번 읽고 사용자 행을 먼저 잠근 뒤 지문을 찾습니다.
  로그인·로그아웃·OAuth 기존 계정 로그인·재설정과 잠금 순서를 맞췄습니다. 같은 refresh JWT를
  동시에 재발급하는 테스트에서 한 요청만 새 토큰을 받고 다른 요청은 `InvalidTokenException`으로
  종료되며 저장된 지문이 성공 응답의 토큰과 일치했습니다.
- 회귀: `MvpIntegrationTest.refreshAndResetTokensAreNotStoredInPlaintext`,
  `refreshRotationInvalidatesPreviousToken`, `simultaneousRefreshUseHasOneWinner`,
  `PasswordResetConcurrencyTest`, `AuthConcurrencyTest`.

DB 자체에 읽기 권한이 없는 공격자로부터 토큰을 추가로 보호한다는 의미는 아닙니다. 서버가
발급한 토큰 이외의 예측 가능한 값을 이 해시 방식에 재사용하면 안 됩니다. 이메일 인증 코드는
8자리이며 이번 변경에서도 원문으로 저장됩니다. 코드 추측·동시 소비 제한은 별도 설계가 필요합니다.

### 로그인 입력과 문제 생성 저장

- 긴 로그인 아이디: `users_log.attempt_name`은 VARCHAR(16)인데 LoginRequest에는 길이 검증이
  없었습니다. 17자 아이디의 실패 로그 INSERT가 DB 제약에 걸려 기존 404 대신 HTTP 409가
  반환되는 것을 재현했습니다. 이제 아이디 16자 초과를 검증 단계에서 400으로 거절합니다.
- 이메일 요청 DTO는 DB의 255자 한도에 맞추고, 이메일·재설정 코드 검증 DTO는 실제 8자리
  숫자 형식을 확인합니다. 재설정 토큰 요청은 발급한 UUID의 최대 36자로 제한합니다.
- 새 문제 생성은 한 번 INSERT한 문제를 다시 UPDATE하고 빈 자식 테이블을 세 번 DELETE했습니다.
  전체 필드를 처음 INSERT하고 예제·테스트·언어 저장을 공통 함수로 묶어 생성 경로에서
  중복 SQL 다섯 건을 줄였습니다. 기존 번호 발급 잠금과 수정·검수 권한은 유지합니다.
- 회귀: `MvpIntegrationTest.oversizedLoginNameIsRejectedBeforeAuditInsert`,
  문제 생성·수정·동시 번호 발급을 포함한 전체 MVP 통합 테스트.

`DOCKER_JUDGE_TEST=true ./gradlew clean build`에서 전체 **72개 테스트가 실패·오류·건너뜀 없이
통과**했습니다. Docker 테스트 35개를 포함하며 Flyway V4는 전용 MySQL에 적용됐습니다.

남은 보안·운영 항목은 이메일 코드/로그인 시도의 엄밀한 속도 제한, 코드 교환 동시성,
비밀번호 재설정·로그아웃 후 기존 access 토큰의 즉시 폐기, OAuth 제공자 이메일 검증 일관성,
프로덕션 Docker 소켓의 격리, 실제 OAuth·SMTP·프록시 환경 검증과 의존성 취약점 검사입니다.

## 2026-10-02 후속 보완 — 당시 상태

이 절은 위의 72개 테스트 시점 위험 목록 다음의 당시 기록입니다. 변경은 별도 `cobweb_test` DB와
로컬 Docker에서 검증했으며, 실제 사용자 DB나 운영 비밀키에는 적용하지 않았습니다.

- V5는 사용자별 access 토큰 버전과 DB 공유 인증 시도 제한을 추가했습니다. 로그아웃·비밀번호
  변경 시 access 토큰 버전이 증가해 기존 access 토큰이 즉시 거절됩니다. 로그인과 이메일
  발급·확인에는 별도 한도가 있으며, 제한 키는 이메일·아이디·IP 원문 대신 SHA-256으로 저장합니다.
  한도는 단기 남용 방어용이지 분산 공격 방어·정식 운영 정책을 보장하지 않습니다.
- V7은 8자리 이메일 코드를 원문에서 서버 키 기반 HMAC-SHA256 지문으로 바꿉니다. 코드 검증은
  상수 시간 비교를 사용하고, 발급·확인·최종 소비는 DB guard 행 잠금으로 직렬화합니다.
  V7은 전환 시 미완료 인증 기록을 지우므로 해당 사용자는 새 코드를 발급받아야 합니다.
  서버 비밀키를 변경해도 전환 전 미완료 코드는 유효하지 않게 됩니다.
- Google은 OIDC userinfo의 `email_verified=true`인 이메일만, GitHub는 `/user/emails`의
  `primary && verified` 이메일만 신규 가입에 사용합니다. Naver 프로필 응답에는 확인 가능한
  이메일 소유권 플래그가 없어 사용자 결정에 따라 신규 자동 가입을 중단했습니다. 기존 Naver
  provider ID가 연결된 계정의 로그인은 유지합니다. 실제 제공자 연동은 검증하지 못했습니다.
- SQL 이외 12개 언어의 FUNCTION 문제는 고정된 자료형·시그니처 어댑터와 고정 컴파일 명령으로
  실행합니다. 사용자가 임의 빌드 명령을 제출할 수 없으며 12개 언어 모두 실제 Docker
  컴파일·실행 회귀를 통과했습니다. 임의 함수 시그니처나 임의 패키지 설치는 지원하지 않습니다.
- 당시 운영 Compose는 기본적으로 judge-worker 프로필을 실행하지 않고 app에 Docker 소켓을
  주지 않습니다. worker의 capability·권한 상승·PID 제한을 보강했지만, worker의 Docker
  소켓은 여전히 호스트 관리자 권한에 준합니다. 별도 채점 호스트 없이 worker를 켜는 구성은
  안전하다고 주장할 수 없습니다. 기본 구성으로만 배포하면 제출은 QUEUED에 머뭅니다.

당시 로컬 회귀 명령 `DOCKER_JUDGE_TEST=true ./gradlew clean build`는 92개 테스트(실패 0,
오류 0, 건너뜀 0)를 통과했습니다. 이 중 Docker 채점 테스트는 47개입니다.
테스트 DB에서 V5–V7을 적용했지만 실제 사용자 DB에는 적용하지 않았습니다. 이전 앱 코드는
V4 이후 토큰 저장 형식을 읽지 못하므로, 기존 앱·워커 중지와 백업·복제 DB 검증 후
당시 V2–V7 마이그레이션·동일 버전 재기동 순서가 필요했습니다. 기존 DB를 자동 baseline 하지 않습니다.

미검증 위험은 분리합니다. 스테이징 환경이 없어 실계정 OAuth, SMTP, DNS/TLS·프록시,
운영 DB 전환·백업 복구·부하 시험을 수행하지 못했습니다. 의존성·이미지 취약점의
외부 스캐너 실행은 비공개 이미지 메타데이터 전송 가능성 때문에 승인되지 않았습니다.
오프라인 빌드 성공은 CVE가 없다는 증거가 아닙니다. 그 외에도 Docker 소켓 격리,
OAuth PKCE, 분산 SMTP outbox, 워커 비정상 종료 자원 정리, 운영 관측·경보는 남아 있습니다.

## 2026-10-02 이미지 CVE 재검사 — 당시 이미지 기록

사용자 승인 후 Docker Scout를 재시도했으나 Docker 계정 로그인이 필요해 결과를 얻지 못했습니다.
대신 [공식 Trivy 릴리스](https://github.com/aquasecurity/trivy/releases/tag/v0.75.0)의 macOS ARM64
바이너리와 체크섬을 임시 디렉터리에 내려받아 SHA-256 일치를 확인했습니다. Trivy 0.75.0의
2026-10-02 갱신 취약점 DB로 두 로컬 ARM64 이미지를 `--scanners vuln --offline-scan
--disable-telemetry --image-src docker` 옵션으로 검사했습니다. 이 검사는 이미지 자체를
업로드하지 않고 로컬 Docker 이미지와 내려받은 DB를 사용합니다. 결과는 해당 DB·이미지
스냅샷에 한정되며 실제 악용 가능성 평가나 운영 호스트 검사를 대체하지 않습니다.

| 이미지 | 최초 심각/높음 | 보완 후 심각/높음 | 보완 후 중간/낮음 |
|---|---:|---:|---:|
| `cobweb-app:local` | 4 / 31 | 0 / 0 | 38 / 4 |
| `cobweb-languages:local` | 5 / 171 | 5 / 163 | 3497 / 224 |

- 앱은 Spring Boot 4.1.1로 올리고 [공식 BOM 버전 속성](https://docs.spring.io/spring-boot/appendix/dependency-versions/properties.html)으로
  Tomcat 전체를 11.0.26, Jackson 3과 2를 각각 3.1.7과 2.21.7로 맞췄습니다. JAR 내부
  파일명을 확인했으며 [Tomcat 보안 공지](https://tomcat.apache.org/security-11)에 있는 패치 버전을 사용합니다.
  Docker CLI도 27에서 29.8.2로 올렸습니다. 최종 앱 이미지 `sha256:49ac88323fa93e47…`에서
  심각·높음 등급은 0건입니다. 중간 38건은 주로 Ubuntu 기반 패키지이며 Trivy 결과에
  수정 버전이 제시되지 않았습니다.
- 채점 이미지에서는 npm을 빌드 스테이지로 옮기고 최종 이미지에서 제거했습니다.
  사용하지 않는 `kotlin-main-kts.jar`도 제외해 취약한 Plexus 부속 버전을 없앴습니다.
  Go는 공식 1.26.8 도구 체인으로 바꾸어 Ubuntu의 오래된 Go 1.22 패키지와 연결된
  중간 등급 24건을 제거했습니다. 최종 채점 이미지 `sha256:190a06136395c31c…`의
  심각·높음 168건은 모두 `linux-libc-dev` 6.8.0-146.146 패키지에 매핑됩니다.
- [Ubuntu 패키지 설명](https://packages.ubuntu.com/en/linux-libc-dev)에 따르면
  `linux-libc-dev`는 컴파일용 커널 헤더입니다. 따라서 이 매핑만으로 이미지에 취약한
  실행 커널이 들어 있다고 결론 낼 수는 없습니다. 반대로 실제 채점 호스트의 커널이
  안전하다는 증거도 아닙니다. 현재 스캔 DB에는 이 168건의 수정 패키지 버전이 없으며,
  C/C++ 컴파일에 필요한 개발 헤더를 무리하게 제거하지 않았습니다. 전용 채점 호스트의
  커널 버전·보안 업데이트와 해당 CVE의 적용 여부는 운영 배치 전에 별도 확인해야 합니다.

이 검사는 코드 취약점 전체, 비밀값 유출, 커널 격리 우회, 실제 OAuth·SMTP·TLS 동작을
검증하지 않습니다. 구체적인 배포 인프라가 없는 현재 시점에는 남은 168건을 단순히
`not_affected`로 표시하지 않습니다.

## 2026-10-03 서브에이전트 회귀 검증 — 당시 코드 상태

- 신규·수정 FUNCTION 문제는 15×15 이진 행렬, 0–14 목표 좌표, 정확한 `true`/`false`
  정답을 검증합니다. V8은 기존 활성 케이스를 검사해 호환 여부를 기록합니다. 비호환
  레거시 문제의 원본이나 언어 행은 삭제하지 않고 API에서 Python만 허용합니다.
- 채점 컨테이너 내부 `timeout`으로 모든 언어의 실제 실행에 밀리초 벽시계 한도를
  적용합니다. 실제 이미지에서 시간 초과(exit 137, OOMKilled=false)와 메모리 초과
  (exit 137, OOMKilled=true)를 구분했습니다. 표시되는 실행 시간은 Docker 기동 비용을
  포함하며 정밀 사용자 코드 시간은 아닙니다.
- 서로 다른 비밀번호 재설정 토큰도 비밀번호 변경 한 번에 모두 무효화합니다. 같은 초의
  이메일 코드는 ID로 최신 순서를 확정합니다. 미등록 주소의 비밀번호 재설정 요청은
  등록 여부와 같은 HTTP 성공 응답을 주되 코드·메일을 만들지 않습니다. 동기 SMTP
  유무에 따른 응답 시간 차이와 회원가입 중복 응답의 계정 존재 노출은 남습니다.
- 미인증 401과 인증된 권한 부족 403을 분리하고, 프런트는 401에서만 토큰 재발급을
  시도합니다. 로그아웃 중 access 토큰이 만료돼도 재발급 후 재시도하며, 실패 시 화면의
  로컬 로그인 상태를 정리합니다.
- 웹/워커 Compose를 분리했습니다. 기존 worker 프로필은 별도 호스트에서 로컬 MySQL을
  함께 띄워 웹 큐와 분리되는 결함이 있었습니다. 새 worker Compose는 공유 DB의 사설
  엔드포인트를 필수로 요구하고 웹 호스트에는 Docker 소켓을 제공하지 않습니다.
  non-web 운영 이미지 기동 시 Servlet용 SecurityFilterChain 때문에 실패하는 것도
  실제 이미지에서 재현해 Servlet 모드에서만 해당 필터를 생성하도록 수정했습니다.

전용 `cobweb_test` DB·ARM64 Docker에서 `DOCKER_JUDGE_TEST=true ./gradlew clean build`
104개 테스트(실패·오류·건너뜀 0), 프런트 Node 회귀 4건, 웹/워커 Compose 구성 검사,
수정된 운영 이미지의 non-web 기동을 확인했습니다. non-web 스모크에서는 실제 제출을
가져오지 않도록 JUDGE_ENABLED=false로 두었습니다. 원격 DB·실계정 OAuth·SMTP·프록시와
전용 워커 호스트의 커널·TLS·방화벽·백업/복구는 스테이징 환경이 없어 검증하지 않았습니다.

## 2026-10-08 회의와 후속 구현

회의에서는 긴 채점 작업을 최초 claimed_at 기준으로 만료시키는 판정 손실 위험을 먼저 처리하고,
회귀 검사를 CI에 연결하기로 했습니다. SMTP 동기 발송은 코드의 재시도·만료·소비와 암호화 저장을
함께 설계할 outbox 작업으로 남겼습니다.

- V9에 heartbeat·실행 시도 횟수·만료 조회 인덱스를 추가했습니다. 별도 실행기가 30초마다
  RUNNING·ID·토큰 조건으로 heartbeat를 갱신하며 작업 단계 전환에서도 확인합니다.
  20분간 갱신이 없으면 토큰을 폐기하고 총 3회 실행 한도에서 재큐잉합니다. 이전 토큰의
  완료 처리는 기존 행 잠금·토큰 재확인으로 결과나 accepted·solved 카운터를 변경하지 못합니다.
- 교차 리뷰에서 V9 이후 구 워커가 남긴 NULL heartbeat 작업이 복구에서 빠질 수 있음을
  확인했습니다. 해당 작업은 claimed_at·submitted_at 순으로 만료를 판단하도록 보완했습니다.
  배포는 여전히 구 앱·워커 중지, 백업, 마이그레이션, 같은 버전 재기동 순서를 따릅니다.
- 가입에서 이메일 중복 조회는 유효한 JOIN 코드 검증 뒤로 옮겼습니다. 올바른 코드 소유자는
  기존 409를 보며 중복 거절 때 인증 기록은 소비하지 않습니다. SMTP 연결 대기 5초,
  읽기·쓰기 각각 10초 상한을 추가했습니다. 동기 SMTP의 가입 여부별 지연·실패 차이는 남습니다.
- CI는 push·PR의 quick와 judge-full 작업으로 나누고 전체 작업에서 13언어·FUNCTION·HTTP
  워커 JUnit XML의 실행 여부를 확인합니다. 빠진 보고서와 skipped 결과는 실패로 처리합니다.
  운영 자격 증명은 사용하지 않고 액션은 공식 릴리스 커밋으로 고정했습니다.

수정된 Java 컴파일·JAR 생성, DB 없는 JUnit 22건(실패·오류·건너뜀 0), Node 인증 회귀 4건,
Compose 설정 3개와 Docker 보고서 누락 거절을 확인했습니다. 전체 DB 실행은 JDBC 연결 시간
초과로 중단했고 Docker API의 /version·/_ping도 응답하지 않았습니다. TCP 포트가 열린 것은
MySQL 정상 동작의 증거가 아니었습니다. V9 적용·DB 통합·실 Docker·원격 GitHub 실행은
검증 대기이며 이전 104건 통과 기록을 이번 코드 결과로 옮기지 않습니다.

새 MySQL 회귀에는 live lease 보존, 재할당 후 이전 토큰 fencing, 세 번째 만료 종료, NULL
heartbeat 복구가 포함됩니다. 현재 이 DB 테스트는 한 트랜잭션의 순차 시나리오이며 실제
병렬 DB 경합까지 검증하지는 않습니다. 워커 mock 회귀는 블로킹 prepare 중 별도 heartbeat,
케이스 실행 중 토큰 상실, 연속 갱신 실패의 중단을 검증합니다.

## 2026-10-12 신규 제작·그룹·커뮤니티 교차 검토

새 기능 구현을 세 서브에이전트와 분담하고 서로 다른 담당자가 권한·잠금·보존 경계를 교차 검토했습니다.
운영 사용자 DB를 쓰지 않고 새 전용 MySQL `cobweb_feature_test`에 V1–V12를 적용했습니다.

- **역할 관리자의 자기 승격**: MANAGE_ROLES만 가진 회원이 자기 역할을 수정해 다른 권한을 얻을 수 있었습니다.
  역할 생성/수정의 요청 권한을 현재 본인 권한 부분집합으로 제한했습니다(전역 운영자의 관리 예외 유지).
  `GroupServiceTest` 2건과 `FeatureIntegrationTest.limitedRoleManagerCannotPromoteItselfOrCreateMorePowerfulRoles`가 거절을 검증합니다.
- **시험 종료 경계의 지연 접수**: 첫 시간 검사 후 추가 검증 중 시험이 종료될 수 있습니다.
  시험 답안 INSERT SELECT에 DB UTC 시작/종료 조건을 넣어 저장 시점에도 검사합니다.
  `SubmissionContestTest`가 조건부 INSERT 및 영향 행 0의 403, 통계/ID 발급 미실행을 검증합니다.
- **감시 이벤트/제출 및 가입/시험 생성의 역순 잠금**: FK 부모 잠금까지 포함하면 교착 사이클이 가능했습니다.
  생성은 사용자→그룹, 시험 제출/감시는 시험→사용자→문제 순서를 일치시켰습니다.
  잠금 순서 단위 회귀와 실제 MySQL의 제출/이벤트 병렬 3쌍을 실행해 정상 접수를 확인했습니다.
- **시험 답안·원본 보존**: 일반 제출 경로로 시험 문제를 우회할 수 없게 하고 일반 solved/submission/accepted 집계를 제외했습니다.
  연결 문제 PUT은 모든 사용자에게 409이며 그룹 문제 보관 후에도 시험 원문은 남습니다.
  제출 상세 직접 접근에도 시간/다시보기/참여/현재 역할 검사를 적용했습니다.
- **커뮤니티 감사 자료 유출/삭제 방어**: 작성자/운영자만 현재 내용을 수정·soft-delete하며 이력·복원은 운영자만 가능합니다.
  버전 충돌은 409이고 생성/수정/삭제/복원마다 이전/이후 원문·작성자/수정자 이름·문제 연결 스냅샷을 보존합니다.
  삭제 댓글은 공개 API에서 본문을 가리고 자식 답글은 유지합니다. 부모별 20개 페이지로 무제한 응답을 피합니다.
  그룹/비공개 문제는 공개 질문 연결로 선택할 수 없고 이후 비공개 전환의 문제 번호도 숨깁니다.
- **입력/표시 경계**: 제작 JSON UTF-8 2MiB, 제출·참고 정답 UTF-8 64KiB, 고정 허용 언어/메타데이터 파일명만 받습니다.
  모든 사용자 게시물/코드는 textContent/value로 표시하며 실행 가능한 HTML을 만들지 않습니다.
  커뮤니티 신규 시각은 DB UTC 저장과 ISO Z 응답으로 시간대 오표시를 막았습니다.
- **클라이언트 지연 응답**: 로그아웃·다른 글/그룹 선택 후 과거 관리자/회원 응답이 DOM을 다시 채우지 않도록
  요청 세대 검사를 넣고 지연 응답→clear 회귀를 추가했습니다. 실제 화면에서 찾은 그룹 목록/시험 제출의
  중복 렌더러 함수명도 분리하고 JS 검사에서 재발을 막습니다.

최종 전체 빌드: **159개, 실패 0·오류 0·건너뜀 0**. 13언어 실제 Docker 실행,
FUNCTION·HTTP 워커 결과 가드, Node 회귀 4개 스크립트와 Compose 구성 3개도 통과했습니다.
이 결과는 로컬 기능/회귀 검증이며 부하 시험·컨테이너 탈출 불가능성·외부 연동·운영 안전 전체를 보증하지 않습니다.
클라이언트 감시 이벤트는 조작 가능한 참고 신호이며 OS 스크린샷 완전 차단이나 자동 실격 근거로 취급하지 않습니다.

## 2026-10-13 재검증: 기존 통과 검사 밖의 경계 결함

기존 전체 159개가 재실행에서도 통과했지만 독립 Node VM 및 SQL 잠금 검사로 다음 결함을 확인했습니다.
사용자가 수정까지 승인한 뒤 보완하고 회귀를 추가했습니다. 운영 사용자 DB·외부 제공자는 사용하지 않았습니다.

### 계정 전환 후 비공개 자료와 인증 입력 잔류

- 이전 운영자 편집 API 응답을 지연시킨 뒤 일반 사용자로 전환하면 openEditor가 이전 비공개 테스트/정답을
  새 계정의 작성 화면에 채웠습니다. 이전 mine/admin 목록·프로필·제출·재발급에도 같은 세대 누락이 있었습니다.
- 로그인 폼의 비밀번호, 미완료 재설정 토큰, 풀이 소스, 프로필/제출 DOM은 기존 clearPrivateViews의 정리 대상에서 빠졌습니다.
- 수정: 로그인/로그아웃의 세션 세대, 화면/요청 세대 검사와 모든 개인 DOM·인증 입력·resetToken 초기화.
  늦은 재발급은 현재 세션의 토큰을 덮어쓰지 못하며, 이전 편집/목록/판정 응답은 표시하지 않습니다.
- `app-session.test.cjs`에 지연 응답·권한 복원·폼/소스 초기화·화면 이동·판정 경합을 넣었습니다.
  실제 브라우저에서도 로그인 비밀번호 초기화, 로그아웃 후 소스/프로필/관리자 미리보기 제거, 다른 계정 진입을 확인했습니다.

### 문제 상태 이력 FK의 역순 잠금

- 문제 검수/보관은 문제 X-lock 후 checker_id FK의 사용자 S-lock을, 제출은 사용자 X-lock 후 문제 X-lock을 요청했습니다.
- 동일 전용 레코드의 두 SQL 트랜잭션에서 MySQL ERROR 1213을 재현했습니다. 재현 트랜잭션은 모두 롤백해 변경 행 0을 확인했습니다.
  이는 서비스 SQL 잠금 순서의 재현이며 수정 전 전체 HTTP 동시 요청을 실험한 것은 아닙니다.
- 수정: 문제 create/update/requestReview/review/setVisibility가 사용자 행을 먼저 잠급니다.
  ProblemLockOrderTest와 실제 MySQL의 동일 소유자 검수/시험 제출 병렬 3쌍을 통과했습니다.

### 시험 시각 정밀도와 UTC DTO

- 시험 시작은 DATETIME(6)인데 제출 저장은 DATETIME(0)+UTC_TIMESTAMP()여서 유효 접수의 저장 시각이 시작 전으로 잘릴 수 있었습니다.
  전용 DB에서 UTC_TIMESTAMP(6)과 UTC_TIMESTAMP()를 비교해 소수초 손실을 확인했습니다.
- V13은 기존 값을 보존하며 submitted_at/judged_at을 DATETIME(6)으로 확장합니다. 접수·완료·최종 lease 오류는
  시험이면 UTC_TIMESTAMP(6), 일반 제출이면 기존 서버 시각을 유지합니다. 시험 judgedAt도 ISO UTC Z 문자열입니다.
- 컬럼 정밀도, submitted_at≥starts_at, 실제 완료/UTC DTO 회귀가 통과했습니다. 과거 제출의 잃어버린 소수초를 복원했다고 주장하지 않습니다.

### 커뮤니티 문제 연결과 편집자 이력 FK의 역순 잠금

- 게시글의 problem_id 변경이 문제 부모 S-lock을 확보한 뒤 revision의 editor_id FK가 사용자 S-lock을 요청하면,
  같은 운영자의 사용자 X-lock→문제 X-lock 공개 설정 변경과 교착할 수 있었습니다.
- 전용 DB의 실제 UPDATE와 이력 INSERT를 병렬 트랜잭션으로 실행해 ERROR 1213을 재현했습니다.
  모두 롤백하여 연결 문제 원상태와 probe 이력 0건을 확인했습니다. 수정 전 전체 HTTP 병렬 실험은 아닙니다.
- 게시글/댓글 create/update/delete/restore 8개 쓰기 경로는 사용자행을 먼저 잠급니다. 사용자행 부재는 401이며,
  null 요청·기존 운영자 권한 검사는 유지합니다. 단위 잠금 순서와 실제 서비스의 같은 운영자/서로 다른 작성자·운영자
  각 3쌍 병렬 연결/비공개 전환을 회귀에 포함했습니다. 숨김이 먼저 완료되면 정상 연결 거절 400을 허용합니다.

그룹/커뮤니티의 역순 응답, 저장 완료 후 과거 화면 복귀, 다른 문제의 판정 표시, 더보기 이중 클릭도 요청 세대/중복 억제로 보완했습니다.
누락된 시험 카운트다운 ID를 수정하고 실제 표시를 확인했습니다.
설정 갱신으로 활성 시험 화면을 다시 그릴 때는 동일 시험/문제의 코드·언어만 임시 보존합니다.
서버 응시 권한과 문제 재조회가 성공한 뒤에만 복원하고, 종료·권한 거절·로그아웃·이탈에서는 폐기합니다.
이 경계는 Node DOM 회귀로 검증했으며 브라우저 DB 자동 저장 기능은 추가하지 않았습니다.

기존 테스트 DB V12→V13과 새 cobweb_revalidation_test V1→V13을 검증했습니다.
최종 Java/DB/Docker 전체는 **171개, 실패·오류·건너뜀 0**, 13언어·FUNCTION·HTTP 워커 가드,
Node 회귀 5개 스크립트와 Compose 3개도 통과했습니다. 운영 배포·부하·SMTP/OAuth 실연동·원격 CI는 별도입니다.

## 2026-10-13 기능 연동과 별도 자동 워커 검증

- 기존 HTTP 채점 회귀는 같은 JVM에서 `worker.tick()`을 직접 호출하므로 독립 워커의 자동 스케줄을 증명하지 않았습니다.
  새 `cobweb_linkage_test`에 V1–V13을 적용하고 루프백 웹 JAR(채점 OFF)과 non-web 워커 JAR(채점 ON)을 별도 실행했습니다.
  `.env`와 실제 비밀값을 사용하지 않았고 워커 Flyway는 OFF입니다. 전용 스크립트는 대상 고정·직접 연결·리다이렉트 차단을 적용합니다.
- 실제 HTTP 경로 13개가 통과했습니다. Python STDIN·SQL·1000번 FUNCTION·시험 C++ 자동 채점 4건이 ACCEPTED,
  attempts=1·종료 시 token/heartbeat NULL·FUNCTION 10케이스 기록을 DB에서도 확인했습니다.
  시험 시작 전/종료 후 거절, 참여자만 다시보기, 결과 담당자 권한, 일반 통계 불변, soft-delete/복구 이력을 포함합니다.
- **시험 문제의 관리자 편집 부작용**: 실제 버튼 처리의 Node VM 재현에서 usedInContest 문제를 PRIVATE로 먼저 바꿔
  그룹에서는 ARCHIVED가 되었지만 편집은 막혔습니다. 버튼 비활성과 handler 가드를 추가해 PATCH 자체를 차단합니다.
  Node 회귀와 실제 브라우저의 비활성/안내 표시를 확인했습니다. 명시적인 운영자 공개 범위 변경은 기존대로 유지합니다.
- **그룹 목록의 제출 동선 불일치**: 담당자 목록은 시험 연결 문제를 보여도 일반 제출은 거절됩니다.
  usedInContest 응답과 코테 전용 안내로 잘못된 일반 풀이 진입을 없앴습니다. 회원 목록 은닉 조건은 유지합니다.
- **비공개 연결의 뜻하지 않은 해제**: 기존 JAR의 HTTP에서 공개 문제 연결→숨김→제목 수정의 null 요청으로
  problem_id가 NULL로 바뀌고 재공개 후에도 연결이 돌아오지 않는 것을 재현했습니다. 원래 연결 정보는 이력에 남았습니다.
  작성자에게 비공개 번호를 보내지 않고 연결 숨김 여부만 알려 명시 보존 플래그로 기존 ID/번호 스냅샷을 유지합니다.
  타인 수정은 403, 새 비공개 번호 지정은 400, 공개 연결의 명시 제거는 기존 동작입니다.
- 새 선택 필드의 초기 primitive boolean 구현은 구형 JSON에서 누락하면 400이 나왔습니다. 별도 JAR HTTP 검증이 이를 포착했고
  nullable Boolean과 필드 생략 회귀로 수정했습니다. 최종 스크립트의 기존 5필드 요청도 통과했습니다.
- 로그인 후 그룹/커뮤니티/공지 경로 복귀를 보완했습니다. 기존 Docker 테스트의 전역 큐 단발 tick 가정은
  선행 제출을 정상 FIFO로 처리하는 제한된 루프로 바꿨습니다. 운영 큐나 제출 데이터를 임의로 정리하지 않았습니다.
- 최종 전체는 **172개 실패/오류/건너뜀 0**, 13언어/12 FUNCTION 결과 가드, Node 5개·Compose 3개도 통과했습니다.
  브라우저에서 그룹 경로 로그인 복귀, 시험 편집 차단, 커뮤니티 관련 문제→풀이 이동을 확인했습니다.
  두 JVM은 같은 로컬 호스트입니다. 운영 DB/다중 호스트/부하/OAuth·SMTP/원격 CI는 여전히 별도 검증입니다.
