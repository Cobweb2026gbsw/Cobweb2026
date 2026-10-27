#!/usr/bin/env python3
"""전용 로컬 DB·앱에서 문제→커뮤니티와 그룹→코테의 실제 HTTP 연동을 확인합니다.

실행 전 root가 cobweb_linkage_test를 만들고 웹 앱의 Flyway V1~V17 및 자동 채점
워커를 켜야 합니다. 테스트 계정과 콘텐츠는 보존하며 SQL DELETE는 사용하지 않습니다.
"""

import json
import subprocess
import sys
import time
import uuid
from datetime import datetime, timedelta, timezone
from urllib import error, parse, request


APP = "http://127.0.0.1:18083"
CONTAINER = "cobweb-feature-verify-mysql"
DATABASE = "cobweb_linkage_test"
PASSWORD = "Feature-test-only-2026!"
CHECKS = []
IDS = {}
USER_NAMES = {}


class CheckError(Exception):
    pass


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


OPENER = request.build_opener(request.ProxyHandler({}), NoRedirect)


def require(condition, label):
    if not condition:
        raise CheckError(label)


def phase(label):
    print("[linkage] " + label, flush=True)


def check_targets():
    base = parse.urlsplit(APP)
    require(base.scheme == "http" and base.hostname in {"127.0.0.1", "localhost", "::1"}
            and base.port == 18083 and not base.username and not base.password
            and not base.path and not base.query and not base.fragment,
            "앱 대상은 지정된 18083 루프백만 허용합니다")
    require(CONTAINER == "cobweb-feature-verify-mysql" and DATABASE == "cobweb_linkage_test",
            "전용 Docker 컨테이너·DB만 허용합니다")
    applied = int(scalar("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 "
                         "AND CAST(version AS UNSIGNED) BETWEEN 1 AND 17"))
    maximum = int(scalar("SELECT COALESCE(MAX(CAST(version AS UNSIGNED)),0) "
                         "FROM flyway_schema_history"))
    failures = int(scalar("SELECT COUNT(*) FROM flyway_schema_history WHERE success=0"))
    require(applied == 17 and maximum == 17 and failures == 0,
            "전용 DB Flyway V1~V17 적용/성공 조건 미충족")
    seed = int(scalar("SELECT COUNT(*) FROM problems WHERE problem_number=1000 "
                      "AND status='PUBLISHED' AND visibility='PUBLIC' AND execution_mode='FUNCTION'"))
    require(seed == 1, "전용 DB의 공개 FUNCTION 시드 문제 1000번이 없습니다")


def sql_string(value):
    return "'" + str(value).replace("\\", "\\\\").replace("'", "''") + "'"


