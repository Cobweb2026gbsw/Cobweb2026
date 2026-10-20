# Cobweb 백엔드 구현 명세

이 문서는 프런트엔드 연동과 백엔드 유지보수를 위한 현재 구현 명세입니다. 2026년 10월 13일 작업 트리의 컨트롤러, DTO, 서비스, 보안 필터, Flyway SQL, 채점 실행기를 기준으로 작성했습니다. 커밋되지 않은 변경도 포함하며 운영 배포와 로컬 검증을 구분합니다.

현재 구현 범위는 회원 인증, 이메일 인증, 비밀번호 재설정, OAuth 연동, 문제 제작과 관리, 코드 제출과 비동기 채점, 그룹 역할·시간제 시험, 커뮤니티·공지와 수정 이력, 프로필과 랭킹입니다. 아래 기존 API 표에 더해 신규 기능 절의 API가 제공됩니다. SQL 문제는 SQLite, 일반 입출력·함수형 문제는 SQL을 제외한 12개 언어를 지원합니다.

인증·함수형 호환성 보강은 별도 테스트 DB의 Flyway V4–V8에서 검증하며 실제 사용자 DB는 변경하지 않았습니다. 검증 이력의 날짜와 문서 작성일은 다르므로 마지막 검증 절에서 구분합니다.

## 문서 구성

- 공통 규약과 전체 API 목록
- 인증과 권한 및 회원 API
- 문제 작성과 검수 API
- 언어와 제출 API
- 채점 처리와 자원 제한
- 프로필과 랭킹 API
- 데이터베이스 명세
- 오류 응답과 운영 설정
- 검증 이력과 미구현 사항

## 기술 구성

| 구분 | 현재 구현 |
|---|---|
| 언어와 프레임워크 | Java 21, Spring Boot 4.1.1, Spring MVC |
| 빌드 | Gradle Kotlin DSL, Gradle Wrapper |
| 데이터베이스 | MySQL 8.4 기반 Compose 구성 |
| 영속성 | 인증과 회원은 Spring Data JPA, 문제·제출·프로필·랭킹은 JdbcTemplate |
| 스키마 관리 | Flyway V1–V13. Hibernate `ddl-auto=none` |
| 인증 | Spring Security, JJWT 0.12.6, BCrypt |
| 입력 검증 | Jakarta Bean Validation |
| 메일 | JavaMailSender, Gmail SMTP 설정, STARTTLS |
| 외부 로그인 | RestClient 기반 Google, GitHub, Naver OAuth 클라이언트 |
| 비동기 채점 | MySQL 제출 테이블을 큐로 사용, Spring Scheduled 워커 |
| 코드 실행 | Docker CLI, 고정 Python 실행기, 다중 언어 이미지 |
| 배포 구성 | MySQL, 웹 앱, 채점 워커, Caddy 리버스 프록시 |

HTTP 요청 스레드는 코드 제출을 저장할 뿐 사용자 코드를 실행하지 않습니다. 별도 워커가 DB 큐에서 제출을 가져와 컴파일하고 테스트 케이스를 실행한 후 판정과 통계를 트랜잭션으로 반영합니다. 별도의 Redis, 메시지 브로커, 객체 저장소는 사용하지 않습니다.

## 공통 API 규약

### 요청과 식별자

- API 경로는 `/api`로 시작합니다. 기본 Spring Boot 포트는 8080이며 설정으로 변경할 수 있습니다.
- 본문이 있는 API는 JSON과 `Content-Type: application/json`을 사용합니다. OAuth 콜백은 예외입니다.
- 인증이 필요한 요청은 `Authorization: Bearer <accessToken>`을 보냅니다.
- 문제의 외부 식별자는 `problem_number`입니다. URL의 `{number}`와 제출 요청의 `problemNumber`는 내부 PK인 `problems.id`가 아닙니다.
- 회원가입 반환값과 제출 반환값은 각각 회원 PK와 제출 PK입니다. 생성한 문제의 반환값은 문제 번호입니다.
- 언어 ID와 enum 값은 대문자이며 대소문자를 구분합니다. OAuth 경로의 provider만 대문자로 변환하여 처리합니다.
- 요청 DTO는 주로 camelCase입니다. JdbcTemplate 조회 응답은 SQL 컬럼명인 snake_case를 그대로 사용하며, `solvedProblems`, `examples`, `languages`, `cases`는 별도의 키입니다.
- 문자열 `@Size`는 Java 문자열 길이 제한입니다. 네트워크 바이트 수나 UTF-8 파일 크기 제한과 같지 않습니다.

### 공통 응답

일반 컨트롤러의 성공 응답은 HTTP 200입니다. 생성 API도 현재 201이 아닌 200을 반환합니다. 수정이나 로그아웃의 성공도 204가 아니라 `data: null`을 가진 200입니다.

```json
{"success":true,"data":1001,"message":null}
```

서비스·컨트롤러 예외를 `GlobalExceptionHandler`가 처리하면 다음 형식입니다.

```json
{"success":false,"data":null,"message":"지원하지 않는 언어입니다."}
```

예외는 두 가지입니다. OAuth 콜백 성공은 JSON이 아닌 리다이렉트이며, Spring Security 필터에서 거절된 요청은 이 공통 JSON 형식을 보장하지 않습니다. 클라이언트는 응답 본문을 읽기 전에 HTTP 상태와 Content-Type을 확인해야 합니다. 인증 정보가 없거나 만료되면 401, 인증된 사용자의 권한이 부족하면 403을 반환합니다. 프런트는 401에서만 refresh 재발급을 시도합니다.

`ErrorCode`의 enum 이름은 현재 응답에 별도 `code` 필드로 반환되지 않습니다. `message`만 내려옵니다. 날짜는 DB DATETIME/JDBC 값의 직렬화에 의존하며, 전 API에 걸친 UTC 또는 특정 문자열 포맷 계약은 별도로 정의되어 있지 않습니다.

### 페이지 처리

문제 목록과 랭킹은 `page`가 0부터 시작하고 페이지당 30건입니다. `page`는 0 이상 10000 이하로 보정되며 음수는 0, 초과 값은 10000으로 처리됩니다. 응답은 배열만 반환하고 `total`, `totalPages`, `hasNext`는 없습니다. 내 제출은 최근 50건 고정이며 페이지 매개변수가 없습니다.

## 전체 API 목록

아래 권한에서 회원은 유효한 토큰과 ACTIVE 계정을 뜻하며, 운영자는 OPERATOR 또는 DEVELOPER입니다. 공개 상세 조회에도 리소스별 접근 검사가 적용됩니다.

| 메서드 | 경로 | 권한 | 기능 | 성공 시 data |
|---|---|---|---|---|
| POST | `/api/email-verifications` | 공개 | 이메일 코드 발송 | null |
| POST | `/api/email-verifications/verify` | 공개 | 이메일 코드 확인 | null |
| POST | `/api/auth/signup` | 공개 | 일반 회원가입 | 회원 ID 숫자 |
| POST | `/api/auth/login` | 공개 | 아이디와 비밀번호 로그인 | TokenResponse |
| POST | `/api/auth/reissue` | refresh 쿠키 | 토큰 재발급 | TokenResponse |
| POST | `/api/auth/logout` | 회원 | refresh 토큰 폐기 | null |
| POST | `/api/auth/password-reset/request` | 공개 | 재설정 코드 요청 | null |
| POST | `/api/auth/password-reset/verify-code` | 공개 | 코드를 재설정 토큰으로 교환 | resetToken 객체 |
| POST | `/api/auth/password-reset/confirm` | 공개 | 비밀번호 변경 | null |
| GET | `/api/auth/oauth/{provider}/authorize-url` | 공개 | OAuth 인가 URL 생성 | authorizeUrl 객체 |
| GET | `/api/auth/oauth/{provider}/callback` | state 쿠키와 인가 코드 | 소셜 가입 또는 로그인 | JSON 대신 리다이렉트 |
| GET | `/api/problems` | 공개 | 공개 문제 목록 | 문제 요약 배열 |
| GET | `/api/problems/{number}` | 리소스 접근 권한 | 문제 상세 | 문제 상세 객체 |
| POST | `/api/problems` | 회원 | 초안 생성 | 문제 번호 숫자 |
| PUT | `/api/problems/{number}` | 작성자 또는 운영자 | 초안 또는 반려 문제 수정 | null |
| POST | `/api/problems/{number}/review-request` | 작성자 또는 운영자 | 검수 요청 | null |
| POST | `/api/problems/{number}/review` | 운영자 | 공개 승인 또는 반려 또는 보관 | null |
| GET | `/api/languages` | 공개 | 지원 언어 조회 | 언어 객체 배열 |
| POST | `/api/submissions` | 회원 | 코드 제출 접수 | 제출 ID 숫자 |
| GET | `/api/submissions` | 회원 | 내 최근 제출 | 제출 요약 배열 |
| GET | `/api/submissions/{id}` | 제출자 또는 운영자 | 제출 결과 | 제출 결과 객체 |
| GET | `/api/ranking` | 공개 | 사용자 랭킹 | 사용자 요약 배열 |
| GET | `/api/profiles/{username}` | 공개 | 공개 프로필 | 프로필 객체 |
| GET | `/api/me` | 회원 | 내 공개 프로필 형식 조회 | 프로필 객체 |
| PATCH | `/api/me` | 회원 | 소개와 GitHub URL 변경 | null |

## 인증과 권한

### 계정과 역할

| 값 | 의미와 동작 |
|---|---|
| USER | 일반 회원. 문제 초안 생성, 본인 문제 편집·검수 요청, 제출과 본인 결과 조회 |
| OPERATOR | 일반 기능에 더해 모든 문제 조회, 수정 가능한 문제 편집, 검수, 타인 제출 결과 조회 |
| DEVELOPER | 현재 API 권한 판정에서는 OPERATOR와 동일 |
| ACTIVE | 일반 로그인과 JWT 인증, refresh 재발급 가능 |
| TIMEOUT, STOPPED, BANNED | 일반 로그인·JWT 인증·refresh 재발급 거절 |

일반 회원가입의 기본값은 USER, ACTIVE, INITIATE 5이며 제출 수와 해결 수는 0입니다. 역할 변경, 정지 처리, 정지 자동 해제 API는 없습니다. `login_failed_count`는 증가하지만 횟수에 따른 자동 잠금은 없습니다.

JWT 필터는 토큰 서명과 만료를 검증한 후 username으로 DB의 현재 계정을 다시 읽습니다. 현재 사용자 ID가 JWT subject, `tokenVersion`이 현재 사용자 버전과 일치해야 하며 현재 상태와 권한을 사용합니다. 같은 username으로 다시 가입한 다른 계정이나 삭제된 계정의 토큰을 유효한 인증으로 보지 않습니다. refresh 토큰에는 username과 tokenVersion이 없어 Bearer access 인증에 사용할 수 없습니다.

### 토큰 규격

| 항목 | 현재 값과 처리 |
|---|---|
| access 만료 | 기본 1,800,000 ms, 30분 |
| access 클레임 | subject는 사용자 ID, username, role, tokenVersion, issuedAt, expiration |
| refresh 만료 | 기본 1,209,600,000 ms, 14일 |
| refresh 클레임 | subject는 사용자 ID, UUID jti, issuedAt, expiration |
| 서버 저장 | refresh 원문의 SHA-256 지문과 만료 시각을 DB에 저장. 사용자당 1개 UNIQUE |
| refresh 회전 | 로그인·재발급 시 기존 토큰 삭제와 flush 후 새 토큰 저장 |
| refresh 쿠키 | `refreshToken`, HttpOnly, Secure, SameSite=Lax, Path=/ |
| 세션 | STATELESS. 서버 HTTP 세션을 로그인 상태 저장용으로 사용하지 않음 |

토큰 응답 예시는 다음과 같습니다. `expiresIn`은 초가 아니라 밀리초입니다.

```json
{
  "success": true,
  "data": {
    "accessToken": "<발급된 JWT>",
    "tokenType": "Bearer",
    "expiresIn": 1800000
  },
  "message": null
}
```

로그아웃과 비밀번호 재설정은 refresh 토큰을 폐기하고 `users.access_token_version`을 증가시켜 이전 access 토큰을 다음 요청부터 거절합니다. 이 정책은 해당 계정의 모든 기존 access 토큰에 적용됩니다. V5 이전 토큰에는 버전 클레임이 없어 새 필터가 거절합니다. Secure 쿠키는 운영 HTTPS를 전제로 하며 로컬 HTTP에서의 수락은 브라우저 정책을 확인해야 합니다.

### 이메일 인증

`POST /api/email-verifications`

