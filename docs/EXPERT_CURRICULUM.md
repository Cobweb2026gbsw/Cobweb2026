# EXPERT — 재귀·그래프·동적 계획법 80문제

2026-10-28 기준으로 추가한 Lv.3 과정입니다. 이전 ELITE 다음 랭크인 **EXPERT**에 사용자 목록 순서 그대로 배정했습니다.
문제 데이터의 단일 원본은 [level3.json](../src/main/resources/problems/level3.json)이며, 아래 번호·제목·서명은 이 파일에 근거합니다.

## 구성

| 영역 | 주제 | 문제 번호 | 등급 | 문제 수 |
| --- | --- | --- | --- | --- |
| Lv.3-1 | 재귀와 백트래킹 | 1266~1281 | EXPERT 5 | 16 |
| Lv.3-2 | DFS와 BFS | 1282~1297 | EXPERT 4 | 16 |
| Lv.3-3 | 동적 계획법 | 1298~1313 | EXPERT 3 | 16 |
| Lv.3-4 | 그래프와 트리 기초 | 1314~1329 | EXPERT 2 | 16 |
| Lv.3-5 | 그리디·탐색·복합 구현 | 1330~1345 | EXPERT 1 | 16 |

80문제 모두 한글 본문·입출력 설명·제한 사항·공개 예제 2개·Python 기준 풀이를 포함합니다.
공개 예제는 총 160개, 비공개 채점 케이스는 총 **1,187개**입니다. 각 문제에는 고유 입력 10~50개가 있으며,
공개 예제의 입력·정답은 해당 문제 채점 케이스에도 포함되므로 두 수치를 서로 다른 입력 개수로 합산하지 않습니다.
시간 제한은 문제당 10,000ms, 메모리 제한은 512MiB입니다.

기존 Lv.0 100문제·SKILLED 75문제·ELITE 90문제와 체인 레이저 1문제는 그대로 유지합니다.
이번 80문제를 더한 기본 과정의 총합은 **346문제**입니다. 체인 레이저의 기존 1100번과 새 문제 1297번의 주제는 연결되지만 별개의 문제입니다.

## 함수형 채점과 결정 규칙

표준 입출력을 직접 작성하지 않고 공개 함수 서명의 `sol` 반환값을 채점합니다. 기존 `GENERIC_V1` ABI를 그대로 사용합니다.
지원 언어는 Python, C, C++, Java, JavaScript, TypeScript, C#, Go, Rust, Kotlin, Ruby, LLVM IR, Pascal, PHP, Dart의 **15개**입니다.
SQL·엄랭은 이 함수형 과정에서 제외합니다. 언어별 시작 코드와 C·LLVM IR 등의 포인터/길이 규약은 [GENERIC.md](../judge/function/GENERIC.md)를 따릅니다.

- 인자는 최대 6개이며 타입은 `int`, `float`, `bool`, `string`, `int[]`, `string[]`, `int[][]`, `string[][]`입니다.
- 정수 인자와 정수 반환값은 부호 있는 32비트 범위입니다. 곱셈·합계·모듈러 중간 계산은 필요한 경우 64비트를 사용합니다.
- 문자열은 ASCII이며 하나당 16KiB 이내입니다. 모든 인자의 배열 슬롯(바깥 행+내부 원소) 합과 반환값 배열 슬롯은 각각 100만 개 이내입니다.
- 테스트 입력은 내부적으로 `{"args":[...]}`를 사용합니다. 입력·정답 JSON 각각 UTF-8 16MiB 상한이며, 화면 예제는 기존 예제 포매터를 사용합니다.
- 문자열·불리언·정수·배열의 순서는 정확히 비교합니다. 실수 반환은 절대 또는 상대 오차 `1e-9`를 허용합니다.
- 재귀·메모이제이션·백트래킹·DP·행렬 거듭제곱 등의 표현은 학습 목표입니다. 채점기는 소스의 특정 구문이나 알고리즘 형태 자체를 강제하지 않습니다.
- 같은 최적값을 만드는 정답이 여럿인 문제는 경로의 사전순, 정점 번호, 종료 시각 등 본문에 명시한 규칙으로 정답을 하나로 고정합니다. 미도달·빈 입력·중복 선택·나눗셈 규칙도 문제별 본문을 따릅니다.
- 1300번 피보나치, 1304번 동전 조합, 1305~1306번 타일과 1311~1312번 격자 경로는 결과를 `1,000,000,007`로 나눈 나머지입니다. 다른 문제에 이 규칙을 임의 적용하지 않습니다.

### 3차원 데이터와 혼합 길이 반환

1296번 3차원 토마토는 새로운 3차원 ABI를 추가하지 않습니다. `layers[z]`에 해당 층의 `rows×cols` 칸을 행 우선으로 펴서 저장하고,
좌표 `(z,r,c)`의 값은 `layers[z][r*cols+c]`로 읽습니다. 인자는 `layers: int[][]`, `rows: int`, `cols: int`입니다.

