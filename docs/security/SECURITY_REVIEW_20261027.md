# 2026-10-27 권한·예외 보완 및 Claude Sonnet 5 보안 검토

## 결론과 증거 범위

권한·예외 경계의 재현 가능한 보완을 구현했고, 별도 로컬 환경에서 Java/MySQL/Docker 375개·프런트 185개 검증을 통과했습니다.
Claude CLI의 **주 감사 모델을 `claude-sonnet-5`로 명시**하여 Strix 유래 절차의 독립적인 소스 검토도 완료했습니다.
추가로 확정한 권한 우회·제출 코드의 호스트 탈출은 없습니다. 이것은 취약점이 없다는 증명이나 운영 환경 침투 테스트 완료를 뜻하지 않습니다.

Strix의 공격자/진입점/신뢰 경계 → 소스/통제/싱크 → 반증 → 재현/수정 확인 절차를 참고했습니다.
원본: [usestrix/strix, 고정 커밋](https://github.com/usestrix/strix/tree/910c1ea4bbf22f8c01ab6c2a54bad09b94ccbc3b),
특히 [counterevidence](https://github.com/usestrix/strix/blob/910c1ea4bbf22f8c01ab6c2a54bad09b94ccbc3b/strix/skills/analysis/counterevidence.md).
Strix 엔진·공격 프록시·클라우드 스캐너를 실행한 것은 아닙니다.

## 실제 CLI 실행

- 기존 Claude CLI 로그인으로 실행했습니다. 자식 프로세스에서 별도 API 키 환경변수를 전달하지 않았습니다.
- `--safe-mode --model claude-sonnet-5 --tools '' --permission-mode dontAsk --strict-mcp-config --mcp-config '{"mcpServers":{}}' --disable-slash-commands --no-session-persistence --output-format json -p`를 사용했습니다.
- 파일/셸/MCP 도구를 모두 끄고 허용 목록 소스만 표준 입력으로 전달했습니다. 실제 `.env`, `pw.txt`, 로컬 설정·비밀번호·DB·로그·다른 프로젝트·키체인 내용은 소스 묶음에서 제외했습니다.
- 광범위 실행 1회는 계획만 반환했고, 두 번째 실행은 제한 시간 안에 완결 보고서를 반환하지 못했습니다. 둘 다 **완료된 감사로 계산하지 않습니다**.
- 이후 권한/인증 27개 파일, 채점/실행기 12개 파일로 분할했습니다(중복 제외 36개 핵심 파일). 두 보고서 모두 `is_error=false`, `subtype=success`, `num_turns=1`입니다.
- 결과의 `modelUsage`에 `claude-sonnet-5`를 확인했습니다. CLI 보조 호출로 `claude-haiku-4-5-20251001` 기록도 있으므로 모든 내부 호출이 Sonnet 5였다고 주장하지 않습니다. 명시적인 fallback 모델은 지정하지 않았습니다.
- 원본 보고서와 파일별 SHA-256 manifest는 `.local/verification/exception-security-20261027/sonnet5-{access,judge}-{result,manifest}.json`에 보존했습니다. 재실행 도구는 같은 폴더의 `run-focused-claude.mjs`입니다.

## 후보와 코디네이터의 반증 확인

| 후보 | 판정 | 확인한 근거와 남은 범위 |
|---|---|---|
| 타인의 비공개 문제·제작자 노출 | 차단 확인 | `ProblemService.load`의 작성자/운영자/그룹 권한 검사와 `ProblemAuthorProjection`의 비공개 이름 null 처리. 실제 HTTP 권한 행렬과 기존 제작자 회귀 통과 |
| 그룹 승인 취소·탈퇴 후 권한 유지 | 수정·로컬 검증 완료 | `GroupService`가 현재 DB의 ACTIVE 계정·ACCEPTED 그룹을 요구. WAITING/REJECTED 전환, 탈퇴, 역할 강등/정지에 동일 JWT를 사용한 거절과 DB 미저장을 확인 |
| Markdown 저장형 XSS | 제기된 경로에서 차단 근거 확인 | 최초 CLI 묶음에는 렌더러가 빠져 후보만 제기됨. 실제 `static/markdown.js`는 text node/허용 DOM만 생성하고 URL 스킴을 제한. 후속 Sonnet 보고서도 해당 통제를 확인했고 별도 Markdown 31개 검사 통과 |
| 동일 코드 판정 캐시의 테스트 변경 누락 | 차단 확인 | 테스트 ID/본문/기대값/비교/순서·ABI/제한·엔진/이미지와 원문 바이트를 비교. 기존 실제 MySQL 재사용 회귀 통과 |
| 가입 인증의 `isVerified`와 소비 사이 경합 | 확정 취약점 아님, 전용 경합 검증 여지 | Sonnet이 초기 가벼운 조회에 잠금이 없다는 공백을 제기했으나, 이후 `verifyCode`와 `consumeVerification`은 가입 트랜잭션 안에서 같은 guard를 잠그고 PESSIMISTIC_WRITE로 최신 기록을 확인함. 코드/이메일 재사용 회귀는 있으나 이 특정 동시 가입 인터리빙을 이번에 별도로 실측한 것은 아님 |
| 오래된 워커의 결과 덮어쓰기 | 결과 무결성 차단 확인 | `judge_token` fencing과 RUNNING 상태 검증. 실제 MySQL의 lease 회수·새 토큰·오래된 완료/heartbeat 거절 회귀 통과. 장시간 SIGSTOP/호스트 장애의 자원 잔존은 별개 운영 공백 |
| 워커의 Docker 소켓 권한 | 알려진 운영 잔여 위험 | 워커의 데몬 접근은 강한 호스트 권한임. 제출 컨테이너에는 소켓을 전달하지 않고 non-root·네트워크 차단·cap-drop·읽기 전용·자원 제한을 적용. 커널/런타임 취약점까지 배제하는 탈출 증명은 없음. 웹/워커 호스트 분리 정책과 런타임 패치·고립된 채점 호스트가 필요 |

추가 관찰: 로그인 실패의 `UserNotFoundException`(404), `PasswordMismatchException`(401), 계정 제한 오류(403)는 구분됩니다.
회원명이 이미 공개 프로필/랭킹의 식별자라는 정책 때문에 이것만으로 계정 장악이나 비공개 자료 노출을 주장하지 않습니다.
인증 실패 응답을 통일하는 강화는 별도 제품/호환성 선택으로 남깁니다. 시도 제한과 실패 감사 로그는 이미 적용되어 있습니다.

Sonnet 원본 보고서의 파일 줄번호는 **해당 입력 manifest 시점** 기준이며, 그 뒤의 줄 이동과 원본 보고서의 추측을 현재 사실로 혼동하지 않습니다.
예를 들어 그룹 상태 취소 뒤 다시보기 권한은 `GroupService.can(TAKE_CONTESTS)`의 현재 상태 검사로 차단되며, 보관된 문제의 허용된 시험 원본 접근과는 다른 조건입니다.

## 구현한 예외 경계

- 도메인의 165개 요청 예외를 `ApiException`의 안정적인 타입으로 분류했습니다. HTTP 상태·트랜잭션 롤백 조건은 유지합니다.
- 입력/JSON/검증·메서드·미디어·용량·인증/권한·DB 충돌/잠금/가용성·외부 제공자/메일·예상외 실패를 공통 처리합니다.
- 응답에 `errorCode`, 예상외 500에 불투명한 `errorId`를 제공합니다. SQL·비밀값·제출 코드·예외 원문을 응답에 복사하지 않습니다.
- 인증 필터의 DB 장애는 익명으로 진행하지 않고 JSON 503으로 종료합니다.
- OAuth의 빈 토큰/식별자·다른 제공자·과대 식별자·빈 state/code는 안전하게 거절합니다. 실제 OAuth 성공은 모의 검증과 구분합니다.
- 중단된 워커의 새 작업/완료 처리와 정리 과정의 인터럽트 소실을 보완했습니다. 선택적 캐시 miss, 워커 생존과 정리 fallback의 의도된 경계는 유지합니다.
- 프런트는 요청/네트워크/프로토콜/세션 오류 타입과 렌더 오류 경계를 사용합니다. 개인 초안을 폐기하고 명시적으로 재열며 변경 요청을 자동 재전송하지 않습니다.

## 검증 기록

- 전체 Java/MySQL/Docker: **375개 통과, 실패·오류·건너뜀 0**.
- 전체 결과: `.local/verification/exception-security-20261027/full-java-375/`.
- 결과 가드: 17개 언어·15개 FUNCTION 어댑터·실제 HTTP/워커 실행 확인.
- 최종 공통 예외/시스템 그룹 집중 회귀: **13개 통과**. 최종 JAR 빌드 성공.
- 최종 프런트 보호 실행기: **185개 통과**. 파일별 힙 192MiB·프로세스 그룹 RSS 512MiB 제한 유지.
- 별도 Markdown: **31개 검사 통과**.
- 테스트 DB는 검증 후 중지하고 데이터/볼륨은 보존했습니다. 사용자 서버/DB에는 재시작·마이그레이션·권한 변경을 하지 않았습니다.

## 미검증 운영 범위

외부 OAuth/SMTP/프록시의 실제 연동, 운영 배포·다중 호스트·부하·커널/컨테이너 탈출·SIGKILL/전원/데몬 장애의 완전한 정리는 증명하지 않았습니다.
이번 CLI 감사에는 외부 네트워크 스캔·의존성 CVE DB 대조·운영 자격 증명 테스트를 수행하지 않았습니다.
따라서 “모든 위험 제거”, “전 코드 무결함”, “동적 Strix 침투 테스트 통과”로 해석하면 안 됩니다.
