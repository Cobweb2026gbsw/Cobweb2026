CREATE DATABASE IF NOT EXISTS cobweb2026
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE cobweb2026;

-- 회원 계정입니다.
-- 일반 가입 계정은 provider/provider_id가 모두 NULL이고, 소셜 계정은 두 값이 함께 존재해야 합니다.
CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,

    username VARCHAR(16) NOT NULL,
    email VARCHAR(255) NULL,
    provider ENUM('GOOGLE', 'GITHUB', 'NAVER') NULL,
    provider_id VARCHAR(255) NULL,
    password VARCHAR(255) NOT NULL,
    bio VARCHAR(300) NOT NULL DEFAULT '',
    github_url VARCHAR(255) NOT NULL DEFAULT '',

    role ENUM('USER', 'OPERATOR', 'DEVELOPER') NOT NULL DEFAULT 'USER',
    status ENUM('ACTIVE', 'TIMEOUT', 'STOPPED', 'BANNED') NOT NULL DEFAULT 'ACTIVE',

    solved_count INT NOT NULL DEFAULT 0,
    submit_count INT NOT NULL DEFAULT 0,
    rank_type ENUM('INITIATE', 'SKILLED', 'ELITE', 'EXPERT', 'LEGEND', 'MYTHIC', 'ABSOLUTE')
        NOT NULL DEFAULT 'INITIATE',
    rank_int TINYINT NOT NULL DEFAULT 5,
    login_failed_count INT NOT NULL DEFAULT 0,

    last_login_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_password_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_users_username UNIQUE (username),
    -- 현재 서비스가 이메일 중복 가입을 차단하므로 DB도 같은 규칙을 강제합니다.
    -- MySQL의 UNIQUE는 NULL 여러 개를 허용하므로 이메일 없는 계정은 계속 허용됩니다.
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT uk_users_provider_identity UNIQUE (provider, provider_id),
    CONSTRAINT chk_users_provider_identity CHECK (
        (provider IS NULL AND provider_id IS NULL)
        OR (provider IS NOT NULL AND provider_id IS NOT NULL)
    ),
    CONSTRAINT chk_users_counts CHECK (solved_count >= 0 AND submit_count >= 0),
    CONSTRAINT chk_users_rank_int CHECK (rank_int BETWEEN 1 AND 5),
    CONSTRAINT chk_users_login_failed_count CHECK (login_failed_count >= 0),

    INDEX idx_users_ranking (solved_count DESC, submit_count ASC),
    INDEX idx_users_created_at (created_at)
);

-- 로그인 시도 감사 로그입니다. user_id는 존재하지 않는 아이디로 로그인한 경우 NULL이 될 수 있습니다.
CREATE TABLE IF NOT EXISTS users_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NULL,
    attempt_email VARCHAR(255) NULL,
    attempt_name VARCHAR(16) NULL,
    status ENUM('SUCCESS', 'FAIL_PASSWORD_MISMATCH', 'FAIL_USER_NOT_FOUND', 'BANNED_USER') NOT NULL,
    log_created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    client_ip VARCHAR(45) NOT NULL,
    user_agent VARCHAR(255) NULL,

    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL,
    INDEX idx_users_log_created_at (log_created_at),
    INDEX idx_users_log_client_ip_created_at (client_ip, log_created_at),
    INDEX idx_users_log_user_created_at (user_id, log_created_at)
);

-- 사용자당 하나만 유지하는 refresh token입니다.
-- 현재 애플리케이션은 token 원문으로 조회하므로 코드 변경 전까지는 해시 저장으로 바꾸지 않습니다.
CREATE TABLE IF NOT EXISTS refresh_token (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    token VARCHAR(512) NOT NULL,
    expires_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_refresh_token_user UNIQUE (user_id),
    CONSTRAINT uk_refresh_token_token UNIQUE (token),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_refresh_token_expires_at (expires_at)
);

-- 회원가입 및 비밀번호 재설정에 사용하는 이메일 인증 코드입니다.
-- 애플리케이션은 email + purpose별 최신 created_at 행을 조회하므로 그 순서에 맞춘 인덱스를 둡니다.
CREATE TABLE IF NOT EXISTS email_verifications (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    email VARCHAR(255) NOT NULL,
    code CHAR(8) NOT NULL,
    purpose ENUM('JOIN', 'PASSWORD_RESET') NOT NULL DEFAULT 'JOIN',
    is_verified BOOLEAN NOT NULL DEFAULT FALSE,
    expires_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_email_verifications_lookup (email, purpose, created_at DESC),
    INDEX idx_email_verifications_expires_at (expires_at)
);

