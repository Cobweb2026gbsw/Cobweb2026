-- 그룹 역할은 기존 permission enum을 보존하면서 세부 기능별 권한으로 확장합니다.
CREATE TABLE group_roles (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    group_id BIGINT NOT NULL,
    role_name VARCHAR(32) NOT NULL,
    builtin_key VARCHAR(32) NULL,
    can_view_problems BOOLEAN NOT NULL DEFAULT FALSE,
    can_create_problems BOOLEAN NOT NULL DEFAULT FALSE,
    can_edit_problems BOOLEAN NOT NULL DEFAULT FALSE,
    can_review_problems BOOLEAN NOT NULL DEFAULT FALSE,
    can_manage_members BOOLEAN NOT NULL DEFAULT FALSE,
    can_manage_roles BOOLEAN NOT NULL DEFAULT FALSE,
    can_manage_group BOOLEAN NOT NULL DEFAULT FALSE,
    can_create_contests BOOLEAN NOT NULL DEFAULT FALSE,
    can_manage_contests BOOLEAN NOT NULL DEFAULT FALSE,
    can_view_contest_results BOOLEAN NOT NULL DEFAULT FALSE,
    can_take_contests BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_group_roles_group FOREIGN KEY (group_id) REFERENCES study_groups(id) ON DELETE CASCADE,
    CONSTRAINT uk_group_roles_name UNIQUE (group_id, role_name),
    CONSTRAINT uk_group_roles_builtin UNIQUE (group_id, builtin_key),
    CONSTRAINT uk_group_roles_group_id UNIQUE (group_id, id),
    CONSTRAINT chk_group_roles_owner_core CHECK (
        builtin_key <> 'OWNER' OR
        (can_manage_members AND can_manage_roles AND can_manage_group)
    )
);

-- 기존 OWNER/ADMIN/MEMBER 회원을 끊지 않도록 모든 그룹에 기본 다섯 역할을 만듭니다.
INSERT INTO group_roles (group_id,role_name,builtin_key,can_view_problems,can_create_problems,
    can_edit_problems,can_review_problems,can_manage_members,can_manage_roles,can_manage_group,
    can_create_contests,can_manage_contests,can_view_contest_results,can_take_contests)
SELECT id,'소유자','OWNER',TRUE,TRUE,TRUE,TRUE,TRUE,TRUE,TRUE,TRUE,TRUE,TRUE,TRUE FROM study_groups;

INSERT INTO group_roles (group_id,role_name,builtin_key,can_view_problems,can_create_problems,
    can_edit_problems,can_review_problems,can_manage_members,can_manage_group,
    can_create_contests,can_manage_contests,can_view_contest_results,can_take_contests)
SELECT id,'관리자','MANAGER',TRUE,TRUE,TRUE,TRUE,TRUE,TRUE,TRUE,TRUE,TRUE,TRUE FROM study_groups;

INSERT INTO group_roles (group_id,role_name,builtin_key,can_view_problems,can_create_problems,
    can_edit_problems,can_take_contests)
SELECT id,'출제자','AUTHOR',TRUE,TRUE,TRUE,TRUE FROM study_groups;

INSERT INTO group_roles (group_id,role_name,builtin_key,can_view_problems,can_create_contests,
    can_manage_contests,can_view_contest_results,can_take_contests)
SELECT id,'코테 관리자','CONTEST_MANAGER',TRUE,TRUE,TRUE,TRUE,TRUE FROM study_groups;

INSERT INTO group_roles (group_id,role_name,builtin_key,can_view_problems,can_take_contests)
SELECT id,'회원','MEMBER',TRUE,TRUE FROM study_groups;

ALTER TABLE group_members ADD COLUMN role_id BIGINT NULL;
UPDATE group_members gm JOIN group_roles gr ON gr.group_id=gm.group_id
    AND gr.builtin_key=CASE gm.permission WHEN 'OWNER' THEN 'OWNER'
        WHEN 'ADMIN' THEN 'MANAGER' ELSE 'MEMBER' END
SET gm.role_id=gr.id;
ALTER TABLE group_members MODIFY role_id BIGINT NOT NULL;
ALTER TABLE group_members
    ADD CONSTRAINT fk_group_members_role FOREIGN KEY (group_id,role_id)
        REFERENCES group_roles(group_id,id) ON DELETE RESTRICT,
    ADD INDEX idx_group_members_role (group_id,role_id);

CREATE TABLE group_contests (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    group_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    starts_at DATETIME(6) NOT NULL,
    ends_at DATETIME(6) NOT NULL,
    replay_allowed BOOLEAN NOT NULL DEFAULT FALSE,
    capture_protection BOOLEAN NOT NULL DEFAULT FALSE,
    window_switch_protection BOOLEAN NOT NULL DEFAULT FALSE,
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_group_contests_group FOREIGN KEY (group_id) REFERENCES study_groups(id) ON DELETE RESTRICT,
    CONSTRAINT fk_group_contests_creator FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT chk_group_contests_window CHECK (starts_at < ends_at),
    INDEX idx_group_contests_group_time (group_id,starts_at,id)
);

CREATE TABLE contest_problems (
    contest_id BIGINT NOT NULL,
    problem_id BIGINT NOT NULL,
    display_order INT NOT NULL,
    PRIMARY KEY (contest_id,problem_id),
    CONSTRAINT uk_contest_problems_order UNIQUE (contest_id,display_order),
    CONSTRAINT fk_contest_problems_contest FOREIGN KEY (contest_id) REFERENCES group_contests(id) ON DELETE CASCADE,
    CONSTRAINT fk_contest_problems_problem FOREIGN KEY (problem_id) REFERENCES problems(id) ON DELETE RESTRICT,
    CONSTRAINT chk_contest_problems_order CHECK (display_order > 0),
    INDEX idx_contest_problems_problem (problem_id,contest_id)
);

ALTER TABLE submissions
    ADD COLUMN contest_id BIGINT NULL,
    ADD INDEX idx_submissions_contest_problem (contest_id,problem_id),
    ADD INDEX idx_submissions_contest_user (contest_id,user_id,problem_id,submitted_at),
    ADD CONSTRAINT fk_submissions_contest FOREIGN KEY (contest_id) REFERENCES group_contests(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_submissions_contest_problem FOREIGN KEY (contest_id,problem_id)
        REFERENCES contest_problems(contest_id,problem_id) ON DELETE RESTRICT;

-- 클라이언트 감시 이벤트는 참고 신호일 뿐 확정적인 부정행위 판정이 아닙니다.
CREATE TABLE contest_events (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    contest_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    event_id CHAR(36) NOT NULL,
    event_type ENUM('WINDOW_BLUR','VISIBILITY_HIDDEN','FULLSCREEN_EXIT',
        'COPY_BLOCKED','PRINT_BLOCKED','CAPTURE_KEY') NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_contest_events_contest FOREIGN KEY (contest_id) REFERENCES group_contests(id) ON DELETE CASCADE,
    CONSTRAINT fk_contest_events_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uk_contest_events_idempotency UNIQUE (contest_id,user_id,event_id),
    INDEX idx_contest_events_rate (contest_id,user_id,occurred_at)
);