경로와 부가 정보를 함께 반환할 때는 행 길이가 서로 다른 `int[][]`를 사용합니다.

| 문제 | 반환 모양과 규칙 |
| --- | --- |
| 1287 미로 최단 경로 1 | `[[거리],[시작 행,시작 열],...,[도착 행,도착 열]]`, 불가능하면 `[[-1]]`; 최단 좌표열 중 사전순 최소 |
| 1294 숨바꼭질 1 | `[[거리],[시작 위치,...,목표 위치]]`; 최단 위치열 중 사전순 최소 |
| 1302 1로 만들기 1 | `[[연산 횟수],[n,...,1]]`; 최단 값 배열 중 사전순 최소 |
| 1330 최대 회의 개수 1 | `[[개수],[선택한 회의 번호들]]`; 종료·시작·입력 번호 오름차순 탐욕 선택 |
| 1333 최소 점프 횟수 1 | `[[도달 여부],[방문 인덱스들]]`; 불가능하면 `[[0],[]]`, 가능하면 사전순 최소 최단 경로 |

정답 코드와 비공개 테스트 입력은 공개 API로 제공하지 않습니다. 풀이 화면의 비공개 테스트 기능은 기존대로 실제 채점 케이스 2개를 실행하고 통과/실패·시간만 보여줍니다.

## 생성과 검증

저장소 루트에서 실행합니다. 생성 명령은 새 `level3.json`만 만들며 기존 `level0.json`~`level2.json`이나 DB를 쓰지 않습니다.
생성기는 고정된 문제 정의와 별도 구현 오라클을 비교하고, 고유 입력·번호·제목·타입·크기를 검사합니다.
이 중 최대 규모 경계 12케이스는 느린 완전탐색 오라클 대신 수학적으로 결과가 결정되는 구조를 사용합니다.
500원소 재귀·DFS, 30×30×30 토마토, 100×100 레이저, 10만 원소 LIS·LDS·트리·회의·점프,
300×300 미로와 8×8 연구소를 포함하며, 별도로 명시한 분석 정답과 기준 풀이 결과를 비교합니다.
호스트에서는 저장소의 고정 저작 기준 풀이만 실행하며 사용자 제출 코드는 실행하지 않습니다.

```sh
python3 -I -B scripts/build-expert-catalog.py
python3 -I -B -m unittest discover -s src/test/python -p 'test_expert_catalog.py'
./gradlew --offline --no-daemon test --tests '*ExpertCatalogContractTest'
```

PostgreSQL 가져오기 회귀는 별도로 준비한 **전용 테스트 DB**에서 실행합니다. 기존 개발·실사용 DB를 테스트 대상으로 지정하지 마세요.
`TEST_DB_URL`, `TEST_DB_USERNAME`, `TEST_DB_PASSWORD`를 테스트 환경에 맞게 설정한 뒤 아래 명령을 실행합니다.
`ExpertDataIntegrationTest`의 데이터 변경은 테스트의 롤백 트랜잭션 안에서만 검증합니다.

```sh
./gradlew --offline --no-daemon test --tests '*ExpertDataIntegrationTest'
```

실제 Docker에서 EXPERT 80개 Python 기준 풀이의 모든 저장 케이스를 재채점하는 선택 검사는 다음과 같습니다.
Docker와 현재 채점 이미지 `cobweb-languages:local`을 먼저 준비하세요. 아래 명령은 기존 입문·알고리즘 카탈로그 전체 재채점과 분리됩니다.

```sh
DOCKER_JUDGE_TEST=true GENERIC_CATALOG_DOCKER_TEST=false \
EXPERT_CATALOG_DOCKER_TEST=true ./gradlew --offline --no-daemon test \
  --tests '*GenericExpertCatalogJudgeTest'
```

이 검사는 정답 기준 풀이의 80문제 실행과 잘못된 스칼라·중첩 배열 정답의 거절을 확인합니다.
15개 언어의 ABI 공통 회귀는 별도 `GenericFunctionJudgeTest`·`GenericNestedFunctionJudgeTest`로 확인합니다.
80문제의 Python 기준 풀이 검증이 곧 80×15개의 언어별 전수 실행이나 외부 운영 환경 검증을 의미하지 않습니다.
실제 완료한 검사와 건너뛴 조건부 검사의 결과는 [PROGRESS.md](../PROGRESS.md)를 기준으로 확인하세요.

## 등록과 보존 범위