-- 이메일 인증이 끝난 뒤 발급하는 1회용 비밀번호 재설정 토큰입니다.
CREATE TABLE IF NOT EXISTS password_reset (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    reset_token VARCHAR(255) NOT NULL,
    is_used BOOLEAN NOT NULL DEFAULT FALSE,
    expires_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_password_reset_token UNIQUE (reset_token),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_password_reset_user_id (user_id),
    INDEX idx_password_reset_expires_at (expires_at)
);

-- 스터디나 비공개 문제를 위한 그룹입니다.
-- GROUPS는 SQL 문법과 혼동되므로 groups 대신 study_groups라는 이름을 사용합니다.
CREATE TABLE IF NOT EXISTS study_groups (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    group_name VARCHAR(16) NOT NULL,
    group_description VARCHAR(1000) NOT NULL DEFAULT '',
    status ENUM('WAITING', 'ACCEPTED', 'REJECTED') NOT NULL DEFAULT 'WAITING',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_study_groups_name UNIQUE (group_name),
    INDEX idx_study_groups_status_created_at (status, created_at)
);

-- 그룹과 회원의 다대다 관계 및 그룹 안에서의 역할입니다.
CREATE TABLE IF NOT EXISTS group_members (
    user_id BIGINT NOT NULL,
    group_id BIGINT NOT NULL,
    member_number INT NOT NULL DEFAULT 1,
    permission ENUM('MEMBER', 'ADMIN', 'OWNER') NOT NULL DEFAULT 'MEMBER',
    joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (user_id, group_id),
    CONSTRAINT uk_group_members_number UNIQUE (group_id, member_number),
    CONSTRAINT chk_group_members_number CHECK (member_number > 0),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (group_id) REFERENCES study_groups(id) ON DELETE CASCADE,
    INDEX idx_group_members_group_permission (group_id, permission)
);

-- 알고리즘 문제의 현재 공개/검수 상태와 문제 본문입니다.
-- submission_count, accepted_count, like_count는 목록 조회 성능을 위한 집계 값이며,
-- 실제 원본 데이터는 submissions 및 problem_likes 테이블입니다.
CREATE TABLE IF NOT EXISTS problems (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    problem_number INT NOT NULL,
    problem_title VARCHAR(200) NOT NULL,

    rank_type ENUM('INITIATE', 'SKILLED', 'ELITE', 'EXPERT', 'LEGEND', 'MYTHIC', 'ABSOLUTE')
        NOT NULL DEFAULT 'INITIATE',
    rank_int TINYINT NOT NULL DEFAULT 1,

    description LONGTEXT NOT NULL,
    input_description TEXT NOT NULL,
    output_description TEXT NOT NULL,
    constraints_text TEXT NULL,

    time_limit_ms INT NOT NULL DEFAULT 1000,
    memory_limit_mb INT NOT NULL DEFAULT 256,
    judge_type ENUM('STANDARD', 'SPECIAL','EFFICIENCY') NOT NULL DEFAULT 'STANDARD',

    -- status는 운영/검수 흐름, visibility는 누구에게 보이는지에 대한 별도 개념입니다.
    status ENUM('DRAFT', 'PENDING_REVIEW', 'PUBLISHED', 'REJECTED', 'ARCHIVED')
        NOT NULL DEFAULT 'DRAFT',
    visibility ENUM('PUBLIC', 'GROUP', 'PRIVATE') NOT NULL DEFAULT 'PRIVATE',

    submission_count BIGINT NOT NULL DEFAULT 0,
    accepted_count BIGINT NOT NULL DEFAULT 0,
    like_count BIGINT NOT NULL DEFAULT 0,

    author_id BIGINT NOT NULL,
    group_id BIGINT NULL,
    source_name VARCHAR(255) NULL,
    source_url VARCHAR(1000) NULL,

    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    published_at DATETIME NULL,

    CONSTRAINT uk_problems_number UNIQUE (problem_number),
    CONSTRAINT chk_problems_rank_int CHECK (rank_int BETWEEN 1 AND 5),
    CONSTRAINT chk_problems_limits CHECK (time_limit_ms > 0 AND memory_limit_mb > 0),
    CONSTRAINT chk_problems_counts CHECK (
        submission_count >= 0 AND accepted_count >= 0 AND like_count >= 0 AND accepted_count <= submission_count
    ),
    CONSTRAINT chk_problems_group_visibility CHECK (
        (visibility = 'GROUP' AND group_id IS NOT NULL)
        OR (visibility IN ('PUBLIC', 'PRIVATE'))
    ),
    FOREIGN KEY (author_id) REFERENCES users(id) ON DELETE RESTRICT,
    -- GROUP 공개 문제는 group_id 없이 존재할 수 없으므로, 연결된 문제가 있는 그룹은 먼저 문제를
    -- 재배정하거나 비공개 처리한 뒤에만 삭제할 수 있게 RESTRICT를 사용합니다.
    FOREIGN KEY (group_id) REFERENCES study_groups(id) ON DELETE RESTRICT,

    INDEX idx_problems_listing (status, visibility, problem_number),
    INDEX idx_problems_rank (rank_type, rank_int),
    INDEX idx_problems_group_listing (group_id, status, problem_number),
    INDEX idx_problems_author_created_at (author_id, created_at)
);