| 필드 | 타입 | 필수 | 검증 |
|---|---|---|---|
| email | string | 예 | 비공백, 이메일 형식, 최대 255자 |
| purpose | string | 예 | JOIN 또는 PASSWORD_RESET |

```json
{"email":"member@example.com","purpose":"JOIN"}
```

SecureRandom으로 숫자 8자리 코드를 만들며 앞자리 0을 보존하므로 문자열로 취급합니다. 유효 기간은 5분입니다. 이메일과 목적별 10분 동안 발송 3회와 확인 10회의 DB 공유 제한을 적용합니다. DB에는 원문 대신 이메일·목적·코드에 대한 서버 키 기반 HMAC-SHA-256 지문만 저장합니다. SMTP로 동기 발송하며 발송 실패 시 인증 기록 트랜잭션은 롤백됩니다. SMTP 연결 대기는 5초, 읽기·쓰기 대기는 각각 10초로 제한합니다. 독립 제한 횟수는 실패해도 유지되어 일시적 SMTP 장애 시 재시도도 제한될 수 있습니다. DB와 외부 SMTP의 원자적 커밋이나 메일 outbox는 없습니다.

`POST /api/email-verifications/verify`

| 필드 | 타입 | 필수 | 검증 |
|---|---|---|---|
| email | string | 예 | 비공백, 이메일 형식, 최대 255자 |
| code | string | 예 | 숫자 8자리 |
| purpose | string | 예 | JOIN 또는 PASSWORD_RESET |

```json
{"email":"member@example.com","code":"01234567","purpose":"JOIN"}
```

동일 이메일과 목적의 `created_at` 최신 기록만 잠금 조회합니다. 코드 지문과 만료를 확인하고 `is_verified=true`로 변경합니다. 기록이 없으면 404, 불일치 또는 만료는 400입니다. 인증 완료 상태도 원래 코드 만료 시각까지만 유효합니다. 발급·확인·소비를 이메일/목적별 DB 잠금 행으로 직렬화합니다. 가입 또는 재설정 토큰 교환에서 소비하면 해당 이메일과 목적의 과거 인증 기록까지 모두 삭제합니다.

별도의 `auth_rate_limits` 행은 발송 기록이 소비되어도 남고 `REQUIRES_NEW` 트랜잭션으로 갱신되므로 잘못된 코드 예외 후에도 시도 수가 유지됩니다. 창은 첫 요청부터 10분이며 만료 행은 주기적으로 정리합니다. 일반 발송 API 자체는 해당 이메일의 가입 여부를 확인하지 않습니다.

### 일반 회원가입

`POST /api/auth/signup`

| 필드 | 타입 | 필수 | 검증 |
|---|---|---|---|
| username | string | 예 | 비공백, 최대 16자. 별도 허용 문자 정규식 없음 |
| email | string | 예 | 비공백, 이메일 형식, 최대 255자 |
| password | string | 예 | 비공백, 최소 8자. DTO상 최대 길이·문자 조합 규칙 없음 |
| verificationCode | string | 예 | 숫자 8자리 |

```json
{"username":"sample_user","email":"member@example.com","password":"example-password","verificationCode":"01234567"}
```

먼저 JOIN 코드 확인 API를 성공시켜야 합니다. 가입 시에도 아직 유효한 verified 상태와 요청에 들어온 실제 코드를 다시 검사합니다. 아이디 중복은 409이며 이메일 중복 409는 유효 코드 검증을 통과한 요청에만 반환합니다. 이메일 미인증이나 잘못된 코드는 400입니다. 중복 이메일은 코드 소비 전에 거절하고, 정상 가입의 인증 기록 소비와 BCrypt 비밀번호 저장은 같은 트랜잭션이며 실패 시 함께 롤백합니다. 성공 data는 회원 ID이고 자동 로그인이나 토큰 발급은 하지 않습니다.

이메일 소문자화나 아이디 공백 제거 같은 정규화는 별도로 하지 않습니다. 중복 비교에는 DB collation도 영향을 주므로 대소문자 구분 정책을 API만으로 단정하면 안 됩니다.

### 로그인과 재발급과 로그아웃

`POST /api/auth/login` 요청은 `{"username":"sample_user","password":"example-password"}`입니다. 두 필드 모두 비공백 문자열이고 username은 최대 16자입니다. 사용자 행을 비관적 잠금으로 조회하여 상태와 비밀번호를 검사합니다.

- 없는 사용자: 404, 사용자 ID 없는 FAIL_USER_NOT_FOUND 감사 로그 저장.
- ACTIVE가 아닌 사용자: 403, BANNED_USER 로그 저장.
- 비밀번호 불일치: 401, 실패 횟수 증가와 FAIL_PASSWORD_MISMATCH 로그 저장.
- 성공: 실패 횟수 0, 마지막 로그인 시각 갱신, SUCCESS 로그, 토큰 발급.

예상된 로그인 실패 예외는 실패 횟수와 로그를 롤백하지 않습니다. 로그인은 아이디당 10분에 20회, 연결 IP당 10분에 1000회로 DB 공유 제한을 둡니다. 감사 IP는 `request.getRemoteAddr()`이며 클라이언트가 보낸 X-Forwarded-For를 신뢰하지 않습니다. 프록시 뒤에서는 여러 사용자가 같은 IP 한도를 공유할 수 있어 IP 한도는 넓은 2차 상한입니다. User-Agent는 최대 255 Unicode 코드 포인트로 자릅니다. 이 감사 로직은 일반 로그인 경로이며 OAuth 로그인에 동일하게 적용되는 것은 아닙니다.

`POST /api/auth/reissue`는 JSON 본문이나 Bearer 토큰이 아니라 `refreshToken` 쿠키를 읽습니다. JWT를 한 번 파싱해 서명·만료·사용자 ID를 확인하고 해당 사용자 행을 잠근 뒤 DB의 SHA-256 지문·DB 만료·현재 계정 상태를 검사합니다. 쿠키 누락, 저장되지 않은 토큰, 잘못된 토큰은 401이며 정지 계정은 403입니다. 사용자당 하나의 refresh 토큰만 유지하므로 다른 로그인으로 이전 refresh 토큰이 무효화됩니다. 동일 토큰의 동시 재발급에서는 사용자 행 잠금으로 한 요청만 성공하고 나머지는 사용된 토큰으로 거절됩니다. 로그아웃도 사용자 행을 먼저 잠급니다.

`POST /api/auth/logout`은 Bearer 인증이 필요합니다. 현재 사용자의 refresh 행을 삭제하고 access 토큰 버전을 증가시키며 동일 속성의 쿠키를 Max-Age=0으로 내려줍니다. 성공 data는 null입니다.

### 비밀번호 재설정

| 순서 | 경로 | 요청 JSON | 성공 data |
|---|---|---|---|
| 1 | `/api/auth/password-reset/request` | `{"email":"member@example.com"}` | null |
| 2 | `/api/auth/password-reset/verify-code` | `{"email":"member@example.com","code":"01234567"}` | `{"resetToken":"<UUID>"}` |
| 3 | `/api/auth/password-reset/confirm` | `{"resetToken":"<UUID>","newPassword":"new-example-password"}` | null |

모두 POST와 공개 경로입니다. 이메일은 비공백·이메일 형식·최대 255자, code는 숫자 8자리, resetToken은 비공백·최대 36자, newPassword는 비공백·최소 8자입니다. 발급된 재설정 토큰은 원문 대신 SHA-256 지문으로 DB에 저장합니다.

1. request는 등록된 주소에만 PASSWORD_RESET 코드를 발송합니다. 미등록 주소도 같은 성공 응답을 주며, 두 공개 발송 경로의 시도 제한을 공유합니다. 동기 메일 발송에 따른 응답 시간 차이는 별도로 해결되지 않았습니다.
2. verify-code는 코드를 검증하고 인증 기록을 소비한 후 유효 기간 30분인 UUID 토큰을 저장합니다.
3. confirm은 토큰에서 사용자 ID를 찾고 사용자 행을 먼저 잠근 뒤 토큰 행을 비관적 잠금으로 다시 확인합니다. 미사용·미만료라면 비밀번호를 BCrypt로 바꾸고 그 계정의 모든 재설정 토큰과 refresh 토큰을 폐기합니다.

동일 토큰뿐 아니라 서로 다른 유효한 resetToken의 동시 소비도 사용자 잠금으로 직렬화되어 한 번만 성공합니다. 잘못됐거나 사용됐거나 만료된 토큰은 401입니다. 비밀번호 변경 시 access 토큰 버전도 증가합니다.

### OAuth 연동

지원 provider는 `google`, `github`, `naver`이며 경로는 대소문자 변환을 허용합니다. 알 수 없는 provider는 400입니다.

`GET /api/auth/oauth/{provider}/authorize-url`은 `{"authorizeUrl":"<제공자 인가 URL>"}`을 반환하고 `oauthState` 쿠키를 설정합니다. state는 UUID, 쿠키 유효 기간은 5분, HttpOnly·Secure·SameSite=Lax·Path=/api/auth/oauth입니다.

`GET /api/auth/oauth/{provider}/callback?code=...&state=...`은 code 쿼리, state 쿼리, 저장된 oauthState 쿠키를 사용합니다. state와 쿠키가 없거나 다르면 401입니다. 비교 성공 후 쿠키를 삭제하고 인가 코드를 외부 토큰으로 교환하여 사용자 정보를 읽습니다. state 발급 이력을 서버 DB에 저장하지는 않습니다.

| 제공자 | 현재 외부 연동 |
|---|---|
| Google | scope `openid email profile`, token POST, userinfo의 sub와 `email_verified=true`인 email만 신규 계정에 사용 |
| GitHub | scope `read:user user:email`, token POST, `/user`의 ID 확인. 신규 이메일은 항상 `/user/emails`의 primary·verified 항목만 사용 |
| Naver | 인가 state 전달, token GET에 state 포함, 프로필 ID로 기존 계정 로그인만 허용. 신규 가입은 중단 |

기존 `(provider, provider_id)` 계정은 사용자 행을 잠그고 ACTIVE 여부를 확인한 뒤 토큰을 발급합니다. 정지 계정은 403입니다. Google/GitHub 신규 계정은 확인된 이메일이 필요하며 없으면 400입니다. 네이버 신규 가입은 확인 상태 필드가 없어 403으로 중단하고 기존 계정 로그인만 유지합니다. username은 제공자 접두사와 식별자 해시로 16자 이내에서 생성하며 중복 후보에는 숫자를 붙입니다. 초기 비밀번호는 무작위 UUID의 BCrypt 값입니다. 기존 이메일 계정과 자동으로 합치지 않으며 이메일 UNIQUE 충돌은 409가 될 수 있습니다.

성공 시 refresh 쿠키를 설정하고 `/index.html#accessToken=<URL 인코딩 JWT>`로 리다이렉트합니다. access 토큰은 쿼리가 아닌 fragment에 담깁니다. 프런트엔드는 fragment를 읽고 주소에서 지워야 합니다.

현재 제약은 다음과 같습니다.

- 공급자 응답의 실제 신뢰성은 실계정 연동에서 확인해야 합니다. 네이버 신규 가입 재개에는 별도 이메일 소유권 확인 흐름이 필요합니다.
- OAuth PKCE, 계정 연결·해제 API, 외부 실패 응답의 세부 표준화, 서버 저장형 일회용 state, 운영용 일회용 교환 코드 전달은 미구현입니다.
- 실계정 로그인과 제공자 콘솔 설정은 이번 문서 작성에서 검증하지 않았습니다.

## 문제 API

### 공개 목록

`GET /api/problems?page=0&search=제목`

PUBLISHED이면서 PUBLIC인 문제만 반환합니다. 제목에 SQL LIKE `%search%`를 적용하며 problem_number 오름차순입니다. 검색값은 바인딩하지만 `%`와 `_`를 검색용 리터럴로 이스케이프하지 않으므로 LIKE 와일드카드로 동작합니다.

data의 각 원소는 다음 필드입니다.

| 필드 | 타입 | 설명 |
|---|---|---|
| problem_number | integer | 외부 문제 번호 |
| problem_title | string | 제목 |
| rank_type | string | 문제 등급 |
| rank_int | integer | 1부터 5까지의 세부 등급 |
| submission_count | integer | 접수된 제출 횟수 |
| accepted_count | integer | 정답 제출 횟수. 고유 해결자 수가 아님 |

### 상세와 접근 권한

`GET /api/problems/{number}`는 아래 경우에 허용합니다. 그 외에는 문제 존재 여부를 숨기기 위해 404입니다.

