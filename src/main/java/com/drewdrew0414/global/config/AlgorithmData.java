package com.drewdrew0414.global.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/** Lv.0 이후의 두 과정과 체인 레이저를 같은 트랜잭션에서 최초 한 번만 등록합니다. */
@Component @RequiredArgsConstructor @Order(20)
@ConditionalOnProperty(name="curriculum.level0.enabled",havingValue="true",matchIfMissing=true)
public class AlgorithmData implements ApplicationRunner {
    public static final String LEVEL1_PACK_KEY="level1-v1";
    public static final String LEVEL2_PACK_KEY="level2-v1";
    public static final String CHAIN_PACK_KEY="elite-chain-v1";
    private static final String LABEL="Algorithm curriculum";
    private static final int CATALOG_LIMIT=32*1024*1024;
    private final JdbcTemplate db;
    private final Level0Data catalogs;

    @Override @Transactional public void run(ApplicationArguments arguments) {
        importCatalogs(readLevel1Catalog(),readLevel2Catalog());
    }
    public Level0Data.Catalog readLevel1Catalog() {
        return catalogs.readCatalog("problems/level1.json",CATALOG_LIMIT,"Lv.1");
    }
    public Level0Data.Catalog readLevel2Catalog() {
        return catalogs.readCatalog("problems/level2.json",CATALOG_LIMIT,"Lv.2");
    }
    /** 직접 호출할 때도 트랜잭션 프록시 또는 테스트의 롤백 트랜잭션 안에서 실행합니다. */
    @Transactional public void importCatalogs(Level0Data.Catalog level1,Level0Data.Catalog level2) {
        catalogs.validate(level1,75,1101,"SKILLED","Lv.1");
        catalogs.validate(level2,90,1176,"ELITE","Lv.2");
        var titles=new HashSet<String>();
        for(var catalog:new Level0Data.Catalog[]{level1,level2})
            for(var entry:catalog.problems()) if(!titles.add(entry.title().toLowerCase(Locale.ROOT)))
                throw conflict("duplicate title between the two new catalogs: "+entry.title());
        String level1Fingerprint=catalogs.fingerprint(level1),level2Fingerprint=catalogs.fingerprint(level2);
        Integer previous=db.queryForObject("SELECT next_number FROM problem_number_sequence WHERE id=1 FOR UPDATE",Integer.class);
        if(previous==null) throw conflict("problem number sequence is missing");
        boolean level1Imported=catalogs.imported(LEVEL1_PACK_KEY,75,1,level1Fingerprint,"Lv.1");
        boolean level2Imported=catalogs.imported(LEVEL2_PACK_KEY,90,1,level2Fingerprint,"Lv.2");
        if(!level1Imported) available(level1);
        if(!level2Imported) available(level2);
        ChainPlan chain=chainPlan();
        // 모든 번호·제목·이력을 먼저 검사합니다. 이력이 있으면 실제 문제 내용/공개 상태는 건드리지 않습니다.
        if(!level1Imported||!level2Imported||!chain.imported()) {
            long author=catalogs.seedAuthor(LABEL);
            if(!chain.imported()) restoreChain(chain,author);
            if(!level1Imported) for(var entry:level1.problems())
                catalogs.insert(entry,author,level1Fingerprint,LEVEL1_PACK_KEY,"Lv.1 과정 최초 등록");
            if(!level2Imported) for(var entry:level2.problems())
                catalogs.insert(entry,author,level2Fingerprint,LEVEL2_PACK_KEY,"Lv.2 과정 최초 등록");
        }
        catalogs.advanceSequence(previous);
    }

    private void available(Level0Data.Catalog catalog) {
        for(var entry:catalog.problems()) {
            if(db.queryForObject("SELECT COUNT(*) FROM problems WHERE problem_number=?",Long.class,entry.number())>0)
                throw conflict("problem number is already authored: "+entry.number());
            if(db.queryForObject("SELECT COUNT(*) FROM problems WHERE title_scope=0 AND problem_title=?",Long.class,entry.title())>0)
                throw conflict("problem title is already authored: "+entry.title());
        }
    }
    private record ChainPlan(boolean imported,Long id,String sql,String fingerprint) {}