웹 기동에서 기존 `curriculum.level0.enabled` 설정이 켜져 있으면 `AlgorithmData`가 Lv.1·2·3과 체인 레이저를 검사한 후 최초 등록합니다.
새 과정 키는 `level3-v1`입니다. 번호·제목·과정 이력에 충돌이 있으면 조용히 덮어쓰지 않고 가져오기를 거절합니다.
기존 카탈로그 바이트와 과정 지문은 유지하며, 이미 가져온 문제의 관리자 수정 내용·공개 상태·기존 제출은 다시 덮어쓰지 않습니다.
번호 시퀀스는 가져오기 후의 최대 번호를 반영하며 사용자 문제와의 충돌을 막습니다.

문제 생성·단위 테스트만으로 실사용 DB를 변경하지 않습니다. 이후 사용자의 `./run.sh` 기동 수정 요청에 따라 2026-10-28 최종 빌드로 로컬 PostgreSQL 서버를 시작했고, 전체 346문제와 EXPERT 1266~1345번의 공개 조회를 확인했습니다. 기존 MySQL 자료는 이관하거나 삭제하지 않았습니다.
다른 환경의 실사용 반영은 검증한 새 빌드로 서버를 시작하거나 재시작하는 별도 작업입니다. DB 초기화·기존 문제 삭제·이력 덮어쓰기를 반영 절차로 사용하지 마세요. 최종 결과와 증거 범위는 [PROGRESS.md](../PROGRESS.md)를 확인하세요.

## 80문제 인덱스

### Lv.3-1 — 재귀와 백트래킹 · EXPERT 5

| 번호 | 문제명 | 공개 함수 서명 |
| --- | --- | --- |
| 1266 | 재귀 팩토리얼 | `sol(n: int) → int` |
| 1267 | 피보나치 수열 2 | `sol(n: int) → int` |
| 1268 | 재귀로 배열 뒤집기 | `sol(numbers: int[]) → int[]` |
| 1269 | 재귀로 자릿수 합 | `sol(n: int) → int` |
| 1270 | 하노이의 탑 | `sol(n: int) → int[][]` |
| 1271 | 순열 생성 1 | `sol(numbers: int[], r: int) → int[][]` |
| 1272 | 조합 생성 1 | `sol(numbers: int[], r: int) → int[][]` |
| 1273 | 중복 순열 | `sol(numbers: int[], r: int) → int[][]` |
| 1274 | 중복 조합 | `sol(numbers: int[], r: int) → int[][]` |
| 1275 | 부분집합의 합 | `sol(numbers: int[], target: int) → int` |
| 1276 | N-Queen | `sol(n: int) → int` |
| 1277 | N-Queen 1 | `sol(n: int) → int[][]` |
| 1278 | 암호 만들기 | `sol(letters: string, length: int) → string[]` |
| 1279 | 연산자 끼워 넣기 | `sol(numbers: int[], counts: int[]) → int[]` |
| 1280 | 숫자 조합 소수 | `sol(digits: string) → int` |
| 1281 | 스도쿠 해결 | `sol(board: int[][]) → int[][]` |

### Lv.3-2 — DFS와 BFS · EXPERT 4

| 번호 | 문제명 | 공개 함수 서명 |
| --- | --- | --- |
| 1282 | 그래프 DFS | `sol(n: int, edges: int[][], start: int) → int[]` |
| 1283 | 그래프 BFS | `sol(n: int, edges: int[][], start: int) → int[]` |
| 1284 | 연결 요소의 개수 | `sol(n: int, edges: int[][]) → int` |
| 1285 | 연결 요소의 크기 | `sol(n: int, edges: int[][]) → int[]` |
| 1286 | 미로 최단 경로 | `sol(grid: int[][]) → int` |
| 1287 | 미로 최단 경로 1 | `sol(grid: int[][]) → int[][]` |
| 1288 | 단지 번호 붙이기 | `sol(grid: int[][]) → int[]` |
| 1289 | 섬의 개수 | `sol(grid: int[][]) → int` |
| 1290 | 섬의 개수 1 | `sol(grid: int[][]) → int` |
| 1291 | 바이러스 전파 | `sol(n: int, edges: int[][], start: int) → int` |
| 1292 | 최단 거리 배열 | `sol(n: int, edges: int[][], start: int) → int[]` |
| 1293 | 숨바꼭질 | `sol(x: int, target: int, limit: int) → int` |
| 1294 | 숨바꼭질 1 | `sol(x: int, target: int, limit: int) → int[][]` |
| 1295 | 토마토 익히기 | `sol(tomatoes: int[][]) → int` |
| 1296 | 토마토 익히기 1 | `sol(layers: int[][], rows: int, cols: int) → int` |
| 1297 | 연쇄 레이저 1 | `sol(matrix: int[][], startRow: int, startCol: int) → int` |

### Lv.3-3 — 동적 계획법 · EXPERT 3

