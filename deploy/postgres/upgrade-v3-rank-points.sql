-- 기존 CobWeb V2 DB 전용입니다. Neon SQL Editor에서 DB 소유자 역할로 전체를 한 번 실행하세요.
-- 웹/워커를 새 버전으로 배포하기 전에 적용합니다. 기존 풀이의 배점은 현재 문제 난이도로 계산합니다.
BEGIN;
SET LOCAL search_path TO public, pg_temp;
LOCK TABLE public.flyway_schema_history IN EXCLUSIVE MODE;

DO $verify_history$
BEGIN
    IF EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '3' AND success) THEN
        RAISE EXCEPTION 'Rank points migration V3 is already installed.';
    END IF;
    IF (SELECT COUNT(*) FROM flyway_schema_history) <> 2
       OR NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '1' AND success AND checksum = -1190079743)
       OR NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '2' AND success AND checksum = -1413680715) THEN
        RAISE EXCEPTION 'Expected an existing CobWeb database with successful Flyway V1 and V2 migrations.';
    END IF;
END
$verify_history$;

-- BEGIN V3__rank_points.sql
-- 첫 일반 정답의 배점을 해결 기록에 보존하고, 누적 점수로 사용자 랭크를 결정합니다.
-- 이전 워커도 같은 원장을 쓰므로 배포 중 두 버전이 함께 실행돼도 한 번만 적립합니다.
LOCK TABLE users, problems, user_solved_problems IN ACCESS EXCLUSIVE MODE;

CREATE FUNCTION cobweb_rank_tiers() RETURNS text[]
LANGUAGE sql IMMUTABLE PARALLEL SAFE
RETURN ARRAY['INITIATE','SKILLED','ELITE','EXPERT','LEGEND','MYTHIC','ABSOLUTE']::text[];

-- 0은 INITIATE 5, 34는 ABSOLUTE 1입니다.
CREATE FUNCTION cobweb_rank_step(tier text, grade integer) RETURNS integer
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
RETURN 5 * array_position(cobweb_rank_tiers(), tier) - grade;

-- Lv.4~6 과정에서 채점·코드 테스트에 같은 언어별 시간 보정을 적용합니다.
ALTER TABLE problems ADD COLUMN language_adjusted_time boolean NOT NULL DEFAULT false;
ALTER TABLE problems ADD COLUMN reward_points integer
    GENERATED ALWAYS AS (10 * (cobweb_rank_step(rank_type, rank_int) + 1)) STORED NOT NULL;

-- 다음 등급까지 필요한 점수는 현재 등급 문제 10개의 배점입니다.
-- step번째 등급의 최소 누적 점수는 100 × (1 + 2 + ... + step)입니다.
CREATE VIEW cobweb_rank_thresholds AS
SELECT rank_type, rank_int, 50 * step * (step + 1) AS min_points
FROM (
    SELECT rank_type, rank_int, cobweb_rank_step(rank_type, rank_int) AS step
    FROM unnest(cobweb_rank_tiers()) AS tiers(rank_type)
    CROSS JOIN generate_series(1, 5) AS grades(rank_int)
) AS levels;

ALTER TABLE users ADD COLUMN rank_points bigint NOT NULL DEFAULT 0 CHECK (rank_points >= 0);
ALTER TABLE user_solved_problems ADD COLUMN awarded_points integer;

-- 과거 풀이 시점의 난이도 변경 이력은 없으므로 적용 시점 문제 배점으로 기존 원장을 채웁니다.
UPDATE user_solved_problems s SET awarded_points = p.reward_points
FROM problems p WHERE p.id = s.problem_id;
ALTER TABLE user_solved_problems ALTER COLUMN awarded_points SET NOT NULL;
ALTER TABLE user_solved_problems ADD CONSTRAINT chk_solved_awarded_points CHECK (awarded_points > 0);

CREATE FUNCTION cobweb_sync_user_rank() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    SELECT rank_type, rank_int INTO STRICT NEW.rank_type, NEW.rank_int
    FROM cobweb_rank_thresholds WHERE min_points <= NEW.rank_points ORDER BY min_points DESC LIMIT 1;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_sync_user_rank BEFORE INSERT OR UPDATE OF rank_points, rank_type, rank_int ON users
FOR EACH ROW EXECUTE FUNCTION cobweb_sync_user_rank();

-- 해결 기록이 없는 계정도 0점 기준 등급으로 맞춥니다.
UPDATE users u SET rank_points = COALESCE(
    (SELECT SUM(s.awarded_points) FROM user_solved_problems s WHERE s.user_id = u.id), 0);

CREATE FUNCTION cobweb_snapshot_solve_points() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    SELECT reward_points INTO STRICT NEW.awarded_points FROM problems WHERE id = NEW.problem_id;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_snapshot_solve_points BEFORE INSERT ON user_solved_problems
FOR EACH ROW EXECUTE FUNCTION cobweb_snapshot_solve_points();

CREATE FUNCTION cobweb_credit_solve_points() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE users SET rank_points = rank_points + NEW.awarded_points WHERE id = NEW.user_id;
    RETURN NULL;
END
$$;

-- ON CONFLICT DO NOTHING으로 중복된 행에는 AFTER INSERT가 실행되지 않습니다.
CREATE TRIGGER trg_credit_solve_points AFTER INSERT ON user_solved_problems
FOR EACH ROW EXECUTE FUNCTION cobweb_credit_solve_points();

DROP INDEX idx_users_ranking;
CREATE INDEX idx_users_ranking ON users (rank_points DESC, solved_count DESC, submit_count, id)
    WHERE status = 'ACTIVE';

-- Neon의 최소 권한 채점 역할에는 트리거 계산에 필요한 읽기·갱신 권한만 추가합니다.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cobweb_judge_runtime') THEN
        GRANT SELECT ON cobweb_rank_thresholds TO cobweb_judge_runtime;
        GRANT SELECT (reward_points, language_adjusted_time) ON problems TO cobweb_judge_runtime;
        GRANT SELECT (rank_points), UPDATE (rank_points) ON users TO cobweb_judge_runtime;
    END IF;
END
$$;
-- END V3__rank_points.sql

INSERT INTO public.flyway_schema_history
    (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
SELECT MAX(installed_rank) + 1, '3', 'rank points', 'SQL', 'V3__rank_points.sql', 1816901663, current_user, 0, TRUE
FROM public.flyway_schema_history;

COMMIT;