def mysql(sql):
    command = ["docker", "exec", "-e", "MYSQL_PWD=cobweb-test-only", CONTAINER,
               "mysql", "-N", "-B", "-uroot", DATABASE, "-e", sql]
    try:
        done = subprocess.run(command, capture_output=True, text=True, timeout=20, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise CheckError("전용 로컬 MySQL 호출 실패: " + type(exc).__name__) from None
    if done.returncode != 0:
        raise CheckError("전용 로컬 MySQL 명령 실패 (SQL/비밀값은 출력하지 않음)")
    return done.stdout.strip()


def scalar(sql):
    value = mysql(sql)
    require(value and "\n" not in value and "\t" not in value, "DB 단일 값 조회 실패")
    return value


def http(method, path, body=None, token=None, expected=200):
    require(path.startswith("/") and not path.startswith("//") and
            not parse.urlsplit(path).scheme and not parse.urlsplit(path).netloc,
            "상대 API 경로만 허용합니다")
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = request.Request(APP + path, data=data, headers=headers, method=method)
    try:
        with OPENER.open(req, timeout=12) as response:
            status, raw = response.status, response.read(2 * 1024 * 1024)
    except error.HTTPError as exc:
        status, raw = exc.code, b""
        exc.close()
    except (error.URLError, TimeoutError) as exc:
        raise CheckError("로컬 HTTP 호출 실패: " + method + " " + path + " (" +
                         type(exc).__name__ + ")") from None
    require(status == expected, method + " " + path + ": HTTP " + str(status) +
            ", 예상 " + str(expected))
    if expected >= 400:
        return None
    try:
        payload = json.loads(raw)
    except (UnicodeError, ValueError):
        raise CheckError("API JSON 형식 오류: " + method + " " + path) from None
    require(isinstance(payload, dict) and payload.get("success") is True and "data" in payload,
            "API 성공 응답 형식 오류: " + method + " " + path)
    return payload["data"]


def account(prefix, role, solved):
    name = prefix + uuid.uuid4().hex[:12]
    require(len(name) == 16, "검증 계정 이름 길이 오류")
    # 기존 검증 계정의 같은 임시 비밀번호 해시를 서버 안에서 복사합니다. 해시는 Python/로그에 오지 않습니다.
    mysql("INSERT INTO users(username,password,role,solved_count) "
          "SELECT " + sql_string(name) + ",password," + sql_string(role) + "," + str(solved) +
          " FROM cobweb_revalidation_test.users "
          "WHERE username LIKE 'f%' AND CHAR_LENGTH(username)=16 LIMIT 1")
    uid = int(scalar("SELECT id FROM users WHERE username=" + sql_string(name)))
    response = http("POST", "/api/auth/login", {"username": name, "password": PASSWORD})
    token = response.get("accessToken") if isinstance(response, dict) else None
    require(isinstance(token, str) and token, "검증 계정 로그인 응답 오류")
    USER_NAMES[prefix] = name
    return {"id": uid, "name": name, "token": token}


def problem_payload(title, mode="STDIN", group_id=None):
    if mode == "SQL":
        source = "SELECT SUM(v) FROM numbers"
        test_input = "CREATE TABLE numbers(v INTEGER); INSERT INTO numbers VALUES(2),(3);"
        languages, file_name = ["SQL"], "solution.sql"
    elif mode == "CPP":
        source = ("#include <iostream>\nint main(){int a,b;if(!(std::cin>>a>>b))return 0;"
                  "std::cout<<a+b<<'\\n';}\n")
        test_input = "2 3\n"
        languages, file_name = ["PYTHON", "CPP"], "solution.cpp"
    else:
        source = "print(sum(map(int,input().split())))\n"
        test_input = "2 3\n"
        languages, file_name = ["PYTHON"], "solution.py"
    return {
        "title": title, "description": "로컬 연동 검증 문제", "inputDescription": "두 정수",
        "outputDescription": "합", "constraints": "검증용", "rankType": "ELITE", "rankInt": 5,
        "timeLimitMs": 3000, "memoryLimitMb": 256,
        "executionMode": "SQL" if mode == "SQL" else "STDIN",
        "examples": [{"input": "2 3", "output": "5", "explanation": "검증"}],
        "tests": [{"input": test_input, "expected": "5"}],
        "languages": languages, "referenceSolutions": [
            {"language": "CPP" if mode == "CPP" else languages[0],
             "fileName": file_name, "sourceCode": source}],
        "groupId": group_id, "visibility": "GROUP" if group_id else "PUBLIC",
    }, source


def poll_accepted(submission_id, token, label):
    phase(label + " 자동 채점 대기 (최대 90초)")
    deadline = time.monotonic() + 90
    while True:
        item = http("GET", "/api/submissions/" + str(submission_id), token=token)
        status = item.get("status") if isinstance(item, dict) else None
        if status == "ACCEPTED":
            require(item.get("cases") and all(c.get("status") == "ACCEPTED" for c in item["cases"]),
                    label + " 비공개 테스트 판정 불일치")
            CHECKS.append(label + "_accepted")
            return item
        require(status in {"QUEUED", "RUNNING"}, label + " 최종 판정: " + str(status))
        require(time.monotonic() < deadline, label + " 자동 채점 90초 초과")
        time.sleep(1)


def sql_count(table, column, value):
    require(table in {"community_posts", "community_comments", "community_post_revisions",
                      "community_comment_revisions", "submissions"} and
            column in {"id", "post_id", "comment_id", "contest_id"}, "DB 집계 대상 오류")
    return int(scalar("SELECT COUNT(*) FROM " + table + " WHERE " + column + "=" + str(int(value))))


def main():
    check_targets()
    phase("루프백 앱 확인 및 4개 검증 계정 생성")
    http("GET", "/api/languages")
    admin = account("lka0", "OPERATOR", 0)
    owner = account("lko0", "USER", 50)
    member = account("lkm0", "USER", 0)
    other = account("lkm1", "USER", 0)
    users = {"operator": admin, "owner": owner, "member": member, "nonParticipant": other}
    CHECKS.append("four_http_logins")

    phase("공개 STDIN 문제·중복·Python 실제 채점")
    title = "연동 일반 " + uuid.uuid4().hex[:10]
    draft, python_code = problem_payload(title)
    number = http("POST", "/api/problems", draft, owner["token"])
    IDS["publicProblem"] = number
    http("POST", "/api/problems", draft, member["token"], expected=409)
    http("GET", "/api/problems/" + str(number), expected=404)
    http("PATCH", "/api/admin/problems/" + str(number) + "/visibility",
         {"visibility": "PUBLIC", "cause": "로컬 검증"}, admin["token"])
    public = http("GET", "/api/problems/" + str(number))
    require(public.get("problem_number") == number and
            not any(key in public for key in ("tests", "referenceSolutions", "sourceCode")),
            "공개 문제 본문 또는 비공개 자료 경계 오류")
    sid = http("POST", "/api/submissions",
               {"problemNumber": number, "sourceCode": python_code, "language": "PYTHON"}, member["token"])
    IDS["pythonSubmission"] = sid
    poll_accepted(sid, member["token"], "python_stdin")
    CHECKS.append("public_problem_duplicate_and_private_fields")

    phase("SQL 모드 문제·실제 채점")
    sql_draft, sql_code = problem_payload("연동 SQL " + uuid.uuid4().hex[:10], "SQL")
    sql_number = http("POST", "/api/problems", sql_draft, owner["token"])
    IDS["sqlProblem"] = sql_number
    http("PATCH", "/api/admin/problems/" + str(sql_number) + "/visibility",
         {"visibility": "PUBLIC", "cause": "로컬 검증"}, admin["token"])
    sql_sid = http("POST", "/api/submissions",
                   {"problemNumber": sql_number, "sourceCode": sql_code, "language": "SQL"}, member["token"])
    IDS["sqlSubmission"] = sql_sid
    poll_accepted(sql_sid, member["token"], "sql_mode")

    phase("시드 FUNCTION 문제 확인")
    seeded = http("GET", "/api/problems/1000")
    require(seeded.get("execution_mode") == "FUNCTION", "시드 FUNCTION 문제 HTTP 상태 오류")
    function_code = """from collections import deque
def sol(matrix, target):
    queue = deque([(7, 7)])
    emitted = {(7, 7)}
    while queue:
        r, c = queue.popleft()
        if (r, c) == target:
            return True
        for dr, dc in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nr, nc = r + dr, c + dc
            while 0 <= nr < 15 and 0 <= nc < 15:
                if (nr, nc) == target:
                    return True
                if matrix[nr][nc] == 1 and (nr, nc) not in emitted:
                    emitted.add((nr, nc))
                    queue.append((nr, nc))
                nr, nc = nr + dr, nc + dc
    return False
"""
    function_sid = http("POST", "/api/submissions",
                        {"problemNumber": 1000, "sourceCode": function_code,
                         "language": "PYTHON"}, member["token"])
    IDS["functionSubmission"] = function_sid
    poll_accepted(function_sid, member["token"], "function_seed_1000")

    phase("커뮤니티 연결·댓글·이력·soft delete/restore")
    post_id = http("POST", "/api/community/posts",
                   {"title": "연결 질문", "body": "연결 원문", "category": "PROBLEM_QNA",
                    "problemNumber": number}, member["token"])
    IDS["post"] = post_id
    post_path = "/api/community/posts/" + str(post_id)
    require(http("GET", post_path).get("problemNumber") == number, "게시글 문제 연결 오류")
    http("PUT", post_path,
         {"title": "권한 침범", "body": "침범", "category": "PROBLEM_QNA",
          "problemNumber": number, "version": 1}, other["token"], expected=403)
    comment_id = http("POST", post_path + "/comments",
                      {"parentId": None, "body": "댓글 원문"}, other["token"])
    IDS["comment"] = comment_id
    comment_path = "/api/community/comments/" + str(comment_id)
    http("PUT", comment_path, {"body": "댓글 수정", "version": 1}, other["token"])
    http("DELETE", comment_path + "?version=2", token=other["token"])
    require(sql_count("community_comments", "id", comment_id) == 1, "댓글 물리 보존 오류")
    http("POST", "/api/admin/community/comments/" + str(comment_id) + "/restore",
         {"version": 3, "reason": "로컬 검증"}, admin["token"])
    comment_history = http("GET", "/api/admin/community/comments/" + str(comment_id) + "/history",
                           token=admin["token"])
    require(len(comment_history) == 4 and any(item.get("oldBody") == "댓글 원문"
                                              for item in comment_history), "댓글 원문 이력 보존 오류")
    http("PUT", post_path,
         {"title": "연결 질문 수정", "body": "연결 편집", "category": "PROBLEM_QNA",
          "problemNumber": number, "version": 1}, member["token"])
    http("DELETE", post_path + "?version=2", token=member["token"])
    http("GET", post_path, expected=404)
    require(sql_count("community_posts", "id", post_id) == 1, "게시글 물리 보존 오류")
    http("GET", "/api/admin/community/posts/" + str(post_id) + "/history",
         token=member["token"], expected=403)
    http("POST", "/api/admin/community/posts/" + str(post_id) + "/restore",
         {"version": 3, "reason": "로컬 검증"}, admin["token"])
    post_history = http("GET", "/api/admin/community/posts/" + str(post_id) + "/history",
                        token=admin["token"])
    require(len(post_history) == 4 and any(item.get("oldBody") == "연결 원문"
                                           for item in post_history), "게시글 원문 이력 보존 오류")
    require(sql_count("community_post_revisions", "post_id", post_id) == 4 and
            sql_count("community_comment_revisions", "comment_id", comment_id) == 4,
            "커뮤니티 이력 DB 보존 오류")
    CHECKS.append("community_history_permissions_and_soft_restore")

    phase("문제 비공개/재공개와 커뮤니티 표시 경계")
    http("PATCH", "/api/admin/problems/" + str(number) + "/visibility",
         {"visibility": "PRIVATE", "cause": "로컬 검증"}, admin["token"])
    http("GET", "/api/problems/" + str(number), expected=404)
    require(http("GET", post_path).get("problemNumber") is None, "숨긴 문제 번호 노출")
    http("POST", "/api/community/posts",
         {"title": "숨긴 문제 연결", "body": "거절", "category": "PROBLEM_QNA",
          "problemNumber": number}, member["token"], expected=400)
    # 비공개 번호를 모르는 작성자의 제목 수정은 기존 연결을 유지해야 합니다.
    hidden = http("GET", post_path, token=member["token"])
    require(hidden.get("problemLinkHidden") is True and hidden.get("problemNumber") is None,
            "작성자 비공개 연결 보존 의도 표시 오류")
    require(http("GET", post_path).get("problemLinkHidden") is False,
            "익명 사용자에게 비공개 연결 존재를 표시")
    http("PUT", post_path,
         {"title": "숨김 중 제목 수정", "body": hidden["body"], "category": hidden["category"],
          "problemNumber": None, "version": hidden["version"], "preserveProblemLink": True}, member["token"])
    require(scalar("SELECT COUNT(*) FROM community_posts cp JOIN problems p ON p.id=cp.problem_id "
                   "WHERE cp.id=" + str(int(post_id)) + " AND p.problem_number=" + str(int(number))) == "1",
            "제목 편집 중 비공개 문제 연결 해제")
    CHECKS.append("hidden_link_text_edit_preserved")
    http("PATCH", "/api/admin/problems/" + str(number) + "/visibility",
         {"visibility": "PUBLIC", "cause": "로컬 검증"}, admin["token"])
    require(http("GET", post_path).get("problemNumber") == number, "재공개 후 기존 연결 오류")
    CHECKS.append("problem_visibility_community_link")

    phase("그룹 문제 제작·검수·시험 시작 전 접근 경계")
    group_id = http("POST", "/api/groups",
                    {"name": "연동" + uuid.uuid4().hex[:10], "description": "전용 검증"}, owner["token"])
    IDS["group"] = group_id
    for candidate in (member, other):
        http("POST", "/api/groups/" + str(group_id) + "/join", token=candidate["token"])
    group_draft, cpp_code = problem_payload("연동 그룹 " + uuid.uuid4().hex[:10], "CPP", group_id)
    group_number = http("POST", "/api/problems", group_draft, owner["token"])
    IDS["groupProblem"] = group_number
    http("POST", "/api/problems/" + str(group_number) + "/review-request", token=owner["token"])
    http("POST", "/api/problems/" + str(group_number) + "/review",
         {"status": "PUBLISHED", "cause": "로컬 검증"}, owner["token"])
    group_search = ("/api/groups/" + str(group_id) + "/problems?search=" +
                    parse.quote(group_draft["title"]))
    listed = http("GET", group_search, token=member["token"])
    require(any(item.get("problemNumber") == group_number for item in listed), "그룹 범위 검색 오류")
    global_list = http("GET", "/api/problems?search=" + parse.quote(group_draft["title"]))
    require(not any(item.get("problem_number") == group_number for item in global_list),
            "그룹 문제가 공개 목록에 노출됨")
    now = datetime.now(timezone.utc)
    contest_write = {"title": "연동 코테 " + uuid.uuid4().hex[:10],
                     "startsAt": (now + timedelta(hours=1)).isoformat(),
                     "endsAt": (now + timedelta(hours=2)).isoformat(),
                     "problemNumbers": [group_number], "replayAllowed": False,
                     "captureProtection": False, "windowSwitchProtection": False}
    contest_id = http("POST", "/api/groups/" + str(group_id) + "/contests",
                      contest_write, owner["token"])
    IDS["contest"] = contest_id
    base = "/api/groups/" + str(group_id) + "/contests/" + str(contest_id)
    require(http("GET", base, token=member["token"]).get("problems") == [],
            "시작 전 시험 상세 문제 노출")
    http("GET", base + "/problems", token=member["token"], expected=403)
    http("GET", base + "/problems/" + str(group_number), token=member["token"], expected=403)
    http("POST", base + "/submissions",
         {"problemNumber": group_number, "sourceCode": cpp_code, "language": "CPP"},
         member["token"], expected=403)
    CHECKS.append("group_problem_scope_and_prestart_gate")

    phase("시험 활성화·C++ 실제 채점·결과와 일반 통계 분리")
    mysql("UPDATE group_contests SET starts_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 MINUTE),"
          "ends_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 10 MINUTE) WHERE id=" + str(int(contest_id)))
    require(http("GET", base, token=member["token"]).get("status") == "ACTIVE",
            "시험 활성 시각 오류")
    exam_questions = http("GET", base + "/problems", token=member["token"])
    require(len(exam_questions) == 1 and exam_questions[0].get("problemNumber") == group_number,
            "시험 문제 목록 오류")
    question = http("GET", base + "/problems/" + str(group_number), token=member["token"])
    serialized = json.dumps(question, ensure_ascii=False)
    require(not any(secret in serialized for secret in
                    ("expected_output", "expectedOutput", "referenceSolutions", "sourceCode", "source_code")),
            "시험 문제 비공개 테스트/정답 노출")
    http("POST", "/api/submissions",
         {"problemNumber": group_number, "sourceCode": cpp_code, "language": "CPP"},
         member["token"], expected=404)
    before_user = scalar("SELECT CONCAT(solved_count,',',submit_count) FROM users WHERE id=" +
                         str(member["id"]))
    before_problem = scalar("SELECT CONCAT(submission_count,',',accepted_count) FROM problems WHERE problem_number=" +
                            str(int(group_number)))
    exam_sid = http("POST", base + "/submissions",
                    {"problemNumber": group_number, "sourceCode": cpp_code, "language": "CPP"},
                    member["token"])
    IDS["contestSubmission"] = exam_sid
    poll_accepted(exam_sid, member["token"], "contest_cpp")
    mine = http("GET", base + "/me", token=member["token"])
    require(any(item.get("id") == exam_sid and item.get("sourceCode") == cpp_code and
                str(item.get("judgedAt", "")).endswith("Z") for item in mine.get("submissions", [])),
            "본인 시험 답안/UTC 판정 응답 오류")
    rankings = http("GET", base + "/results", token=owner["token"])
    require(any(item.get("userId") == member["id"] and item.get("solvedCount") == 1
                for item in rankings), "관리자 시험 순위 오류")
    http("GET", base + "/results", token=member["token"], expected=403)
    staff = http("GET", base + "/submissions?userId=" + str(member["id"]), token=owner["token"])
    require(any(item.get("id") == exam_sid and item.get("sourceCode") == cpp_code
                for item in staff.get("submissions", [])), "관리자 시험 답안 열람 오류")
    http("GET", base + "/submissions?userId=" + str(member["id"]),
         token=member["token"], expected=403)
    normal_mine = http("GET", "/api/submissions", token=member["token"])
    require(not any(item.get("id") == exam_sid for item in normal_mine), "시험 답안이 일반 제출 목록에 노출")
    require(scalar("SELECT CONCAT(solved_count,',',submit_count) FROM users WHERE id=" +
                   str(member["id"])) == before_user and
            scalar("SELECT CONCAT(submission_count,',',accepted_count) FROM problems WHERE problem_number=" +
                   str(int(group_number))) == before_problem,
            "시험 제출이 일반 풀이 통계를 변경")
    CHECKS.append("contest_results_permissions_and_statistics")

    phase("종료·다시보기 OFF/ON·미참여 회원 차단")
    mysql("UPDATE group_contests SET ends_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=" +
          str(int(contest_id)))
    require(http("GET", base, token=member["token"]).get("status") == "ENDED",
            "시험 종료 시각 오류")
    http("GET", base + "/me", token=member["token"], expected=403)
    http("GET", base + "/problems/" + str(group_number), token=member["token"], expected=403)
    http("GET", "/api/submissions/" + str(exam_sid), token=member["token"], expected=403)
    latest = http("GET", base, token=owner["token"])
    http("PUT", base, {"title": latest["title"], "startsAt": latest["startsAt"],
                       "endsAt": latest["endsAt"],
                       "problemNumbers": [item["problemNumber"] for item in latest["problems"]],
                       "replayAllowed": True, "captureProtection": latest["captureProtection"],
                       "windowSwitchProtection": latest["windowSwitchProtection"]}, owner["token"])
    require(any(item.get("id") == exam_sid for item in
                http("GET", base + "/me", token=member["token"])["submissions"]),
            "참여자 다시보기 답안 오류")
    http("GET", base + "/problems/" + str(group_number), token=member["token"])
    http("GET", "/api/submissions/" + str(exam_sid), token=member["token"])
    http("GET", base + "/me", token=other["token"], expected=403)
    http("GET", base + "/problems/" + str(group_number), token=other["token"], expected=403)
    http("GET", "/api/submissions/" + str(exam_sid), token=other["token"], expected=403)
    require(sql_count("submissions", "contest_id", contest_id) == 1,
            "시험 제출 행 보존 오류")
    CHECKS.append("contest_replay_participation_gate")

    phase("별도 자동 워커의 최종 lease·케이스 기록 확인")
    judged_ids = [IDS[key] for key in ("pythonSubmission", "sqlSubmission", "functionSubmission", "contestSubmission")]
    id_list = ",".join(str(int(value)) for value in judged_ids)
    require(scalar("SELECT COUNT(*) FROM submissions WHERE id IN (" + id_list + ") "
                   "AND status='ACCEPTED' AND judge_attempts=1 AND judge_token IS NULL "
                   "AND judge_heartbeat_at IS NULL") == "4", "자동 워커 최종 lease 기록 오류")
    require(scalar("SELECT COUNT(*) FROM submission_test_case_results WHERE submission_id=" +
                   str(int(IDS["functionSubmission"]))) == "10", "1000번 비공개 10케이스 기록 오류")
    CHECKS.append("automatic_worker_lease_and_private_cases")

    summary = {"status": "ok", "app": APP, "database": DATABASE,
               "users": {key: value["name"] for key, value in users.items()},
               "ids": IDS, "pathChecks": CHECKS}
    print(json.dumps(summary, ensure_ascii=False, sort_keys=True), flush=True)


if __name__ == "__main__":
    try:
        main()
    except CheckError as exc:
        print("[linkage] 실패: " + str(exc), file=sys.stderr, flush=True)
        print(json.dumps({"status": "failed", "app": APP, "database": DATABASE,
                          "users": USER_NAMES, "ids": IDS, "pathChecks": CHECKS,
                          "reason": str(exc)}, ensure_ascii=False, sort_keys=True), flush=True)
        sys.exit(1)
    except Exception as exc:
        # 토큰·비밀번호·해시·제출 코드가 예외 문자열에 포함될 가능성을 차단합니다.
        print("[linkage] 예상하지 못한 실패: " + type(exc).__name__, file=sys.stderr, flush=True)
        print(json.dumps({"status": "failed", "app": APP, "database": DATABASE,
                          "users": USER_NAMES, "ids": IDS, "pathChecks": CHECKS,
                          "reason": type(exc).__name__}, ensure_ascii=False, sort_keys=True), flush=True)
        sys.exit(1)