- OPERATOR 또는 DEVELOPER.
- 해당 문제 작성자.
- PUBLISHED·PUBLIC인 문제의 모든 방문자.
- PUBLISHED·GROUP이고 해당 그룹 VIEW_PROBLEMS 권한이 있는 회원, 또는 그룹 EDIT/REVIEW_PROBLEMS 담당자.

GROUP은 현재 DB 역할 권한을 검사합니다. 시험 연결 문제는 일반 조회에서 응시자에게 숨기며 시험 전용 API의 시간·참여·다시보기 검사를 거칩니다. HTTP 공개 경로 설정이 PRIVATE 문제나 비공개 테스트를 공개한다는 뜻은 아닙니다.

| 응답 필드 | 타입 |
|---|---|
| problem_number | integer |
| problem_title, description, input_description, output_description | string |
| constraints_text | string 또는 null |
| rank_type, execution_mode, status | string |
| rank_int, time_limit_ms, memory_limit_mb | integer |
| examples | `{input_text: string, output_text: string, explanation: string 또는 null}` 배열 |
| languages | 허용 언어 ID 문자열 배열 |

예제는 display_order 순서, 언어는 ID 정렬 순서입니다. 내부 PK, 작성자 ID, visibility, group_id, 비공개 테스트, 정답, 테스트별 점수는 반환하지 않습니다. 작성자·운영자에게도 이 상세 API가 비공개 테스트를 반환하는 별도 분기는 없습니다.

### 생성과 수정 요청

`POST /api/problems`와 `PUT /api/problems/{number}`는 같은 Write 본문을 사용합니다. POST는 모든 인증 회원에게 열려 있으며 관리자 전용이 아닙니다.

| 필드 | 타입 | 필수와 제한 |
|---|---|---|
| title | string | 비공백, 최대 200자 |
| description | string | 비공백, 최대 1,000,000자 |
| inputDescription | string | null 불가, 빈 문자열 가능, 최대 65,535자 |
| outputDescription | string | null 불가, 빈 문자열 가능, 최대 65,535자 |
| constraints | string 또는 null | 최대 65,535자 |
| rankType | string | INITIATE, SKILLED, ELITE, EXPERT, LEGEND, MYTHIC, ABSOLUTE 중 하나 |
| rankInt | integer | 1부터 5 |
| timeLimitMs | integer | 100부터 10,000 ms |
| memoryLimitMb | integer | 64부터 512 MB |
| executionMode | string | STDIN, FUNCTION, SQL |
| examples | array | null 불가, 0부터 20개, null 원소 불가 |
| examples[].input | string | null 불가, 최대 65,535자 |
| examples[].output | string | null 불가, 최대 65,535자 |
| examples[].explanation | string 또는 null | 최대 65,535자 |
| tests | array | 0부터 50개, null 원소 불가. 검수/게시에는 활성 테스트 필요 |
| tests[].input | string | null 불가, 최대 1,000,000자 |
| tests[].expected | string | null 불가, 최대 1,000,000자 |
| languages | array 또는 null | 실행 방식 호환 언어 선택, 최대 13개. 생략하면 방식별 기본 목록 |
| tags | array 또는 null | 최대 10개, 각 50자. 공백 정규화·중복 제거 |
| sourceName, sourceUrl | string 또는 null | 최대 255/1000자. URL은 HTTP(S)만 |
| referenceSolutions | array 또는 null | 언어별 하나, 최대 13개. UTF-8 코드 각 64KiB, 파일명 최대 120자(경로 불가) |
| groupId, visibility | integer/string 또는 null | 일반 PUBLIC/PRIVATE, 그룹 GROUP+groupId. 생성 후 범위 이동 금지 |

다음은 일반 입출력 문제 생성 예시이며 실제 등록된 문제를 뜻하지 않습니다.

```json
{
  "title": "두 수의 합",
  "description": "두 정수의 합을 출력하세요.",
  "inputDescription": "한 줄에 정수 두 개가 주어집니다.",
  "outputDescription": "합을 출력합니다.",
  "constraints": "각 수는 0 이상 100 이하입니다.",
  "rankType": "INITIATE",
  "rankInt": 5,
  "timeLimitMs": 1000,
  "memoryLimitMb": 256,
  "executionMode": "STDIN",
  "examples": [{"input":"2 3\n","output":"5\n","explanation":"2 + 3 = 5"}],
  "tests": [{"input":"8 9\n","expected":"17\n"}]
}
```

생성 시 번호 시퀀스 행을 FOR UPDATE로 잠그고 `max(next_number, 현재 최대 번호+1)`을 발급합니다. 정상 시퀀스의 시작 번호는 1000이며 기존 시드 번호를 건너뜁니다. 문제는 DRAFT·PRIVATE, 현재 회원을 작성자로 저장합니다. 생성 직후 불필요한 UPDATE·자식 테이블 DELETE 없이 본문과 예제·테스트·언어 목록을 바로 저장합니다. 성공 data는 발급된 번호입니다.

작성자는 일반 DRAFT·REJECTED를, 운영자는 비공개 PUBLISHED도 수정할 수 있습니다. 그룹 문제는 CREATE/EDIT 권한을 검사하고 PUBLISHED는 먼저 ARCHIVED로 전환해야 합니다. 시험에 연결된 문제는 모든 사용자의 PUT을 409로 거절해 시험 원본을 보존합니다. PUT은 본문·예제·테스트·언어·태그를 교체하며 referenceSolutions=null은 기존 정답 유지, []는 명시적 삭제입니다. 테스트는 배열 순서대로 display_order를 부여하고 `compare_type=EXACT`, 활성 상태로 생성합니다.

제목은 앞뒤/연속 공백을 정규화합니다. 일반 문제(scope 0)와 각 그룹 안에서 DB 고유 제약으로 중복을 막으며 충돌은 409입니다. 전체 제작 JSON은 UTF-8 2MiB 이내여야 합니다. 참고 정답은 코드 실행 경로나 명령으로 쓰지 않으며 관리 권한이 있는 편집 API에만 반환합니다. 사용자 지정 채점기·개별 배점 설정은 지원하지 않습니다.

### 검수와 상태 전환

`POST /api/problems/{number}/review-request`는 요청 본문 없이 작성자 또는 운영자가 호출합니다. DRAFT·REJECTED에서 PENDING_REVIEW로 바꾸고 사유 `검수 요청`과 요청자 ID를 이력에 기록합니다. 그 외 상태는 409입니다.

`POST /api/problems/{number}/review`는 일반 문제는 운영자, 그룹 문제는 REVIEW_PROBLEMS 담당자도 호출합니다.

```json
{"status":"PUBLISHED","cause":"검수 완료"}
```

status는 PUBLISHED, REJECTED, ARCHIVED 중 하나이며 필수입니다. cause는 선택 문자열로 최대 2000자입니다.

| 현재 상태 | 요청 | 결과와 조건 |
|---|---|---|
| DRAFT 또는 REJECTED | review-request | PENDING_REVIEW |
| PENDING_REVIEW | review PUBLISHED | 활성 테스트가 1개 이상이면 PUBLISHED·PUBLIC, published_at 현재 시각 |
| PENDING_REVIEW | review REJECTED | REJECTED, 기존 visibility 유지 |
| 임의 상태 | review ARCHIVED | ARCHIVED, 기존 visibility 유지 |
| 그 외 조합 | 수정 또는 검수 요청 | 규칙 위반 시 409 |

검수·게시에는 활성 테스트와 허용 언어의 참고 정답이 필요합니다. 승인 시 일반 문제는 저장된 requested_visibility(PUBLIC/PRIVATE), 그룹 문제는 GROUP을 유지합니다. 운영자 visibility API는 일반 공개/비공개, 그룹 PUBLISHED/ARCHIVED 전환으로 해석하며 그룹을 전체 공개하지 않습니다. 상태 변경 사유와 담당자는 DB 이력에 남깁니다.

### 편집·관리 API

| 경로 | 기능/권한 |
|---|---|
| GET `/api/problems/permissions` | 로그인, canCreate/canManage |
| GET `/api/problems/mine?page&search` | 본인 문제 모든 상태, 30개 |
| GET `/api/problems/{number}/edit` | 작성자/운영자/그룹 CREATE(본인)·EDIT·REVIEW. camelCase Write+상태·현재 공개범위·canEdit·usedInContest·비공개 테스트·정답 |
| GET `/api/admin/problems?page&search&status&visibility` | 운영자 전체 문제 목록 |
| GET `/api/admin/problems/{number}` | 운영자 편집 자료 |
| PATCH `/api/admin/problems/{number}/visibility` | 운영자, `{visibility:"PUBLIC" 또는 "PRIVATE",cause?}` |

## 그룹과 코딩테스트 API

그룹 생성은 DB solved_count≥50, 가입/소유를 합해 최대 3개입니다. 생성·가입은 사용자 행 잠금으로 상한을 직렬화합니다.
기본 역할은 OWNER, MANAGER, AUTHOR, CONTEST_MANAGER, MEMBER의 5개이고 커스텀 포함 총 8개입니다.
OWNER는 잠금 방지를 위해 고정하고 다른 역할의 기능별 권한은 변경할 수 있습니다. 기본 역할 삭제, 소유자 탈퇴·제거·역할 변경은 금지합니다.

권한 플래그는 VIEW_PROBLEMS, CREATE_PROBLEMS, EDIT_PROBLEMS, REVIEW_PROBLEMS, MANAGE_MEMBERS,
MANAGE_ROLES, MANAGE_GROUP, CREATE_CONTESTS, MANAGE_CONTESTS, VIEW_CONTEST_RESULTS, TAKE_CONTESTS입니다.
역할·문제·시험 쓰기는 해당 플래그를 매 요청의 현재 DB에서 확인합니다. 전역 운영자는 관리 권한만 우회할 수 있고, 생성 상한이나 실제 응시의 회원/시간 제한은 우회하지 않습니다.

| 경로 | 기능 |
|---|---|
| GET/POST `/api/groups` | 공개 이름 목록/검색, 로그인 생성 `{name,description}` |
| GET `/api/groups/mine` | 가입 그룹·역할·권한 목록 |
| GET/PATCH `/api/groups/{gid}` | 소속 회원 상세/설정 변경 |
| POST `/api/groups/{gid}/join`, `/leave` | 직접 가입/탈퇴 |
| GET `/api/groups/{gid}/members` | 회원 목록 |
| PATCH/DELETE `/api/groups/{gid}/members/{userId}` | 역할 지정 `{roleId}`/제거 |
| GET/POST `/api/groups/{gid}/roles` | 역할 목록/생성 `{name,permissions:[]}` |
| PUT/DELETE `/api/groups/{gid}/roles/{roleId}` | 역할 수정/미사용 커스텀 삭제 |
| GET `/api/groups/{gid}/problems?page&search` | 그룹 공개 문제, 시험 연결 문제는 일반 회원에게 제외. 담당자 응답의 usedInContest는 일반 풀이 버튼 차단 안내용 |
| GET `/api/groups/{gid}/problems/review-queue?page&search` | EDIT/REVIEW 담당자의 모든 상태 문제관리 목록 |
| GET/POST `/api/groups/{gid}/contests` | 시험 목록/생성 |
| GET/PUT `/api/groups/{gid}/contests/{cid}` | 시험 상세/설정 변경 |
| GET `.../{cid}/problems`, `.../{cid}/problems/{number}` | 시간·권한·다시보기 검사 후 시험 문제 열람 |
| POST `.../{cid}/submissions` | `{problemNumber,language,sourceCode}` 별도 시험 제출 |
| GET `.../{cid}/me` | 본인 답안·판정·코드, 응시 중 또는 허용된 다시보기 |
| GET `.../{cid}/results`, `.../{cid}/submissions?userId` | 결과 권한 담당자의 실시간 순위/대상 답안 |
| GET/POST `.../{cid}/events` | 결과 권한 담당자 신호 조회/응시자의 보호 신호 기록 |

시험 Write: `{title,startsAt,endsAt,problemNumbers:[],replayAllowed,captureProtection,windowSwitchProtection}`.
시각은 ISO UTC이고 시작≤DB UTC현재<종료일 때만 접수합니다. 새 시험은 미래 시작, 같은 그룹의 준비된 PUBLISHED 문제 1–50개를 선택합니다.
시작 후 제목·시간·문제 변경은 금지하며 다시보기와 보호 옵션만 변경할 수 있습니다.
연결된 문제는 내용 수정을 영구 금지합니다. 그룹 목록에서 보관해도 기존 시험 원문·답안은 보존됩니다.
다시보기는 종료+관리자 허용+본인 제출 기록+현재 응시 권한을 모두 만족해야 합니다.

