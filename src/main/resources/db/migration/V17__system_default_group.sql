-- 이름 문자열이 아니라 DB 고유 키로 내장 그룹을 식별합니다. 사용자 생성 그룹은 항상 NULL입니다.
ALTER TABLE study_groups
    ADD COLUMN system_key VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT uk_study_groups_system_key UNIQUE (system_key),
    ADD CONSTRAINT chk_study_groups_system_key CHECK (system_key IS NULL OR system_key='COBWEB_DEFAULT');

-- 내장 그룹의 회원 번호는 users.id를 그대로 사용하므로 동시 가입 때 MAX+1 경쟁이 없습니다.
-- 기존 일반 그룹의 번호/순서는 바꾸지 않으며 이 내부 번호는 API 응답에 노출하지 않습니다.
ALTER TABLE group_members MODIFY member_number BIGINT NOT NULL DEFAULT 1;

-- 같은 이름의 일반 그룹이 이미 있으면 고유 이름 제약으로 실패합니다. 기존 그룹을 가로채거나 임의 개명하지 않습니다.
INSERT INTO study_groups(group_name,group_description,status,system_key)
VALUES('COBWEB GROUP','모든 회원이 기본으로 참여하는 COBWEB 공식 그룹입니다.','ACCEPTED','COBWEB_DEFAULT');
SET @cobweb_default_group_id = LAST_INSERT_ID();

-- 기본 다섯 역할은 유지하되 OWNER는 누구에게도 할당하지 않습니다.
-- 운영은 기존 플랫폼 OPERATOR/DEVELOPER 권한 경로를 사용하며 일반 회원에게 새 관리 권한을 주지 않습니다.
INSERT INTO group_roles(group_id,role_name,builtin_key,can_view_problems,can_create_problems,
    can_edit_problems,can_review_problems,can_manage_members,can_manage_roles,can_manage_group,
    can_create_contests,can_manage_contests,can_view_contest_results,can_take_contests)
VALUES(@cobweb_default_group_id,'소유자','OWNER',1,1,1,1,1,1,1,1,1,1,1);
INSERT INTO group_roles(group_id,role_name,builtin_key,can_view_problems,can_create_problems,
    can_edit_problems,can_review_problems,can_manage_members,can_manage_group,
    can_create_contests,can_manage_contests,can_view_contest_results,can_take_contests)
VALUES(@cobweb_default_group_id,'관리자','MANAGER',1,1,1,1,1,1,1,1,1,1);
INSERT INTO group_roles(group_id,role_name,builtin_key,can_view_problems,can_create_problems,
    can_edit_problems,can_take_contests)
VALUES(@cobweb_default_group_id,'출제자','AUTHOR',1,1,1,1);
INSERT INTO group_roles(group_id,role_name,builtin_key,can_view_problems,can_create_contests,
    can_manage_contests,can_view_contest_results,can_take_contests)
VALUES(@cobweb_default_group_id,'코테 관리자','CONTEST_MANAGER',1,1,1,1,1);
INSERT INTO group_roles(group_id,role_name,builtin_key,can_view_problems,can_take_contests)
VALUES(@cobweb_default_group_id,'회원','MEMBER',1,1);

DELIMITER //

-- 별도 가입 API 호출이 아니라 users INSERT와 함께 실행하여 로컬/OAuth/관리 도구의 모든 생성 경로를 포함합니다.
CREATE TRIGGER users_join_cobweb_default_group AFTER INSERT ON users FOR EACH ROW
BEGIN
    DECLARE default_gid BIGINT DEFAULT NULL;
    DECLARE default_role_id BIGINT DEFAULT NULL;
    SELECT MAX(g.id),MAX(r.id) INTO default_gid,default_role_id
    FROM study_groups g JOIN group_roles r ON r.group_id=g.id AND r.builtin_key='MEMBER'
    WHERE g.system_key='COBWEB_DEFAULT' AND g.status='ACCEPTED';
    IF default_gid IS NULL OR default_role_id IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='COBWEB default group is unavailable';
    END IF;
    INSERT INTO group_members(user_id,group_id,member_number,permission,role_id)
    VALUES(NEW.id,default_gid,NEW.id,'MEMBER',default_role_id);
END//

-- 일반 그룹을 내장 그룹으로 바꾸거나 내장 그룹의 키/이름/상태를 바꾸는 우회도 DB에서 거절합니다.
CREATE TRIGGER study_groups_preserve_system_identity BEFORE UPDATE ON study_groups FOR EACH ROW
BEGIN
    IF NOT (OLD.system_key <=> NEW.system_key)
       OR (OLD.system_key IS NOT NULL AND (
            NOT (CAST(OLD.group_name AS BINARY) <=> CAST(NEW.group_name AS BINARY))
            OR OLD.status<>NEW.status)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='System group identity cannot be changed';
    END IF;
END//

CREATE TRIGGER study_groups_keep_system_group BEFORE DELETE ON study_groups FOR EACH ROW
BEGIN
    IF OLD.system_key IS NOT NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='System group cannot be deleted';
    END IF;
END//

CREATE TRIGGER group_members_keep_system_membership BEFORE DELETE ON group_members FOR EACH ROW
BEGIN
    IF EXISTS(SELECT 1 FROM study_groups g WHERE g.id=OLD.group_id AND g.system_key='COBWEB_DEFAULT') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='System group membership cannot be removed';
    END IF;
END//

CREATE TRIGGER group_members_no_system_owner_insert BEFORE INSERT ON group_members FOR EACH ROW
BEGIN
    IF EXISTS(SELECT 1 FROM study_groups g WHERE g.id=NEW.group_id AND g.system_key='COBWEB_DEFAULT')
       AND (NEW.permission='OWNER' OR EXISTS(
            SELECT 1 FROM group_roles r WHERE r.group_id=NEW.group_id AND r.id=NEW.role_id AND r.builtin_key='OWNER')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='System group cannot have a user owner';
    END IF;
END//

CREATE TRIGGER group_members_preserve_system_identity BEFORE UPDATE ON group_members FOR EACH ROW
BEGIN
    IF EXISTS(SELECT 1 FROM study_groups g WHERE g.id IN (OLD.group_id,NEW.group_id) AND g.system_key='COBWEB_DEFAULT') THEN
        IF OLD.user_id<>NEW.user_id OR OLD.group_id<>NEW.group_id OR OLD.member_number<>NEW.member_number THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='System group membership identity cannot be changed';
        END IF;
        IF NEW.permission='OWNER' OR EXISTS(
            SELECT 1 FROM group_roles r WHERE r.group_id=NEW.group_id AND r.id=NEW.role_id AND r.builtin_key='OWNER') THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='System group cannot have a user owner';
        END IF;
    END IF;
END//

DELIMITER ;

-- 트리거를 먼저 만든 뒤 이전 계정을 채웁니다. backfill과 트리거 생성 사이에 신규 계정이 누락되는 틈을 만들지 않습니다.
INSERT INTO group_members(user_id,group_id,member_number,permission,role_id)
SELECT u.id,g.id,u.id,'MEMBER',r.id FROM users u
JOIN study_groups g ON g.system_key='COBWEB_DEFAULT'
JOIN group_roles r ON r.group_id=g.id AND r.builtin_key='MEMBER'
WHERE NOT EXISTS(SELECT 1 FROM group_members gm WHERE gm.user_id=u.id AND gm.group_id=g.id);
