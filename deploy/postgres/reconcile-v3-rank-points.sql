-- V3 객체와 랭크 데이터가 이미 설치됐지만 Flyway V3 이력만 누락된 PostgreSQL 18 DB용입니다.
-- DB 소유자가 Neon SQL Editor에서 전체를 실행합니다. 정식 V1/V2/V3 외의 이력이나
-- 스키마·권한·점수 차이가 있으면 롤백합니다. Flyway V3 이력 한 행만 추가할 수 있습니다.
BEGIN;
SET LOCAL search_path TO pg_catalog;
SET LOCAL lock_timeout TO '5s';

LOCK TABLE public.flyway_schema_history IN EXCLUSIVE MODE;
LOCK TABLE public.users, public.problems, public.user_solved_problems IN SHARE MODE;

DO $reconcile$
DECLARE
    actual_schema_hash text;
    expected_schema_hash constant text := 'ab41e01adc35673cd96f4e8224be31fc57a4497fa69ad8c6fd1a9f99251602b4';
BEGIN
    IF current_setting('server_version_num')::integer / 10000 <> 18 THEN
        RAISE EXCEPTION 'This V3 schema fingerprint is verified for PostgreSQL 18.';
    END IF;
    IF (SELECT count(*) FROM public.flyway_schema_history) NOT IN (2,3)
       OR NOT EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE installed_rank=1 AND version='1'
                     AND description='postgresql schema' AND type='SQL' AND script='V1__postgresql_schema.sql'
                     AND checksum=-1190079743 AND success)
       OR NOT EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE installed_rank=2 AND version='2'
                     AND description='learning reports' AND type='SQL' AND script='V2__learning_reports.sql'
                     AND checksum=-1413680715 AND success)
       OR EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE installed_rank NOT IN (1,2)
                  AND (installed_rank,version,description,type,script,checksum,success)
                      IS DISTINCT FROM (3,'3','rank points','SQL','V3__rank_points.sql',1816901663,true)) THEN
        RAISE EXCEPTION 'Unexpected Flyway migration history. No changes applied.';
    END IF;

    -- 정식 V3 SQL을 새 PostgreSQL 18에 적용해 얻은 정의와 비교합니다.
    WITH definitions(item) AS (
        SELECT jsonb_build_array('function',p.proname,pg_get_functiondef(p.oid))
          FROM pg_proc p WHERE p.pronamespace='public'::regnamespace
           AND p.proname IN ('cobweb_rank_tiers','cobweb_rank_step','cobweb_sync_user_rank',
                             'cobweb_snapshot_solve_points','cobweb_credit_solve_points')
        UNION ALL
        SELECT jsonb_build_array('column',c.relname||'.'||a.attname,format_type(a.atttypid,a.atttypmod),
               a.attnotnull,a.attgenerated,a.attidentity,a.attcollation::regcollation::text,
               pg_get_expr(d.adbin,d.adrelid))
          FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid
          LEFT JOIN pg_attrdef d ON (d.adrelid,d.adnum)=(a.attrelid,a.attnum)
         WHERE c.relnamespace='public'::regnamespace AND NOT a.attisdropped
           AND (c.relname,a.attname) IN (('problems','language_adjusted_time'),('problems','reward_points'),
               ('users','rank_points'),('user_solved_problems','awarded_points'))
        UNION ALL
        SELECT jsonb_build_array('constraint',c.conname,pg_get_constraintdef(c.oid),
               c.convalidated,c.condeferrable,c.condeferred)
          FROM pg_constraint c WHERE (c.conrelid,c.conname) IN (
              ('public.users'::regclass,'users_rank_points_check'),
              ('public.user_solved_problems'::regclass,'chk_solved_awarded_points'))
        UNION ALL
        SELECT jsonb_build_array('trigger',t.tgname,pg_get_triggerdef(t.oid),t.tgenabled)
          FROM pg_trigger t WHERE (t.tgrelid,t.tgname) IN (
              ('public.users'::regclass,'trg_sync_user_rank'),
              ('public.user_solved_problems'::regclass,'trg_snapshot_solve_points'),
              ('public.user_solved_problems'::regclass,'trg_credit_solve_points'))
        UNION ALL
        SELECT jsonb_build_array('index',c.relname,pg_get_indexdef(c.oid),i.indisvalid,i.indisready,i.indislive)
          FROM pg_class c JOIN pg_index i ON i.indexrelid=c.oid
         WHERE c.relnamespace='public'::regnamespace AND c.relname='idx_users_ranking'
        UNION ALL
        SELECT jsonb_build_array('view',c.relname,pg_get_viewdef(c.oid,false),c.reloptions)
          FROM pg_class c WHERE c.relnamespace='public'::regnamespace AND c.relname='cobweb_rank_thresholds' AND c.relkind='v'
    )
    SELECT encode(sha256(convert_to(jsonb_agg(item ORDER BY (item->>0) COLLATE "C",(item->>1) COLLATE "C",item::text COLLATE "C")::text,'UTF8')),'hex')
    INTO actual_schema_hash
    FROM definitions;
    IF actual_schema_hash IS DISTINCT FROM expected_schema_hash THEN
        RAISE EXCEPTION 'V3 schema fingerprint differs (%). No changes applied.', actual_schema_hash;
    END IF;

    IF to_regrole('cobweb_judge_runtime') IS NULL THEN
        RAISE EXCEPTION 'Expected Neon judge runtime role is missing.';
    END IF;
    IF NOT (has_table_privilege('cobweb_judge_runtime','public.cobweb_rank_thresholds','SELECT')
        AND has_column_privilege('cobweb_judge_runtime','public.problems','reward_points','SELECT')
        AND has_column_privilege('cobweb_judge_runtime','public.problems','language_adjusted_time','SELECT')
        AND has_column_privilege('cobweb_judge_runtime','public.users','rank_points','SELECT')
        AND has_column_privilege('cobweb_judge_runtime','public.users','rank_points','UPDATE')) THEN
        RAISE EXCEPTION 'Required V3 judge privileges are missing. No changes applied.';
    END IF;

    -- 난이도 편집 전 배점을 보존하므로 과거 지급액을 현재 문제 배점에 맞춰 재계산하지 않습니다.
    IF EXISTS (SELECT 1 FROM public.user_solved_problems WHERE awarded_points NOT BETWEEN 10 AND 350
                                                           OR awarded_points % 10 <> 0)
       OR EXISTS (
           SELECT 1 FROM public.users u
           LEFT JOIN (SELECT user_id,sum(awarded_points) AS total FROM public.user_solved_problems
                       GROUP BY user_id) awards ON awards.user_id=u.id
           LEFT JOIN (SELECT rank_type,rank_int,min_points,lead(min_points) OVER (ORDER BY min_points) AS next_min
                       FROM public.cobweb_rank_thresholds) ranks
             ON ranks.rank_type=u.rank_type AND ranks.rank_int=u.rank_int
           WHERE u.rank_points IS DISTINCT FROM coalesce(awards.total,0) OR ranks.min_points IS NULL
              OR u.rank_points<ranks.min_points OR u.rank_points>=ranks.next_min) THEN
        RAISE EXCEPTION 'V3 solve ledger, accumulated points or ranks do not agree. No changes applied.';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE installed_rank=3) THEN
        INSERT INTO public.flyway_schema_history
            (installed_rank,version,description,type,script,checksum,installed_by,execution_time,success)
        VALUES (3,'3','rank points','SQL','V3__rank_points.sql',1816901663,current_user,0,true);
    END IF;
END
$reconcile$;
COMMIT;