시험 제출은 submissions.contest_id로 분리하고 일반 submit_count, accepted_count, solved_count, user_solved_problems에 반영하지 않습니다.
일반 제출 목록도 시험 답안을 제외하며 `/api/submissions/{id}`로 읽어도 동일한 시험 다시보기 검사를 적용합니다.
순위는 해결 문제 수 내림차순, 문제별 최초 정답 제출의 시작 이후 경과초 합 오름차순입니다(오답 패널티 없음).
프런트는 15초 간격 새로고침을 사용하며 SSE/WebSocket은 없습니다. 시험 제출/판정/이벤트 시각은 UTC이고,
V13부터 제출·판정 시각도 DATETIME(6)에 저장합니다. 제출 DTO의 submittedAt/judgedAt은 ISO Z 문자열입니다.

보호 옵션은 브라우저 복사·인쇄 억제와 전체화면 복귀 요구·창 이탈 감시입니다. OS 캡처를 완전히 막을 수 없습니다.
이벤트는 WINDOW_BLUR, VISIBILITY_HIDDEN, FULLSCREEN_EXIT, COPY_BLOCKED, PRINT_BLOCKED, CAPTURE_KEY,
eventId(UUID) 중복 제거, 사용자/시험당 1분 최대 60개입니다. 클라이언트 신호이므로 자동 부정행위 판정을 하지 않습니다.

## 커뮤니티·공지와 영구 보존

카테고리: HARDWARE, SOFTWARE, NEWS, PROBLEM_QNA, MAJOR_QNA, GENERAL, NOTICE. NOTICE 생성/전환/편집은 운영자만 가능합니다.
게시글 제목 200자·본문 20,000자, 댓글 5,000자입니다. 공개 API는 현재 내용만 반환하며 HTML을 실행하지 않습니다.
문제 연결은 PUBLISHED·PUBLIC·비그룹 문제만 허용하고 이후 비공개 전환 시 공개 응답에서 연결 번호를 숨깁니다.

| 경로 | 기능/권한 |
|---|---|
| GET/POST `/api/community/posts` | 공개 검색/카테고리/20개 페이지, 로그인 글 작성 |
| GET `/api/community/notices` | 공개 공지 목록 |
| GET/PUT/DELETE `/api/community/posts/{id}` | 공개 상세/작성자·운영자 수정/soft-delete |
| GET/POST `/api/community/posts/{id}/comments` | 부모별 20개 조회(`parentId`,page)/로그인 댓글·답글 작성 |
| PUT/DELETE `/api/community/comments/{id}` | 작성자·운영자 수정/soft-delete |
| GET `/api/admin/community/posts?includeDeleted=true` | 운영자 삭제 글 포함 검색 |
| GET `/api/admin/community/posts/{id}`, `.../{id}/comments` | 운영자 삭제 글·댓글도 열람/페이지 조회 |
| GET `/api/admin/community/posts/{id}/history`, `/api/admin/community/comments/{id}/history` | 운영자 이전/이후 내용·수정자·시각·문제 연결 스냅샷, 20개 페이지 |
| POST `/api/admin/community/posts/{id}/restore`, `/api/admin/community/comments/{id}/restore` | 운영자 `{version,reason?}` 복원 |

글 Write는 `{title,body,category,problemNumber?}`, 댓글 Write는 `{parentId?,body}`입니다.
PUT은 현재 version을 추가하고 DELETE는 `?version=`를 필수로 받습니다. 낡은 버전은 409로 거절합니다.
글 PUT의 선택 필드 `preserveProblemLink: true`와 `problemNumber: null`은 기존 연결을 유지합니다.
숨긴 번호를 새로 연결하는 권한이 아니며 다른 공개 번호를 지정하면 기존 공개 일반문제 검증을 적용합니다.
작성자/운영자의 상세에만 `problemLinkHidden: true`가 표시될 수 있어 번호 없이 제목/본문을 편집할 수 있습니다.
필드를 생략/false로 보내고 번호를 null로 교체하면 기존처럼 연결을 제거합니다. 선택 필드는 nullable Boolean으로 구형 JSON을 유지합니다.
댓글 깊이는 원댓글0→대댓글1→대대댓글2이며 그 이상은 400입니다. 부모 댓글 삭제는 자식 답글을 삭제하지 않습니다.
삭제된 댓글은 일반 사용자에게 본문 없이 삭제 표시만 반환하며 수정 전 원문은 관리자 이력에서 확인합니다.
게시글·댓글·이력에 대한 물리 DELETE는 없고 생성/수정/삭제/복원마다 한 트랜잭션에 old/new 스냅샷을 쌓습니다.
저자/수정자 계정과 분리된 username 스냅샷도 유지합니다. DB 관리자의 직접 조작까지 막는 저장소 수준 불변 보증은 아닙니다.

## 언어 API와 실행 규격

`GET /api/languages`의 data는 다음 형식의 배열입니다. 파일 경로나 Docker 명령은 API 응답에 넣지 않습니다.

```json
[{"id":"PYTHON","label":"Python","compiled":false,"modes":["STDIN","FUNCTION"]}]
```

위 예시는 배열 원소 하나만 표시한 것입니다. 실제 목록은 다음 13개이며 enum 선언 순서로 반환합니다.

| ID | label | compiled | modes | 제출 파일과 진입점 |
|---|---|---|---|---|
| PYTHON | Python | false | STDIN, FUNCTION | main.py, 함수형은 sol |
| C | C | true | STDIN, FUNCTION | main.c, main 또는 sol |
| CPP | C++ | true | STDIN, FUNCTION | main.cpp, main 또는 sol |
| JAVA | Java | true | STDIN, FUNCTION | Main.java, public class Main |
| JAVASCRIPT | JavaScript | false | STDIN, FUNCTION | main.js, CommonJS 또는 sol |
| TYPESCRIPT | TypeScript | true | STDIN, FUNCTION | main.ts, CommonJS로 변환 |
| CSHARP | C# | true | STDIN, FUNCTION | Main.cs, Main 또는 Solution.Sol |
| GO | Go | true | STDIN, FUNCTION | main.go, package main |
| RUST | Rust | true | STDIN, FUNCTION | main.rs, main 또는 sol |
| KOTLIN | Kotlin | true | STDIN, FUNCTION | Main.kt, main 또는 sol |
| SQL | SQL (SQLite) | false | SQL | query.sql, 단일 읽기 쿼리 |
| RUBY | Ruby | false | STDIN, FUNCTION | main.rb, main 코드 또는 sol |
| LLVM_IR | LLVM IR | true | STDIN, FUNCTION | main.ll, main 또는 sol |

`compiled`는 제출 전에 별도 컴파일 단계를 실행하는지 뜻하며 언어의 일반적인 분류가 아닙니다. C는 C17, C++는 C++20, TypeScript는 ES2022 대상과 Node 타입, Rust는 edition 2021, C#은 net10.0으로 고정합니다. Kotlin은 실행 가능한 JAR로 만들고 Java와 Kotlin은 JVM에서 실행합니다. LLVM IR은 Clang 18로 네이티브 실행 파일을 만듭니다.

런타임은 Python 3, Node 24, OpenJDK 21, .NET 10과 이미지에 설치된 GCC·Go·Rust·Ruby·LLVM, TypeScript 5.9.3, Kotlin 2.2.21입니다. 정확한 2026년 9월 17일 이미지의 패치 버전은 README에 기록되어 있습니다. 고정 digest가 아닌 태그와 OS 패키지를 사용하므로 재빌드 때 달라질 수 있고 이번 문서 작성에서 런타임 버전을 재측정하지 않았습니다.

### 일반 입출력 모드

STDIN은 테스트의 input_data를 UTF-8 표준 입력으로 전달하고 표준 출력을 정답과 비교합니다. 소스 파일 하나만 받으며 파일명·빌드 옵션·설치 명령·Docker 이미지 선택권을 제출자에게 주지 않습니다. 외부 패키지 다운로드나 다중 파일 업로드는 지원하지 않습니다.

### 함수 호출 모드

FUNCTION은 범용 함수형 채점기가 아니라 15×15 이진 행렬, 0–14 범위의 목표 행·열, 불리언 결과에 고정되어 있습니다. 새 문제의 비공개 테스트는 저장 시 이 규격을 검증하며, SQL 외 12개 언어의 고정 어댑터가 같은 테스트 JSON을 각 언어의 자료형으로 전달합니다. V8 마이그레이션에서 규격이 맞지 않는 레거시 FUNCTION 문제는 기존 테스트·언어 행을 삭제하지 않고 Python 제출만 노출합니다. 언어별 함수 선언은 README의 표가 기준입니다.

```python
def sol(matrix: list[list[int]], target: tuple[int, int]) -> bool:
    ...
```

입력은 `{"matrix":[...],"target":[행,열]}` JSON입니다. Python에서는 target을 tuple로 변환하고 반환 타입이 정확히 bool인지 검사합니다. 다른 언어는 고정된 225개 행 우선 정수와 목표 좌표를 받아 `sol`을 호출하는 이미지 내부 래퍼를 빌드하거나 실행합니다. 결과는 소문자 `true` 또는 `false`로 출력하므로 비공개 expected도 이 형식을 사용해야 합니다. Python 제출의 일반 print 출력은 함수 실행 중 stderr로 돌립니다. Python 소스 실행의 `__name__`은 `__submission__`입니다. 제출자는 래퍼나 빌드 옵션을 바꿀 수 없습니다.

### SQL 모드

SQL은 서비스 MySQL과 연결하지 않고 케이스마다 새로운 SQLite `:memory:` DB를 만듭니다. 테스트 input_data는 테이블 생성과 데이터 입력 SQL이며, 제출 소스는 SELECT나 WITH 등의 단일 읽기 쿼리입니다.

```sql
-- 테스트 입력 예시
CREATE TABLE numbers(value INTEGER);
INSERT INTO numbers VALUES (2), (3);
```

```sql
-- 제출 예시
SELECT SUM(value) FROM numbers;
```

위 예시의 expected는 `5`입니다. 결과 행은 헤더 없는 TSV로 출력하며 NULL은 `NULL`, 줄바꿈은 LF입니다. 탭·개행·따옴표가 들어간 값은 CSV 인용 규칙을 사용합니다. 결과 순서가 필요하면 ORDER BY를 명시해야 하며 자동 정렬하지 않습니다.

초기화 SQL에는 ATTACH, DETACH, load_extension을 차단하고, 제출 단계에는 SELECT·READ·FUNCTION·RECURSIVE 권한만 허용합니다. 제출의 데이터 변경, PRAGMA, 파일 DB 연결, 확장 로딩, 여러 SQL 문, `.shell` 같은 CLI 명령은 허용하지 않습니다. 거절이나 SQL 문법 오류는 별도 컴파일 오류가 아닌 RUNTIME_ERROR로 분류됩니다.

## 제출 API

### 제출 접수

`POST /api/submissions`

| 필드 | 타입 | 필수와 제한 |
|---|---|---|
| problemNumber | integer | 1000 이상 |
| sourceCode | string | 비공백, 최대 65,536자 |
| language | string 또는 null | 최대 30자. 생략 또는 null은 PYTHON |

```json
{"problemNumber":1001,"language":"CPP","sourceCode":"#include <iostream>\nint main(){int a,b;std::cin>>a>>b;std::cout<<a+b;}"}
```

위 번호는 예시입니다. 실제 공개된 문제 번호와 그 문제의 languages 목록을 사용해야 합니다. 알 수 없는 언어 ID, 소문자 ID, 빈 문자열, 실행 모드 불일치, 문제 허용 목록에 없는 언어는 400입니다.

접수 트랜잭션은 사용자 행과 문제 행을 잠근 뒤 접근 권한, 실행 모드, 허용 언어, PUBLISHED 상태, 활성 테스트 존재 여부를 확인합니다. 비공개 접근은 404, 접근 가능한 미공개 문제나 테스트 미준비는 409입니다. 해당 사용자의 QUEUED·RUNNING 제출이 합쳐서 5개 이상이면 429입니다.

성공 시 submissions에 QUEUED 상태로 저장하고 사용자 submit_count와 문제 submission_count를 각각 1 증가시킵니다. 성공 data는 제출 ID입니다. 채점 완료까지 HTTP 요청을 기다리지 않으며 프런트엔드는 상세 결과를 다시 조회해야 합니다. WebSocket이나 SSE 알림은 없습니다.

### 내 제출 목록

`GET /api/submissions`는 현재 사용자 제출만 ID 내림차순 최근 50건 반환합니다.

`id`, `problem_number`, `problem_title`, `language`, `status`, `score`, `execution_time_ms`, `submitted_at`이 각 원소의 필드입니다. 채점 전 execution_time_ms는 null일 수 있습니다. 운영자도 이 목록 API로는 본인 제출만 조회합니다.