    private ChainPlan chainPlan() {
        // 원본 시드가 upsert이므로 숫자와 전역 제목의 unique-key 범위를 각각 잠급니다.
        // REPEATABLE READ의 빈 key gap도 보호하여 검사 이후 다른 제목 편집/생성이 끼어들지 못하게 합니다.
        var numberOwners=db.queryForList("SELECT id FROM problems WHERE problem_number=1100 FOR UPDATE");
        var titleOwners=db.queryForList("SELECT id FROM problems WHERE title_scope=0 AND problem_title=? FOR UPDATE","체인 레이저");
        String script=chainScript(),fingerprint=sha256(script.getBytes(StandardCharsets.UTF_8));
        if(catalogs.imported(CHAIN_PACK_KEY,1,0,fingerprint,"ELITE chain")) {
            long id=db.queryForObject("SELECT problem_id FROM curriculum_problem_entries WHERE pack_key=? AND ordinal=0",Long.class,CHAIN_PACK_KEY);
            if(db.queryForObject("SELECT problem_number FROM problems WHERE id=?",Integer.class,id)!=1100)
                throw conflict("previously imported chain is not at problem number 1100");
            return new ChainPlan(true,id,script,fingerprint);
        }
        var chains=db.queryForList("""
            SELECT p.id,p.problem_number FROM problems p JOIN users u ON u.id=p.author_id
            WHERE p.problem_title='체인 레이저' AND u.username='cobweb-system' FOR UPDATE
            """);
        if(chains.size()>1) throw conflict("multiple seed chain problems exist");
        if(!chains.isEmpty()) {
            var chain=chains.getFirst();
            if(((Number)chain.get("problem_number")).intValue()!=1100)
                throw conflict("existing seed chain is not at problem number 1100");
            long id=((Number)chain.get("id")).longValue();
            if(db.queryForObject("SELECT COUNT(*) FROM curriculum_problem_entries WHERE problem_id=?",Long.class,id)>0)
                throw conflict("seed chain already belongs to another imported pack");
            return new ChainPlan(false,id,script,fingerprint);
        }
        if(!numberOwners.isEmpty()||!titleOwners.isEmpty())
            throw conflict("chain problem number or title is already authored");
        return new ChainPlan(false,null,script,fingerprint);
    }

    private void restoreChain(ChainPlan plan,long author) {
        long id;
        if(plan.id()==null) {
            // seedAuthor가 STOPPED / ! 계정을 잠근 뒤에만 원본 SQL을 실행합니다.
            var dataSource=Objects.requireNonNull(db.getDataSource(),"JDBC DataSource is missing");
            var connection=DataSourceUtils.getConnection(dataSource);
            try {
                ScriptUtils.executeSqlScript(connection,new EncodedResource(new ByteArrayResource(plan.sql().getBytes(StandardCharsets.UTF_8)),StandardCharsets.UTF_8));
            } finally {DataSourceUtils.releaseConnection(connection,dataSource);}
            id=db.queryForObject("SELECT id FROM problems WHERE problem_number=1100",Long.class);
        } else {
            id=plan.id();
            // 기존 ID, 제출/테스트/정답 코드/작성자 공개 설정을 그대로 두고 공개 상태만 한 번 복구합니다.
            db.update("""
                UPDATE problems SET status='PUBLISHED',visibility='PUBLIC',requested_visibility='PUBLIC',
                    published_at=COALESCE(published_at,CURRENT_TIMESTAMP) WHERE id=?
                """,id);
            db.update("INSERT INTO problem_status_history(problem_id,status,cause,checker_id) VALUES(?,'PUBLISHED',?,?)",
                id,"ELITE 과정 등록: 체인 레이저 공개 복구 (1100)",author);
        }
        db.update("INSERT INTO curriculum_problem_entries(pack_key,ordinal,problem_id,catalog_sha256) VALUES(?,0,?,?)",CHAIN_PACK_KEY,id,plan.fingerprint());
    }

    /** 번호와 최초 계정 INSERT 두 곳만 바꿉니다. 원본의 문제 본문/예제/테스트/정답은 재작성하지 않습니다. */
    private String chainScript() {
        try(var stream=new ClassPathResource("demo/chain-laser.sql").getInputStream()) {
            byte[] bytes=stream.readNBytes(1024*1024+1);
            if(bytes.length>1024*1024) throw conflict("chain seed exceeds 1MiB");
            String source=new String(bytes,StandardCharsets.UTF_8);
            String number="SET @chain_laser_problem_number = 1000;";
            String user="INSERT INTO users(username,password,status) VALUES ('cobweb-system','!', 'STOPPED');";
            if(source.indexOf(number)<0||source.indexOf(number)!=source.lastIndexOf(number)||source.indexOf(user)<0||source.indexOf(user)!=source.lastIndexOf(user))
                throw conflict("chain seed no longer has the expected exact seed statements");
            return source.replace(number,"SET @chain_laser_problem_number = 1100;")
                .replace(user,"INSERT IGNORE INTO users(username,password,status) VALUES ('cobweb-system','!', 'STOPPED');");
        } catch(java.io.IOException error) {throw new IllegalStateException("Cannot read chain seed",error);}
    }
    private static String sha256(byte[] bytes) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException error) {throw new IllegalStateException(error);}
    }
    private static IllegalStateException conflict(String reason) {return Level0Data.conflict(LABEL,reason);}
}
