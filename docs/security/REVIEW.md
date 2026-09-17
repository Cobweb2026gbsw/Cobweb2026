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