### 제출 결과 조회

`GET /api/submissions/{id}`는 제출자 또는 운영자에게만 허용합니다. 없는 제출이나 다른 일반 회원의 제출은 404입니다.

```json
{
  "success": true,
  "data": {
    "id": 123,
    "user_id": 7,
    "language": "CPP",
    "status": "ACCEPTED",
    "score": 100,
    "execution_time_ms": 250,
    "submitted_at": "<환경에 따른 날짜 직렬화 값>",
    "judged_at": "<환경에 따른 날짜 직렬화 값>",
    "cases": [{"status":"ACCEPTED","execution_time_ms":250}]
  },
  "message": null
}
```

이 예시의 ID·시간은 설명용입니다. 판정 전 execution_time_ms와 judged_at은 null이고 cases는 빈 배열일 수 있습니다. 결과는 최종 반영 트랜잭션에서 저장하므로 실행 중 개별 케이스 진행 상황을 계속 반환하지 않습니다. 소스 코드, 테스트 입력·정답, 실제 출력, stderr, 컴파일 진단, 최대 메모리 사용량은 이 API로 반환하지 않습니다. 제출 상세에는 problem_number와 problem_title도 없으므로 필요한 경우 목록의 값을 함께 보관해야 합니다.

## 채점 큐와 결과 반영

### 워커 처리

1. `judge.enabled=true`인 프로세스에만 JudgeWorker가 등록됩니다. 작업 종료 후 fixedDelay 1000ms마다 다시 실행하고 한 워커는 제출 하나씩 처리합니다.
2. claim 트랜잭션은 heartbeat가 20분 넘게 갱신되지 않은 RUNNING의 토큰을 폐기합니다. 실행 시도 횟수가 3 미만이면 QUEUED로 돌리고, 3회째 실행도 중단됐으면 SYSTEM_ERROR로 마무리합니다. heartbeat가 없는 작업은 claimed_at, 그 값도 없으면 submitted_at으로 만료를 판단합니다.
3. QUEUED를 ID 순서로 `FOR UPDATE SKIP LOCKED`로 1개 가져와 RUNNING, claimed_at, heartbeat, 새 UUID judge_token을 설정하고 실행 시도 횟수를 1 증가시킵니다. 여러 워커는 서로 잠긴 작업을 건너뜁니다. 재시도는 제출 접수 횟수를 다시 늘리지 않습니다.
4. 컴파일 언어는 제출당 한 번 컴파일합니다. 실패 시 test_case_id가 null인 제출 단계 결과 한 건을 남깁니다.
5. 성공하면 활성 테스트를 display_order 순으로 실행합니다. 각 케이스는 새 컨테이너이고, 앞선 오답이 나와도 기본 루프는 나머지 케이스를 계속 실행합니다.
6. 결과 반영은 사용자, 문제, 제출 순서로 잠급니다. 아직 RUNNING이고 judge_token이 같을 때만 결과를 저장합니다. 오래된 워커의 재반영은 무시합니다.
7. 정상 경로에서 런타임 컨테이너, 컴파일 컨테이너, 산출물 볼륨, 호스트 임시 소스 파일을 정리합니다.

블로킹 채점 스레드와 별도의 실행기에서 30초마다 heartbeat를 갱신하고 단계 전환에서도 갱신합니다. RUNNING·제출 ID·토큰 조건이 맞을 때만 갱신하며 토큰 상실이나 연속 3회 갱신 실패 이후에는 다음 단계와 결과 반영을 멈춥니다. 실패 로그에는 제출 ID와 단계만 남기며 코드·테스트·토큰은 남기지 않습니다. 20분 만료 회수는 다음 claim 호출에서 수행됩니다. 정상 판정 오류의 자동 재시도나 수동 재채점·취소·큐 관리 API는 없습니다.

### 상태와 판정

| 상태 | 현재 의미 |
|---|---|
| QUEUED | 접수되었거나 만료 lease에서 복구되어 워커 대기 |
| RUNNING | 워커가 claim하여 컴파일 또는 테스트 처리 중 |
| ACCEPTED | 모든 결과가 ACCEPTED |
| WRONG_ANSWER | 정상 종료했으나 정답 문자열과 다름 |
| TIME_LIMIT_EXCEEDED | wall timeout, CPU 제한 관련 종료 등 |
| MEMORY_LIMIT_EXCEEDED | 종료 코드 137이며 Docker OOMKilled=true |
| RUNTIME_ERROR | 기타 비정상 실행, SQL 실패, 출력 제한 초과 등 |
| COMPILATION_ERROR | 컴파일 실패·컴파일 시간 초과·컴파일 출력 제한 초과 |
| SYSTEM_ERROR | Docker 준비 오류, 처리 예외, 비어 있는 결과, 세 번째 실행도 lease가 만료된 작업 등 |

런타임 분류는 wall timeout, 종료 코드 125·126·127, 출력 제한, 종료 코드 137, 종료 코드 152, 기타 비정상 종료, 정답 비교 순으로 판단합니다. 125·126·127은 SYSTEM_ERROR이며 137은 OOMKilled가 아니면 TIME_LIMIT_EXCEEDED, 152도 TIME_LIMIT_EXCEEDED입니다. 자원 부족으로 언어 런타임이 다른 코드로 종료하면 RUNTIME_ERROR가 될 수 있어 메모리 초과를 완벽하게 구분하는 규약은 아닙니다.

최종 상태는 테스트 순서에서 첫 번째 ACCEPTED 아닌 결과이며 실패가 없으면 ACCEPTED입니다. 테스트가 없으면 SYSTEM_ERROR입니다. 총점은 전체 정답 100, 그 외 0이고 부분 점수는 없습니다. 제출 execution_time_ms는 각 결과의 최댓값이며 합계가 아닙니다.

### 비교 방식

실제 비교는 Java `actual.strip().equals(expected.strip())`입니다. 전체 문자열 양 끝의 공백을 제거하지만 내부 공백, 중간 개행, 숫자 허용 오차를 정규화하지 않습니다. DB의 compare_type이나 judge_type을 읽어서 비교 방식을 분기하지 않습니다. 따라서 이름이 EXACT여도 바이트 단위 완전 일치 채점은 아니며 SPECIAL_CHECKER·FLOAT_TOLERANCE·EFFICIENCY가 구현되었다고 해석하면 안 됩니다.

### 통계와 랭크 갱신

- 일반 ACCEPTED 제출마다 문제 accepted_count를 1 증가시킵니다. 시험 제출은 별도 결과에만 반영합니다.
- user_solved_problems의 `(user_id, problem_id)`를 INSERT IGNORE하여 최초 해결일 때만 사용자 solved_count를 증가시킵니다.
- 실패·시스템 오류 제출도 이미 접수된 제출 수에는 남습니다.
- 해결 수에 따른 사용자 랭크는 임시 정책이며 문제 난이도별 가중치가 없습니다.

해결 수를 `n`이라 할 때 큰 등급은 `[INITIATE, SKILLED, ELITE, EXPERT, LEGEND, MYTHIC, ABSOLUTE][min(n / 50, 6)]`이며 `/`는 정수 나눗셈입니다. 세부 등급은 `n >= 350`이면 1, 아니면 `5 - ((n % 50) / 10)`입니다. 예를 들어 0개 INITIATE 5, 10개 INITIATE 4, 50개 SKILLED 5, 300개 ABSOLUTE 5, 340개부터 ABSOLUTE 1입니다. 랭크 갱신은 새로운 문제를 처음 해결할 때 수행합니다.

## Docker 실행 환경

| 항목 | 설정 |
|---|---|
| 기본 이미지 | cobweb-languages:local |
| 실행 사용자 | UID와 GID 65534 |
| 네트워크 | none |
| 루트 파일시스템 | read-only |
| 권한 | cap-drop=ALL, no-new-privileges |
| CPU 할당 | 컨테이너당 1 CPU |
| 프로세스와 스레드 | pids-limit=128 |
| 메모리와 스왑 | 메모리 한도와 memory-swap 값을 같게 설정하여 추가 스왑 미허용 |
| 소스 | 고정 파일명으로 호스트에 생성, /source에 읽기 전용 bind mount |
| 컴파일 산출물 | 제출별 Docker local tmpfs 볼륨 256MiB, /work에 마운트 |
| 산출물 실행 시 | 읽기 전용 마운트, 케이스 간 변경 차단 |
| 파일 크기 ulimit | 67,108,864 bytes |
| 출력 보관 | stdout와 stderr 각각 최대 65,536 bytes |
| Docker 로그 | log-driver=none |

컴파일 단계는 메모리 1024MiB, CPU soft limit 60초·hard limit 61초, 명령 wall timeout 65초, `/tmp` 256MiB입니다. 이는 프로세스 CPU ulimit과 컨테이너 제한의 조합이지 전체 컴파일 프로세스 트리의 정밀한 합산 CPU 계측은 아닙니다.

실행 단계의 메모리는 문제 memory_limit_mb입니다. 컨테이너 내부의 `timeout`이 Python·SQL을 포함한 모든 언어의 실행을 문제의 `time_limit_ms` 벽시계 시간으로 제한합니다. CPU soft limit은 보조 장치로 `max(1, ceil(time_limit_ms / 1000))`초, hard limit은 그보다 1초 큽니다. 호스트 Docker 명령의 `time_limit_ms + 5000`ms 대기는 인프라 정체 시 최종 안전망이며 `/tmp`는 32MiB입니다. 컴파일·실행의 /tmp 모두 noexec·nosuid입니다. Java와 Node의 heap 및 .NET GC heap 상한은 대략 문제 메모리의 절반으로 제한하며 컨테이너 전체 메모리에는 런타임 자체 메모리도 포함됩니다.

시간 측정은 Docker 실행 시작 전부터 명령 종료 후 판정 처리까지의 벽시계 시간이므로 컨테이너 준비 비용을 포함합니다. 경쟁 프로그래밍용 정확한 CPU 실행 시간이나 최대 메모리 사용량을 측정하는 구현은 아닙니다. stdout·stderr·stdin 파이프는 별도 virtual-thread executor로 처리하여 공용 풀 정체를 피합니다.

산출물 tmpfs가 케이스 사이에 사라지지 않도록 컴파일 컨테이너의 PID 1을 최대 30분 유지합니다. 정상 종료 시 즉시 정리하지만 워커가 비정상 종료하면 볼륨 메타데이터와 호스트 소스가 남을 수 있습니다. 청소 실패를 별도 운영 알림으로 보내는 로직은 없습니다. 기대 정답은 실행 컨테이너로 전달하지 않고 Java 호스트 쪽에서 비교합니다.

Docker는 현재의 격리 수단이며 커널 수준 취약점까지 차단하는 보장은 아닙니다. 워커의 Docker 소켓 접근은 높은 호스트 권한이므로 공개 서비스에서는 전용 격리 환경이 필요합니다.

## 프로필과 랭킹

### 공개 프로필과 내 프로필

`GET /api/profiles/{username}`은 다음을 반환하며 사용자가 없으면 404입니다.

| 필드 | 내용 |
|---|---|
| username | 사용자명 |
| bio | 소개 문자열 |
| github_url | GitHub URL 또는 빈 문자열 |
| solved_count, submit_count | 해결 수와 제출 수 |
| rank_type, rank_int | 현재 등급 |
| solvedProblems | `{problem_number, problem_title}` 배열 |

solvedProblems는 해결 시각 내림차순 최근 100개이며 현재 PUBLISHED·PUBLIC인 문제만 포함합니다. 사용자 solved_count는 비공개로 바뀐 해결 문제도 포함할 수 있어 배열 크기와 같지 않을 수 있습니다. 이메일, 비밀번호, 토큰, 로그인 로그, 내부 사용자 ID, 역할은 반환하지 않습니다.

프로필 조회에는 사용자 status 조건이 없으므로 정지된 계정도 존재하면 공개 프로필 조회 대상입니다. `GET /api/me`는 인증한 사용자의 username으로 같은 프로필 함수를 호출하므로 이메일이나 role을 추가로 내려주지 않습니다.

`PATCH /api/me`는 다음 두 필드를 모두 받습니다. PATCH라는 이름이지만 필드별 생략 업데이트가 아닙니다.

```json
{"bio":"알고리즘 공부 중입니다.","githubUrl":"https://github.com/example"}
```

- bio: null 불가, 최대 300자, 빈 문자열로 삭제 가능.
- githubUrl: null 불가, 최대 255자. 빈 문자열 또는 `https://github.com/[A-Za-z0-9_-]+/?`에 일치해야 함.
- HTTP, 다른 도메인, 저장소 하위 경로, 쿼리 등은 정규식에 맞지 않아 400.
- username, 이메일, 프로필 이미지, 역할 변경은 지원하지 않음.