-- 문제 상태가 바뀐 이력입니다. problems.status가 현재 상태의 기준이고, 이 테이블은 감사 기록입니다.
CREATE TABLE IF NOT EXISTS problem_status_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    problem_id BIGINT NOT NULL,
    status ENUM('DRAFT', 'PENDING_REVIEW', 'PUBLISHED', 'REJECTED', 'ARCHIVED') NOT NULL,
    cause TEXT NULL,
    checker_id BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (problem_id) REFERENCES problems(id) ON DELETE CASCADE,
    FOREIGN KEY (checker_id) REFERENCES users(id) ON DELETE SET NULL,
    INDEX idx_problem_status_history_problem_created_at (problem_id, created_at)
);

-- 문제 화면에 공개되는 예제 입출력입니다. 실제 채점용 데이터는 아래 problem_test_cases에 따로 둡니다.
CREATE TABLE IF NOT EXISTS problem_examples (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    problem_id BIGINT NOT NULL,
    input_text LONGTEXT NOT NULL,
    output_text LONGTEXT NOT NULL,
    explanation TEXT NULL,
    display_order INT NOT NULL DEFAULT 1,

    CONSTRAINT uk_problem_examples_order UNIQUE (problem_id, display_order),
    CONSTRAINT chk_problem_examples_order CHECK (display_order > 0),
    FOREIGN KEY (problem_id) REFERENCES problems(id) ON DELETE CASCADE
);

-- 외부 API로 절대 반환하지 않는 실제 채점용 입력과 정답입니다.
-- 데이터가 매우 커지면 input_data/expected_output 대신 파일 저장소 경로와 해시를 저장하도록 확장할 수 있습니다.
CREATE TABLE IF NOT EXISTS problem_test_cases (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    problem_id BIGINT NOT NULL,
    input_data LONGTEXT NOT NULL,
    expected_output LONGTEXT NOT NULL,
    compare_type ENUM('EXACT', 'TRIM_TRAILING_WHITESPACE', 'FLOAT_TOLERANCE', 'SPECIAL_CHECKER')
        NOT NULL DEFAULT 'TRIM_TRAILING_WHITESPACE',
    score INT NOT NULL DEFAULT 0,
    display_order INT NOT NULL DEFAULT 1,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_problem_test_cases_order UNIQUE (problem_id, display_order),
    CONSTRAINT chk_problem_test_cases_score CHECK (score >= 0),
    CONSTRAINT chk_problem_test_cases_order CHECK (display_order > 0),
    FOREIGN KEY (problem_id) REFERENCES problems(id) ON DELETE CASCADE,
    INDEX idx_problem_test_cases_judging (problem_id, is_active, display_order)
);

-- 알고리즘 분류 태그와 문제-태그 다대다 관계입니다.
CREATE TABLE IF NOT EXISTS tags (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(50) NOT NULL,
    CONSTRAINT uk_tags_name UNIQUE (name)
);