| 번호 | 문제명 | 공개 함수 서명 |
| --- | --- | --- |
| 1298 | 계단 오르기 | `sol(n: int) → int` |
| 1299 | 계단 오르기 1 | `sol(scores: int[]) → int` |
| 1300 | 피보나치 수열 3 | `sol(n: int) → int` |
| 1301 | 1로 만들기 | `sol(n: int) → int` |
| 1302 | 1로 만들기 1 | `sol(n: int) → int[][]` |
| 1303 | 최소 동전 개수 1 | `sol(coins: int[], amount: int) → int` |
| 1304 | 동전 조합 | `sol(coins: int[], amount: int) → int` |
| 1305 | 타일 채우기 | `sol(n: int) → int` |
| 1306 | 타일 채우기 1 | `sol(n: int) → int` |
| 1307 | 최대 연속 합 1 | `sol(numbers: int[]) → int` |
| 1308 | 가장 긴 증가 부분 수열 | `sol(numbers: int[]) → int` |
| 1309 | 가장 긴 감소 부분 수열 | `sol(numbers: int[]) → int` |
| 1310 | 정수 삼각형 | `sol(triangle: int[][]) → int` |
| 1311 | 격자 경로의 수 | `sol(n: int, m: int) → int` |
| 1312 | 격자 경로의 수 1 | `sol(grid: int[][]) → int` |
| 1313 | 배낭 문제 | `sol(weights: int[], values: int[], capacity: int) → int` |

### Lv.3-4 — 그래프와 트리 기초 · EXPERT 2

| 번호 | 문제명 | 공개 함수 서명 |
| --- | --- | --- |
| 1314 | 트리의 부모 찾기 | `sol(n: int, edges: int[][]) → int[]` |
| 1315 | 트리의 깊이 | `sol(n: int, edges: int[][], root: int) → int[]` |
| 1316 | 트리의 높이 | `sol(n: int, edges: int[][], root: int) → int` |
| 1317 | 트리의 리프 개수 | `sol(n: int, edges: int[][], root: int) → int` |
| 1318 | 이진 트리 전위 순회 | `sol(values: int[], left: int[], right: int[], root: int) → int[]` |
| 1319 | 이진 트리 중위 순회 | `sol(values: int[], left: int[], right: int[], root: int) → int[]` |
| 1320 | 이진 트리 후위 순회 | `sol(values: int[], left: int[], right: int[], root: int) → int[]` |
| 1321 | 이진 트리 레벨 순회 | `sol(values: int[], left: int[], right: int[], root: int) → int[][]` |
| 1322 | 이진 탐색 트리 검사 | `sol(values: int[], left: int[], right: int[], root: int) → bool` |
| 1323 | 트리의 지름 | `sol(n: int, edges: int[][]) → int` |
| 1324 | 이분 그래프 판별 | `sol(n: int, edges: int[][]) → bool` |
| 1325 | 사이클 판별 | `sol(n: int, edges: int[][]) → bool` |
| 1326 | 사이클 판별 1 | `sol(n: int, edges: int[][]) → bool` |
| 1327 | 위상 정렬 | `sol(n: int, edges: int[][]) → int[]` |
| 1328 | 선수 과목 | `sol(n: int, edges: int[][]) → bool` |
| 1329 | 도시 연결 | `sol(n: int, edges: int[][]) → bool` |

### Lv.3-5 — 그리디·탐색·복합 구현 · EXPERT 1

| 번호 | 문제명 | 공개 함수 서명 |
| --- | --- | --- |
| 1330 | 최대 회의 개수 1 | `sol(meetings: int[][]) → int[][]` |
| 1331 | 회의실 배정 | `sol(meetings: int[][]) → int` |
| 1332 | 최소 점프 횟수 | `sol(numbers: int[]) → int` |
| 1333 | 최소 점프 횟수 1 | `sol(numbers: int[]) → int[][]` |
| 1334 | 구간 덮기 | `sol(intervals: int[][], left: int, right: int) → int` |
| 1335 | 구간 병합 1 | `sol(intervals: int[][], point: int) → int[]` |
| 1336 | 물류 상자 배치 | `sol(weights: int[], limit: int) → int` |
| 1337 | 랜선 자르기 | `sol(lengths: int[], k: int) → int` |
| 1338 | 나무 자르기 | `sol(heights: int[], required: int) → int` |
| 1339 | 공유기 설치 | `sol(houses: int[], c: int) → int` |
| 1340 | 예산 배분 | `sol(requests: int[], budget: int) → int` |
| 1341 | 숫자 카드 합치기 | `sol(cards: int[]) → int` |
| 1342 | 작업 스케줄러 | `sol(durations: int[]) → float` |
| 1343 | 로봇 이동 4 | `sol(grid: int[][], start: int[], target: int[]) → int` |
| 1344 | 미로 최단 경로 2 | `sol(grid: int[][], start: int[], target: int[]) → int` |
| 1345 | 연구소 안전 구역 | `sol(grid: int[][]) → int` |