### 랭킹

`GET /api/ranking?page=0`은 ACTIVE 사용자만 반환합니다. 해결 수 내림차순, 제출 수 오름차순, ID 오름차순으로 정렬합니다. 각 원소는 `username`, `bio`, `solved_count`, `submit_count`, `rank_type`, `rank_int`입니다. 명시적 순위 숫자나 전체 사용자 수는 없습니다.

## 데이터베이스 명세

Flyway SQL이 물리 스키마의 기준입니다. 아래는 V13까지의 주요 필드와 사용 상태를 요약한 데이터 사전입니다. 별도 표시가 없으면 ID 단일 PK 테이블의 id는 BIGINT AUTO_INCREMENT입니다. 정확한 NULL, DEFAULT, 인덱스 정의는 버전별 SQL을 함께 확인해야 합니다. Flyway 자체 이력 테이블은 서비스 테이블 수에 포함하지 않습니다.

### 회원과 인증 테이블

| 테이블 | 필드 | 제약과 용도 |
|---|---|---|
| users | id; username VARCHAR(16); email VARCHAR(255); provider ENUM; provider_id VARCHAR(255); password VARCHAR(255); bio VARCHAR(300); github_url VARCHAR(255); role ENUM; status ENUM; solved_count INT; submit_count INT; rank_type ENUM; rank_int TINYINT; login_failed_count INT; access_token_version BIGINT; last_login_at, created_at, updated_password_at DATETIME | username·email·(provider, provider_id) UNIQUE. email과 제공자 정보는 nullable. provider와 provider_id는 둘 다 NULL 또는 둘 다 존재. 카운터 음수 금지, rank_int 1부터 5. access 토큰 버전은 로그아웃·비밀번호 변경 시 증가 |
| users_log | id; user_id BIGINT nullable; attempt_email VARCHAR(255); attempt_name VARCHAR(16); status ENUM; log_created_at DATETIME; client_ip VARCHAR(45); user_agent VARCHAR(255) | 일반 로그인 감사. user 삭제 시 user_id SET NULL. 로그인 시각·IP와 시각·사용자와 시각 인덱스 |
| refresh_token | id; user_id BIGINT; token CHAR(64); expires_at, created_at DATETIME | user_id와 token 각각 UNIQUE. 사용자 삭제 CASCADE. SHA-256 지문 저장 |
| email_verifications | id; email VARCHAR(255); code_hash CHAR(64); purpose ENUM; is_verified BOOLEAN; expires_at, created_at DATETIME | email·purpose·created_at DESC 조회 인덱스, 만료 인덱스. 회원 FK 없음. 코드의 HMAC 지문 저장 |
| password_reset | id; user_id BIGINT; reset_token CHAR(64); is_used BOOLEAN; expires_at, created_at DATETIME | reset_token UNIQUE. 사용자 삭제 CASCADE. 사용자·만료 인덱스. SHA-256 지문 저장 |
| auth_rate_limits | limit_key CHAR(64) PK; window_started_at DATETIME(6); attempts INT | 이메일·아이디·연결 IP 원문 대신 해시 키. 첫 요청부터 10분 창, 주기적 정리 |
| email_verification_guards | guard_key CHAR(64) PK; touched_at DATETIME(6) | 이메일/목적별 발급·검증·소비 직렬화 잠금 행. 주기적 정리 |

### 문제와 관계 테이블

| 테이블 | 필드 | 제약과 용도 |
|---|---|---|
| problems | id; problem_number INT; problem_title VARCHAR(200); rank_type ENUM; rank_int TINYINT; description LONGTEXT; input_description, output_description, constraints_text TEXT; time_limit_ms, memory_limit_mb INT; judge_type ENUM; status ENUM; visibility ENUM; submission_count, accepted_count, like_count BIGINT; author_id, group_id BIGINT; source_name VARCHAR(255); source_url VARCHAR(1000); created_at, updated_at, published_at DATETIME; execution_mode ENUM; function_abi_compatible BOOLEAN | 번호 UNIQUE. 작성자 FK RESTRICT. 그룹 nullable·FK RESTRICT. GROUP은 group_id 필수. rank_int 1부터 5, 자원 한도 양수, 집계 음수 금지, accepted_count는 submission_count 이하. FUNCTION 호환 플래그가 거짓인 레거시 문제는 Python만 노출 |
| problem_number_sequence | id INT PK; next_number INT | id=1 한 행. 번호 발급 직렬화, V2에서 생성 |
| problem_status_history | id; problem_id BIGINT; status ENUM; cause TEXT; checker_id BIGINT nullable; created_at DATETIME | 문제 삭제 CASCADE, 검사자 삭제 SET NULL. 현재 상태는 problems가 기준 |
| problem_examples | id; problem_id BIGINT; input_text, output_text LONGTEXT; explanation TEXT nullable; display_order INT | (problem_id, display_order) UNIQUE, 순서 양수, 문제 삭제 CASCADE. 공개용 |
| problem_test_cases | id; problem_id BIGINT; input_data, expected_output LONGTEXT; compare_type ENUM; score INT; display_order INT; is_active BOOLEAN; created_at DATETIME | (problem_id, display_order) UNIQUE, 순서 양수·점수 음수 금지. 문제 삭제 CASCADE. 비공개 채점 데이터 |
| problem_languages | problem_id BIGINT; language VARCHAR(30) | 두 필드 복합 PK. 문제 삭제 CASCADE. 저장된 언어 목록이며 비호환 레거시 FUNCTION은 API에서 Python만 노출·접수 |
| tags | id; name VARCHAR(50) | 이름 UNIQUE. 관리 API 미구현 |
| problem_tags | problem_id BIGINT; tag_id BIGINT | 복합 PK, 양쪽 삭제 CASCADE. 제작 Write에서 태그 연결 가능 |
| reference_solutions | problem_id BIGINT, language VARCHAR(30), file_name VARCHAR(120), source_code LONGTEXT, created_at DATETIME | (problem_id,language) PK. 비공개 편집 자료, 공개 상세·채점 컨테이너에는 전달하지 않음 |
| problem_likes | user_id BIGINT; problem_id BIGINT; created_at DATETIME | 두 ID 복합 PK, 양쪽 삭제 CASCADE. 좋아요 API 미구현 |

problems의 목록 인덱스는 status·visibility·problem_number, 등급 인덱스는 rank_type·rank_int, 그룹 목록 인덱스는 group_id·status·problem_number, 작성자 인덱스는 author_id·created_at입니다. V10은 requested_visibility 및 STORED 생성 컬럼 title_scope=COALESCE(group_id,0), UNIQUE(title_scope,problem_title)를 추가합니다. test_cases는 problem_id·is_active·display_order 인덱스로 조회합니다.

### 제출과 해결 테이블

| 테이블 | 필드 | 제약과 용도 |
|---|---|---|
| submissions | id; user_id, problem_id BIGINT; language VARCHAR(30); source_code LONGTEXT; status ENUM; score INT; execution_time_ms, memory_usage_kb INT nullable; submitted_at, judged_at DATETIME(6); claimed_at DATETIME nullable; judge_token VARCHAR(36) nullable; judge_heartbeat_at DATETIME(6) nullable; judge_attempts INT | user와 problem 삭제 CASCADE. 점수·시간·메모리·시도 횟수 음수 금지. 기본 상태 QUEUED, score 0. claimed_at·judge_token은 V2, heartbeat·attempts는 V9, 시각 정밀도는 V13 |
| submission_test_case_results | id; submission_id BIGINT; test_case_id BIGINT nullable; status ENUM; score INT; execution_time_ms, memory_usage_kb INT nullable | 제출 삭제 CASCADE, 테스트 삭제 SET NULL. 점수 음수 금지. 컴파일 단계 실패는 test_case_id NULL. COMPILATION_ERROR는 V3 추가 |
| user_solved_problems | user_id BIGINT; problem_id BIGINT; solved_at DATETIME | 두 ID 복합 PK로 중복 해결 방지. 양쪽 삭제 CASCADE |

submissions에는 사용자·제출 시각, 문제·제출 시각, 문제·사용자·상태 인덱스가 있으며 V2에서 status·submitted_at·id 큐 인덱스, V9에서 status·judge_heartbeat_at·id 만료 조회 인덱스를 추가합니다. V11은 nullable contest_id와 (contest_id,problem_id) 복합 FK 및 시험/사용자 인덱스를 추가합니다. NULL이면 일반 제출입니다. 케이스 결과의 score는 현재 명시적으로 갱신하지 않아 기본 0입니다. memory_usage_kb는 스키마만 있고 현재 워커가 측정·저장하지 않습니다.

### 그룹 테이블

| 테이블 | 필드 | 제약과 구현 상태 |
|---|---|---|
| study_groups | id; group_name VARCHAR(16); group_description VARCHAR(1000); status ENUM; created_at DATETIME | 그룹명 UNIQUE. 새 생성은 ACCEPTED, 생성·설정 API 제공 |
| group_members | user_id BIGINT; group_id BIGINT; member_number INT; permission ENUM; role_id BIGINT; joined_at DATETIME | 두 ID 복합 PK. (group_id,member_number) UNIQUE, (group_id,role_id)→group_roles FK. permission은 기존 호환용 |
| group_roles | id, group_id, role_name, builtin_key, 기능별 can_* BOOLEAN 11개 | 그룹별 역할명/기본키 UNIQUE, 기본5개+커스텀 총8개 서비스 상한 |
| group_contests | id, group_id, title, starts_at, ends_at, replay_allowed, capture_protection, window_switch_protection, created_by, 시각 | starts_at<ends_at CHECK, UTC 시간창 |
| contest_problems | contest_id,problem_id,display_order | 복합 PK, 시험 내 순서 UNIQUE, 문제 FK RESTRICT |
| contest_events | id,contest_id,user_id,event_id,event_type,occurred_at | 시험/사용자/UUID UNIQUE, UTC 시각·발생률 인덱스 |

### 커뮤니티 보존 테이블

| 테이블 | 필드와 보존 정책 |
|---|---|
| community_posts | category,title,body,problem_id,author_id,author_username,version,is_deleted,deleted_at,created_at,updated_at. soft-delete, 저자 이름 스냅샷 |
| community_comments | post_id,parent_id,depth 0–2,body,author_id,author_username,version,삭제 상태/시각. 부모/글 삭제 FK RESTRICT |
| community_post_revisions | action,old/new title/category/body/problem_id/problem_number/deleted/version,editor_id/editor_username,edited_at,reason. 글 삭제 FK RESTRICT |
| community_comment_revisions | action,old/new body/deleted/version,editor_id/editor_username,edited_at,reason. 댓글 삭제 FK RESTRICT |

커뮤니티 서비스는 위 네 테이블을 물리 삭제하지 않습니다. 수정 이력과 본문 변경은 같은 트랜잭션이며 일반 사용자는 삭제/과거 내용을 읽을 수 없습니다.

### 스키마 enum

| 구분 | 값 |
|---|---|
| 사용자 및 문제 rank_type | INITIATE, SKILLED, ELITE, EXPERT, LEGEND, MYTHIC, ABSOLUTE |
| 문제 status | DRAFT, PENDING_REVIEW, PUBLISHED, REJECTED, ARCHIVED |
| 문제 visibility | PUBLIC, GROUP, PRIVATE |
| 실행 execution_mode | STDIN, FUNCTION, SQL |
| judge_type | STANDARD, SPECIAL, EFFICIENCY. 워커가 이 필드로 분기하지 않음 |
| compare_type | EXACT, TRIM_TRAILING_WHITESPACE, FLOAT_TOLERANCE, SPECIAL_CHECKER. 워커가 이 필드로 분기하지 않음 |
| 인증 purpose | JOIN, PASSWORD_RESET |
| 감사 status | SUCCESS, FAIL_PASSWORD_MISMATCH, FAIL_USER_NOT_FOUND, BANNED_USER |

### 마이그레이션과 시드