CREATE TABLE IF NOT EXISTS problem_tags (
    problem_id BIGINT NOT NULL,
    tag_id BIGINT NOT NULL,

    PRIMARY KEY (problem_id, tag_id),
    FOREIGN KEY (problem_id) REFERENCES problems(id) ON DELETE CASCADE,
    FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE CASCADE,
    INDEX idx_problem_tags_tag_problem (tag_id, problem_id)
);

-- 문제별로 허용하는 언어입니다. 언어 이름은 애플리케이션의 채점기 설정과 같은 값으로 관리합니다.
CREATE TABLE IF NOT EXISTS problem_languages (
    problem_id BIGINT NOT NULL,
    language VARCHAR(30) NOT NULL,

    PRIMARY KEY (problem_id, language),
    FOREIGN KEY (problem_id) REFERENCES problems(id) ON DELETE CASCADE
);

-- 문제에 대한 사용자 좋아요입니다. 복합 PK로 한 사용자의 중복 좋아요를 막습니다.
CREATE TABLE IF NOT EXISTS problem_likes (
    user_id BIGINT NOT NULL,
    problem_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (user_id, problem_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (problem_id) REFERENCES problems(id) ON DELETE CASCADE,
    INDEX idx_problem_likes_problem_created_at (problem_id, created_at)
);

-- 한 번의 코드 제출입니다. source_code는 개인정보 및 정답 유출 방지를 위해 제출자와 운영자만 조회할 수 있어야 합니다.
CREATE TABLE IF NOT EXISTS submissions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    problem_id BIGINT NOT NULL,
    language VARCHAR(30) NOT NULL,
    source_code LONGTEXT NOT NULL,
    status ENUM(
        'QUEUED',
        'RUNNING',
        'ACCEPTED',
        'WRONG_ANSWER',
        'TIME_LIMIT_EXCEEDED',
        'MEMORY_LIMIT_EXCEEDED',
        'RUNTIME_ERROR',
        'COMPILATION_ERROR',
        'SYSTEM_ERROR'
    ) NOT NULL DEFAULT 'QUEUED',
    score INT NOT NULL DEFAULT 0,
    execution_time_ms INT NULL,
    memory_usage_kb INT NULL,
    submitted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    judged_at DATETIME NULL,

    CONSTRAINT chk_submissions_score CHECK (score >= 0),
    CONSTRAINT chk_submissions_execution_time CHECK (execution_time_ms IS NULL OR execution_time_ms >= 0),
    CONSTRAINT chk_submissions_memory_usage CHECK (memory_usage_kb IS NULL OR memory_usage_kb >= 0),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (problem_id) REFERENCES problems(id) ON DELETE CASCADE,
    INDEX idx_submissions_user_submitted_at (user_id, submitted_at DESC),
    INDEX idx_submissions_problem_submitted_at (problem_id, submitted_at DESC),
    INDEX idx_submissions_problem_user_status (problem_id, user_id, status)
);

-- 채점 중 테스트 케이스별 결과가 필요할 때 저장합니다.
CREATE TABLE IF NOT EXISTS submission_test_case_results (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    submission_id BIGINT NOT NULL,
    test_case_id BIGINT NULL,
    status ENUM(
        'ACCEPTED',
        'WRONG_ANSWER',
        'TIME_LIMIT_EXCEEDED',
        'MEMORY_LIMIT_EXCEEDED',
        'RUNTIME_ERROR',
        'SYSTEM_ERROR'
    ) NOT NULL,
    score INT NOT NULL DEFAULT 0,
    execution_time_ms INT NULL,
    memory_usage_kb INT NULL,

    CONSTRAINT chk_submission_test_case_results_score CHECK (score >= 0),
    FOREIGN KEY (submission_id) REFERENCES submissions(id) ON DELETE CASCADE,
    FOREIGN KEY (test_case_id) REFERENCES problem_test_cases(id) ON DELETE SET NULL,
    INDEX idx_submission_test_case_results_submission (submission_id)
);

-- 사용자가 정답 처리한 문제를 한 번만 기록합니다. solved_count를 재계산하거나 프로필에 표시할 때 사용합니다.
CREATE TABLE IF NOT EXISTS user_solved_problems (
    user_id BIGINT NOT NULL,
    problem_id BIGINT NOT NULL,
    solved_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (user_id, problem_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (problem_id) REFERENCES problems(id) ON DELETE CASCADE,
    INDEX idx_user_solved_problems_problem_solved_at (problem_id, solved_at)
);
