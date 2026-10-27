# 엄랭 v2 원본 런타임

- 원본 저장소: <https://github.com/rycont/umjunsik-lang>
- 고정 커밋: `e973f9d22b9803ee86b53d8e60f1b65ab08c7547` (2024-05-06)
- 구현: [`umjunsik-lang-python/runtime.py`](https://github.com/rycont/umjunsik-lang/blob/e973f9d22b9803ee86b53d8e60f1b65ab08c7547/umjunsik-lang-python/runtime.py)
- 라이선스: MIT, 원문은 이 디렉터리의 `LICENSE`에 보존합니다.
- 조회일: 2026-10-20. 실행 중 다운로드하거나 pip 패키지를 설치하지 않습니다.

`runtime.py`와 `LICENSE`는 마지막 LF 개행 하나를 추가한 것 외에는 해당 커밋의 원문입니다.
원본의 공백과 구현을 보존하며, 채점용 변경은 별도 `../../umjunsik_cli.py`에만 둡니다.

| 파일 | upstream SHA-256 | 포함본 SHA-256 |
|---|---|---|
| runtime.py | `851d1b420a7720f24ccdc0ce52c9eb89d83097bbcfd4179286a9011706cc7722` | `520642d8dfcdd811172799a4a26fc5c8d809ba9a97da8ce7259d5fdc01c4fc53` |
| LICENSE | `ca6ca5d8e587b5c8a3f8308214ecb7257c7d29c4d90b958cfeaaa4ac7b9e5321` | `e353712979d210ca3c549b807d836efeeeeb8091c00b4a380c9334a95378248b` |

## 채점 어댑터의 명시적 보완

원본 Python의 변수 저장·정수 계산·반복/분기 실행 루프를 사용합니다. 원본에는 `eval`/`exec`나 외부 의존성이 없습니다.
엄랭은 자체 함수 호출 규약이 없으므로 `STDIN` 모드만 지원하고 `.umm` 소스를 받습니다.

- 공식 v2 문법 외의 명령은 원본처럼 조용히 무시하지 않고 오류로 거절합니다. README에 초안/미구현으로 표시된 v3 문법도 지원하지 않습니다.
- `식?`는 표준 채점 입력과 같이 공백 또는 개행으로 구분된 정수를 읽으며, 입력 부족/비정수는 오류입니다.
- `화이팅!`은 공식 v2 문서대로 종료값으로 사용합니다. 원본 Python의 숫자 출력 부작용은 적용하지 않습니다.
- 잘못된 줄 이동은 음수 Python 인덱스로 처리하지 않고 오류로 거절합니다. 줄 번호는 빈 줄을 포함한 원본 줄 순서를 유지합니다.
- 원본의 임의 100,000단계 제한은 끄고, 다른 언어와 같은 Docker 시간/메모리/출력 제한으로 무한 루프를 종료합니다.
- CRLF와 파일 끝 개행을 정규화합니다. `~` 한 줄 형식의 마지막 개행도 허용합니다.

이 어댑터는 샌드박스가 아닙니다. 제출은 호스트가 아닌 기존 격리 컨테이너에서만 실행해야 합니다.

검사: `python3 -I -B -m unittest discover -s src/test/python -p 'test_umjunsik*.py'`.