- V1은 회원·인증·그룹·문제·제출·통계 관련 18개 테이블을 정의합니다.
- V2는 problem_number_sequence, execution_mode, claimed_at, judge_token, 큐 인덱스를 추가합니다. 번호 1000이며 제목이 체인 레이저인 기존 문제의 모드를 FUNCTION으로 지정합니다.
- V3는 SQL 모드와 케이스 COMPILATION_ERROR를 추가하고 기존 STDIN 문제에 12개 언어를 INSERT IGNORE합니다.
- V4는 기존 refresh·재설정 토큰 원문을 SHA-256으로 변환하고 컬럼을 CHAR(64)로 줄입니다. 기존 발급 토큰의 원문을 가진 사용자는 새 코드에서도 이를 사용할 수 있습니다.
- V5는 access 토큰 버전과 두 인증 제어 테이블을 추가합니다. 버전 클레임이 없는 기존 access 토큰은 새 코드에서 거절됩니다.
- V6는 기존 FUNCTION 문제에 SQL 외 12개 언어를 허용합니다. 신규 시드도 12개를 삽입합니다.
- V7은 기존 미완료 이메일 인증 기록을 삭제하고 `code` 원문 열을 `code_hash CHAR(64)`로 바꿉니다. 진행 중인 인증 코드는 다시 발급해야 합니다.
- V8은 기존 FUNCTION 문제의 활성 테스트가 15×15 이진 행렬·유효한 목표 좌표·정확한 불리언 정답 규격을 모두 만족할 때만 `function_abi_compatible`을 참으로 표시합니다. 비호환 문제의 테스트나 언어 행은 삭제하지 않으며 API에서 Python만 노출·접수합니다.
- V9는 제출의 heartbeat, 실행 시도 횟수, 만료 조회 인덱스를 추가합니다. 기존 RUNNING의 claimed_at을 heartbeat로 옮기고 첫 실행으로 기록합니다. 새 앱·워커를 같은 버전으로 배치해야 합니다.
- V10은 범위별 제목 고유 제약·requested_visibility·비공개 reference_solutions를 추가합니다. 중복 제목이 있으면 원자 ALTER가 실패하며 자동 재명명/삭제하지 않습니다.
- V11은 기본 그룹 역할 5개 backfill·그룹 코테·문제 연결·별도 시험 제출·보호 이벤트를 추가합니다.
- V12는 커뮤니티 게시글·댓글과 생성/수정/삭제/복원 이력 테이블을 추가합니다.
- V13은 기존 값을 유지하며 submissions.submitted_at/judged_at을 DATETIME(6)으로 확장합니다. 과거 시각의 미보관 소수초를 추정하지 않습니다.
- `demo.seed=true`이면 DemoData가 문제 번호 1000의 존재를 먼저 확인합니다. 있으면 아무 시드도 실행하지 않습니다. 없으면 체인 레이저 본문·예제·비공개 테스트 10개와 STOPPED인 cobweb-system 작성자를 넣습니다.
- 시드 SQL은 DemoData를 통한 개발용 실행을 전제로 합니다. 임의의 부분 데이터가 있는 DB에 직접 반복 실행하는 범용 마이그레이션이 아닙니다.
- 체인 레이저는 ELITE 5, 1000ms, 256MB, PUBLISHED·PUBLIC·FUNCTION·12개 비-SQL 언어입니다. 시드의 judge_type SPECIAL은 범용 특별 채점기 구현을 의미하지 않습니다.
- 기존 DB를 자동 baseline 처리하거나 기존 데이터의 중복을 자동 정리하지 않습니다. 적용된 마이그레이션을 바꾸는 대신 신규 버전을 추가해야 합니다.

## 오류 명세

### 공통 처리

| 상황 | HTTP | 응답 메시지 |
|---|---|---|
| 경로와 쿼리 숫자 형식 오류 | 400 | 요청 매개변수 형식을 확인해주세요. |
| 잘못된 JSON 또는 필드 타입 | 400 | 요청 JSON 형식을 확인해주세요. |
| DTO 검증 실패 | 400 | 첫 번째 필드 검증 메시지. 모든 오류 목록을 반환하지 않음 |
| ResponseStatusException | 해당 상태 | 지정 reason, 없으면 요청을 처리할 수 없습니다. |
| DB 무결성 제약 위반 | 409 | 중복되거나 참조 조건에 맞지 않는 데이터입니다. |
| 기타 Exception | 500 | Internal Server Error. |

Security 필터 오류나 프록시의 요청 크기 초과는 위 Advice 밖에서 발생할 수 있습니다. 모든 4xx가 동일 JSON으로 표준화된 것은 아니며, 미지원 메서드·쿼리 누락 등 프레임워크 예외 중 별도 처리되지 않은 경로도 있습니다.

### 인증 도메인 오류

아래 이름은 내부 ErrorCode이며 실제 JSON에는 message만 있습니다.

| 내부 이름 | HTTP | message |
|---|---|---|
| USER_NOT_FOUND | 404 | 존재하지 않는 사용자입니다. |
| PASSWORD_MISMATCH | 401 | 비밀번호가 일치하지 않습니다. |
| BANNED_USER | 403 | 정지되었거나 제한된 계정입니다. |
| INVALID_TOKEN | 401 | 유효하지 않은 토큰입니다. |
| EXPIRED_TOKEN | 401 | 만료된 토큰입니다. |
| DUPLICATE_USERNAME | 409 | 이미 사용 중인 아이디입니다. |
| DUPLICATE_EMAIL | 409 | 이미 사용 중인 이메일입니다. |
| EMAIL_NOT_VERIFIED | 400 | 이메일 인증이 필요합니다. |
| EMAIL_VERIFICATION_NOT_FOUND | 404 | 인증 요청을 찾을 수 없습니다. 인증 코드를 다시 요청해주세요. |
| VERIFICATION_CODE_MISMATCH | 400 | 인증 코드가 일치하지 않습니다. |
| VERIFICATION_CODE_EXPIRED | 400 | 인증 코드가 만료되었습니다. 다시 요청해주세요. |
| TOO_MANY_VERIFICATION_REQUESTS | 429 | 인증 코드를 너무 자주 요청했습니다. 잠시 후 다시 시도해주세요. |
| TOO_MANY_AUTH_ATTEMPTS | 429 | 인증 시도가 너무 많습니다. 잠시 후 다시 시도해주세요. |
| INVALID_RESET_TOKEN | 401 | 유효하지 않거나 만료된 토큰입니다. |
| INVALID_OAUTH_STATE | 401 | 유효하지 않은 소셜 로그인 요청입니다. 로그인을 다시 시작해주세요. |
| OAUTH_EMAIL_NOT_FOUND | 400 | 소셜 계정에서 확인된 이메일을 가져올 수 없습니다. |
| OAUTH_SIGNUP_UNAVAILABLE | 403 | 이 제공자의 신규 가입은 이메일 소유권 확인 절차가 준비될 때까지 중단됩니다. |
| UNSUPPORTED_OAUTH_PROVIDER | 400 | 지원하지 않는 소셜 로그인 제공자입니다. |

일반 로그인은 아이디 없음과 비밀번호 불일치의 상태를 구분합니다. 재설정 요청은 등록·미등록 주소 모두 정상 요청에 같은 성공 응답을 주지만, 동기 SMTP의 지연·실패 차이는 남습니다. 가입의 이메일 중복은 유효한 코드 소유 증명 뒤에만 반환합니다.

## 운영 설정

### 설정 항목

비밀값 자체는 문서에 기록하지 않습니다. application.yaml은 선택적으로 루트 .env를 읽습니다.

| 환경변수 또는 설정 | 용도와 기본값 |
|---|---|
| DB_URL | 기본 localhost:3306/cobweb2026 JDBC URL, 기본 URL의 serverTimezone은 Asia/Seoul |
| DB_USERNAME, DB_PASSWORD | DB 접속. 기본 사용자 root, 비밀번호는 외부 값 |
| FLYWAY_ENABLED | 기본 false. local과 prod 프로필은 true |
| JUDGE_ENABLED | 기본 false. true일 때 워커 실행 |
| JUDGE_IMAGE | 기본 cobweb-languages:local |
| JUDGE_WORKSPACE | 기본 /tmp/cobweb-judge |
| JWT_SECRET | JWT HMAC 키. 안전한 외부 비밀값 필요 |
| jwt.access-token-expiration | 1800000 ms |
| jwt.refresh-token-expiration | 1209600000 ms |
| MAIL_USERNAME, MAIL_PASSWORD | SMTP 인증 정보 |
| GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET | Google OAuth 설정 |
| GITHUB_CLIENT_ID, GITHUB_CLIENT_SECRET | GitHub OAuth 설정 |
| NAVER_CLIENT_ID, NAVER_CLIENT_SECRET | Naver OAuth 설정 |
| PUBLIC_BASE_URL | 기본 http://localhost:8080. OAuth callback URL의 기준 |
| DEV_DB_PASSWORD, DEV_ROOT_PASSWORD | 개발 Compose DB 설정 |
| MYSQL_ROOT_PASSWORD, SITE_DOMAIN | 운영 Compose DB와 프록시 설정 |
| WORKER_DB_URL, WORKER_DB_USERNAME, WORKER_DB_PASSWORD | 전용 워커 호스트에서 웹 앱과 동일한 DB의 사설 TLS 엔드포인트 및 전용 계정 |
| TEST_DB_URL, TEST_DB_USERNAME, TEST_DB_PASSWORD | 별도 테스트 DB 접속 설정 |
| DOCKER_JUDGE_TEST | true일 때 Docker 조건부 테스트 실행 |
| demo.seed | true일 때 선택적 1000번 시드. 명시적 Spring 설정으로 전달 |

애플리케이션의 기본 Flyway 비활성화는 기존 DB를 의도 없이 변경하지 않기 위한 설정입니다. `baseline-on-migrate=false`이며 local 프로필은 localhost:3307, 사용자 cobweb, UTC JDBC URL을 사용합니다. prod는 Flyway 활성화, SQL 출력 비활성화, graceful shutdown, 전달 헤더 자동 해석 비활성화입니다. JVM·DB·JSON에 걸친 시간대 통일은 별도 운영 확인이 필요합니다.

### 배포 구성과 신뢰 경계

- 개발 Compose는 MySQL 8.4를 127.0.0.1:3307에 노출하고 영속 볼륨과 healthcheck를 사용합니다.
- 앱 Dockerfile은 Java 21 JRE와 Docker CLI, 빌드된 JAR을 포함하고 기본 UID 10001로 prod 프로필을 실행합니다.
- 운영 Compose의 app은 JUDGE_ENABLED=false이며 Docker 소켓을 받지 않습니다.
- worker는 전용 호스트의 `compose.worker.yaml`에서 같은 버전의 앱 JAR 이미지로 실행합니다. JUDGE_ENABLED=true, user 0:0, Docker 소켓과 호스트 /tmp/cobweb-judge를 마운트합니다. 워커 자체는 root지만 제출 컨테이너는 UID 65534입니다. 웹 호스트의 `compose.prod.yaml`에는 worker가 없습니다.
- worker는 web application과 Flyway를 끄고 Linux capability 제거·no-new-privileges·PID 한도를 둡니다. MySQL을 함께 기동하지 않고 `WORKER_DB_URL`로 웹 앱과 동일한 DB의 사설 TLS 엔드포인트에 연결합니다. 기본 운영 MySQL에는 공개 포트가 없으므로 사설 경로/터널, DB 호스트 인증서, 방화벽, 최소 권한 워커 DB 계정을 별도로 준비해야 합니다. Docker 소켓은 호스트 관리자 권한에 준합니다. 운영 프록시는 app만 대상으로 하며 worker 포트를 호스트에 publish하지 않습니다.
- 제출 컨테이너에는 .env, DB 자격 증명, Docker 소켓, 기대 정답을 전달하지 않습니다.
- Caddy는 80·443을 공개하고 app:8080으로 프록시하며 요청 본문 최대 2MB와 nosniff·no-referrer·DENY 헤더를 설정합니다. DTO상 가능한 큰 테스트 묶음도 이 프록시 제한에는 걸릴 수 있습니다.
- 별도 CORS 허용 정책은 없고 현재 정적 프런트엔드와 API의 same-origin 구성을 전제로 합니다.
- Spring CSRF, form login, HTTP Basic은 비활성화되어 있습니다. refresh가 쿠키 기반이라는 점은 남아 있으며 SameSite=Lax만으로 모든 배치 환경의 CSRF 검토를 대체하지 않습니다.

시작·빌드·테스트 명령은 README를 기준으로 사용합니다. 실제 배포에는 채점 이미지 준비, 검증한 스키마 적용, 새 앱과 워커의 일치하는 버전 배포가 필요합니다. 이미지 빌드 성공이 운영 공개 배포 완료를 의미하지 않습니다.

## 검증 이력

### 2026-10-13 별도 앱·자동 워커와 기능 간 연동

전용 `cobweb_linkage_test`에 Flyway V1–V13을 적용하고 루프백 웹 JAR와 non-web 워커 JAR를 별도 JVM으로 실행했습니다.
`scripts/verify-local-linkage.py`의 HTTP 연동 13개 항목이 통과했습니다. 일반 Python·SQL·1000번 FUNCTION·시험 C++ 제출은
자동 워커에서 모두 ACCEPTED, attempts=1·token/heartbeat 해제·1000번 10케이스 기록을 확인했습니다.
공개/비공개·커뮤니티 연결 보존·댓글/게시글 이력·그룹/시험 권한·시간창·순위·별도 통계·다시보기 경계도 확인했습니다.
관리자 편집의 시험 문제 상태 변경, 그룹의 일반 풀이 잘못된 안내, 로그인 후 경로 복귀 및 숨긴 연결 해제를 수정했습니다.
구형 JSON의 선택 필드 생략을 별도 HTTP 회귀로 확인했고, 전체 `clean build`는 172개 실패/오류/건너뜀 0입니다.
로컬 두 JVM 연동이지 운영 다중 호스트·실제 OAuth/SMTP·부하·원격 CI 승인은 아닙니다.

### 2026-10-13 재검증

기존 테스트 DB의 V12→V13 업그레이드와 새 `127.0.0.1:13309/cobweb_revalidation_test`의 V1→V13을 검증했습니다.
새 계정/화면 세대 검사, 비밀번호·재설정 토큰·개인 DOM 정리, 사용자→문제 잠금 순서, 시험 소수초 저장·UTC 응답을 보강했습니다.
커뮤니티 게시글/댓글 8개 쓰기 경로도 사용자행 선잠금으로 맞췄습니다.
실제 MySQL 병렬 검수/제출, 같은 운영자 및 일반 작성자/별도 운영자의 문제 연결·비공개 전환,
제출 시각≥시작 시각 및 판정 UTC DTO를 새 회귀에 포함했습니다.
전체 Docker 빌드 **171개, 실패/오류/건너뜀 0**, 13언어 결과 가드와 Node 5개 회귀·Compose 3개 검사가 통과했습니다.
실제 브라우저 로그인/로그아웃과 다른 계정 전환에서 개인 자료 제거·시험 카운트다운을 확인했습니다.
외부 연동 및 실제 사용자 DB 전환은 여전히 별도 검증입니다.

### 2026-10-12 현재 코드

새 격리 MySQL 8.4(`127.0.0.1:13309/cobweb_feature_test`)에 Flyway V1–V12를 처음부터 적용했습니다.
`DOCKER_JUDGE_TEST=true ./gradlew --no-daemon --console=plain build`의 최종 결과는
159개 테스트, 실패 0·오류 0·건너뜀 0입니다. 13개 언어·FUNCTION·HTTP 워커 XML 결과 가드도 통과했습니다.
새 제작/그룹/시험/커뮤니티 실제 DB 회귀는 16개이며, Node 회귀 4개 스크립트·Compose 3개·JAR 생성도 통과했습니다.
실제 브라우저에서 테스트 계정 로그인, 제작·관리 페이지, 커뮤니티와 댓글 표시를 확인했습니다.
기존 사용자 DB, 외부 OAuth·SMTP, 운영 서버·원격 CI는 검증 범위가 아닙니다.
아래는 과거 검증 시점의 기록이며 당시의 Docker 연결 장애는 현재 로컬 검증에서 해소됐습니다.

초기 문서는 2026년 9월 30일 작성했고 10월 8일 채점 lease·CI·가입 정보 보호 보강을 반영했습니다. 실제 사용자 DB·SMTP·실계정 OAuth는 사용하지 않았습니다. 아래 전체 검증 기록은 10월 3일 코드에 대한 결과입니다.

2026년 9월 17일 전체 JUnit 보고서는 68개였습니다. 10월 3일 별도 테스트 DB에서 `DOCKER_JUDGE_TEST=true ./gradlew clean build`를 재실행한 최신 결과는 104개, 실패 0, 오류 0, 건너뜀 0입니다. 프런트 인증 흐름의 Node 회귀 4건과 웹·워커 Compose 구성 검사도 통과했습니다.

| 테스트 묶음 | 수 | 검증 범위 |
|---|---|---|
| MvpIntegrationTest | 31 | HTTP 권한·상태·입력 검증, 함수형 입력 계약과 레거시 언어 제한, 번호 동시 발급, 결과 중복 방지, 프로필, JWT, 언어 저장, 실제 Java·SQL·C++ 워커, 체인 레이저 10케이스, 토큰 지문·동시 재발급·시도 제한·코드 교환 경합 |
| DockerJudgeTest | 10 | 함수형 판정, 실제 벽시계 초과, 레거시 Python 입력, 무한 루프, 파일·네트워크 제한, 큰 입출력과 파이프 정체 |
| MultiLanguageJudgeTest | 39 | 13개 언어 실제 입력·정오답, 12개 비-SQL 언어 FUNCTION 실행, 9개 컴파일 오류, SQL 차단, 산출물 읽기 전용, C 시간·메모리, SQL 시간 제한 |
| AuthConcurrencyTest | 2 | 로그인과 비밀번호 재설정 경합, 동시 로그인 실패 카운터 |
| PasswordResetConcurrencyTest | 1 | 같은 재설정 토큰 동시 소비 |
| EmailVerificationServiceTest | 5 | 발송 횟수, 미등록 주소 처리, 소비, 미인증·만료 검사 |
| OAuthServiceTest | 6 | state 쿠키, state 불일치, 신규 이메일 누락, 정지 계정 거절, 네이버 신규 가입 중단·기존 계정 유지 |
| PasswordResetServiceTest | 2 | 비밀번호 변경 시 refresh 폐기, 미등록 주소의 요청 처리 |
| AuthTokenLifecycleIntegrationTest | 2 | 여러 재설정 토큰의 일괄 폐기, 같은 초에 발급된 코드 순서 |
| PasswordResetRequestIntegrationTest | 1 | 미가입 주소의 일반 응답·메일 차단·공유 시도 제한 |
| SecurityStatusIntegrationTest | 2 | 미인증 401과 인증된 권한 부족 403 |
| WorkerContextTest | 1 | Servlet 보안 필터 없이 non-web 워커 컨텍스트 기동 |
| EmailCodeHashTest | 1 | 코드 지문의 이메일·목적·값 결속 |
| Cobweb2026ApplicationTests | 1 | 컨텍스트 기동 |

해당 검증은 별도 cobweb_test DB와 ARM64 Docker 이미지에서 수행한 기록입니다. 모든 CPU 아키텍처·언어 기능·운영 장애·동시성 경로를 완전히 검증했다는 의미는 아닙니다. 테스트 프로필은 .env import를 끄고 기본 127.0.0.1:13307/cobweb_test, Flyway와 데모 시드 활성화, 자동 워커 비활성화를 사용합니다. 실제 사용자 DB에는 V4–V8을 적용하지 않았습니다.

10월 8일 변경의 로컬 검증은 DB 없는 서비스·보안·lease JUnit 22건(실패·오류·건너뜀 0),
앱 JAR 생성, Node 인증 회귀 4건, Compose 3개 설정 검사까지 통과했습니다.
새 JudgeLeaseIntegrationTest와 SignupEmailPrivacyIntegrationTest는 작성했지만 전용 MySQL이
JDBC 연결에 응답하지 않아 DB 전체 실행을 중단했습니다. Docker 관리 API도 무응답이므로 V9 적용,
새 코드의 실제 Docker 채점·운영 이미지 기동은 아직 검증하지 않았습니다.
기존 104개 전체 통과 기록을 현재 코드의 통과 결과로 취급하지 않습니다.

CI는 `.github/workflows/ci.yml`의 quick와 judge-full 작업으로 구성했습니다. quick는 MySQL 연동,
Node 인증, Compose를 검사하고 judge-full은 채점 이미지를 빌드해 `DOCKER_JUDGE_TEST=true`
전체 빌드를 수행합니다. XML 결과에서 13언어·FUNCTION·HTTP 워커 실행이 누락되거나 건너뛰어지면
실패합니다. 액션은 공식 릴리스 커밋으로 고정하고 로그·결과는 7일 보관합니다.
원격 GitHub 실행과 보호 브랜치 필수 검사 설정은 아직 수행하지 않았습니다.

## 미구현과 운영 전 확인 사항

다음 항목은 개발 완료 범위에 포함하지 않습니다.

- 그룹 가입 승인·초대 방식, 소유권 양도, 그룹 삭제/운영 심사(현재 생성 즉시 ACCEPTED·직접 가입).
- 좋아요 관리, 태그·난이도 검색, 일반 문제 상태 이력 조회 API(커뮤니티 수정 이력 조회는 구현).
- 사용자 역할·상태 관리, 회원 탈퇴, 이메일·아이디 변경, 프로필 이미지.
- 임의 시그니처의 범용 함수형 문제, 사용자 지정 검사기, 부분 점수, 부동소수 허용 오차, 효율성 평가.
- 다중 소스 업로드와 외부 패키지 설치, 컴파일 진단·소스·실제 출력 조회 API.
- 재채점·취소·큐 운영 API, SSE·WebSocket, 정밀 CPU·최대 메모리 계측.
- 세션 목록·다중 기기 관리, 로그인 존재 여부 통일 응답, 분산 SMTP 발송 outbox.
- 네이버 신규 가입용 이메일 소유권 확인, OAuth 계정 연결·해제, PKCE, 운영용 토큰 전달 방식.
- API 오류 형식·날짜 형식·필드명 통일, OpenAPI와 Swagger, 전용 상태 점검·메트릭 API.
- 워커 강제 종료 후 잔여 자원 정리, 만료 토큰·감사 로그의 보존 정책, 운영 경보와 중앙 로그, 부하·백업 복구 시험.
- 도메인 DNS·실제 TLS·메일 발송·OAuth 실계정·운영 DB 전환의 최종 검증.

위 항목은 현재 코드에서 확인한 지원 한계이며, 문서화만으로 보완된 것은 아닙니다. 별도 보안 검토 이력은 기존 보안 문서를 참고합니다.

## 구현 근거 파일

| 영역 | 기준 파일 |
|---|---|
| 공통 응답과 오류 | `src/main/java/com/drewdrew0414/global/common/ApiResponse.java`, `global/exception/GlobalExceptionHandler.java`, `global/exception/ErrorCode.java` |
| HTTP 보안 | `src/main/java/com/drewdrew0414/global/config/SecurityConfig.java`, `global/security/jwt/`, `global/security/CustomUserDetails.java` |
| 회원 계약 | `src/main/java/com/drewdrew0414/domain/user/controller/`, `domain/user/dto/` |
| 회원 처리와 외부 연동 | `src/main/java/com/drewdrew0414/domain/user/service/`, `domain/user/oauth/`, `domain/user/repository/` |
| 문제 | `src/main/java/com/drewdrew0414/domain/problem/ProblemController.java`, `ProblemRequests.java`, `ProblemService.java` |
| 언어와 제출 | `src/main/java/com/drewdrew0414/domain/submission/Language.java`, `LanguageController.java`, `SubmissionController.java`, `SubmissionService.java` |
| 워커와 격리 | `src/main/java/com/drewdrew0414/domain/submission/JudgeWorker.java`, `DockerJudge.java`, `judge/runner.py`, `judge/function/`, `judge/Dockerfile`, `judge/csharp/` |
| 프로필과 랭킹 | `src/main/java/com/drewdrew0414/domain/user/controller/ProfileController.java` |
| 물리 스키마 | `src/main/resources/db/migration/V1__initial_schema.sql`부터 `V13__submission_timestamp_precision.sql`까지의 순차 마이그레이션 |
| 그룹/시험 | `src/main/java/com/drewdrew0414/domain/group/`, `domain/submission/ContestSubmissionController.java` |
| 커뮤니티/공지/보존 | `src/main/java/com/drewdrew0414/domain/community/`, `V12__community_history.sql` |
| 데모 | `src/main/java/com/drewdrew0414/global/config/DemoData.java`, `src/main/resources/demo/chain-laser.sql` |
| 설정과 배포 | `src/main/resources/application*.yaml`, `build.gradle.kts`, `compose.dev.yaml`, `compose.prod.yaml`, `Dockerfile`, `deploy/Caddyfile` |
| 테스트 | `src/test/java/`, `src/test/resources/application-test.yaml` |

위 경로는 저장소 루트 기준이며 표에서 축약한 동급 파일은 같은 패키지에 있습니다. 기존 문서의 역할은 다음과 같이 구분합니다.

- README.md는 실행 명령과 연동 시작점입니다.
- PROGRESS.md는 진행 현황과 남은 작업입니다.
- docs/security/REVIEW.md는 과거 결함 재현과 보안 검증 기록입니다.
- docs/BACKEND_SPEC.md는 현재 구현의 API와 동작 계약입니다.
