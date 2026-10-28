package com.drewdrew0414.global.config;

import com.drewdrew0414.domain.problem.FunctionSignature;
import com.drewdrew0414.domain.problem.ProblemRequests;
import com.drewdrew0414.domain.submission.Language;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 공개 입문 과정입니다. 한 트랜잭션으로 충돌을 검사하고 기존 문제/제출을 보존합니다. */
@Component @RequiredArgsConstructor @Order(10)
@ConditionalOnProperty(name="curriculum.level0.enabled",havingValue="true",matchIfMissing=true)
public class Level0Data implements ApplicationRunner {
    public static final String PACK_KEY="level0-v1";
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public record Catalog(int version,String name,List<Entry> problems) {}
    public record Entry(int ordinal,int number,String chapter,String title,String description,
                        String inputDescription,String outputDescription,String constraints,
                        String rankType,int rankInt,int timeLimitMs,int memoryLimitMb,
                        FunctionSignature functionSignature,List<String> tags,
                        List<ProblemRequests.Example> examples,List<ProblemRequests.TestCase> tests,
                        ProblemRequests.ReferenceSolution referenceSolution) {}

    @Override @Transactional public void run(ApplicationArguments arguments) {
        importCatalog(readCatalog());
    }
    public Catalog readCatalog() {
        return readCatalog("problems/level0.json",8*1024*1024,"Lv.0");
    }
    Catalog readCatalog(String resource,int maximumBytes,String label) {
        try(var stream=new ClassPathResource(resource).getInputStream()) {
            byte[] bytes=stream.readNBytes(maximumBytes+1);
            if(bytes.length>maximumBytes) throw new IllegalStateException(label+" catalog exceeds its byte limit");
            return json.readValue(bytes,Catalog.class);
        } catch(java.io.IOException error) {throw new IllegalStateException("Cannot read "+label+" catalog",error);}
    }
    /** 호출자는 트랜잭션 경계 안에 있어야 합니다. ApplicationRunner 및 통합 테스트가 이를 보장합니다. */
    @Transactional public void importCatalog(Catalog catalog) {
        validate(catalog);
        String fingerprint=fingerprint(catalog);
        // 수동 작성/동시 기동과 번호 공간을 공유하므로 먼저 시퀀스 행을 잠급니다.
        Integer next=db.queryForObject("SELECT next_number FROM problem_number_sequence WHERE id=1 FOR UPDATE",Integer.class);
        if(next==null) throw conflict("problem number sequence is missing");
        if(imported(PACK_KEY,100,1,fingerprint,"Lv.0")) {
            // 이력만 확인하며 등록 후 제작자가 수정한 문제 본문과 공개 상태는 덮어쓰지 않습니다.
            advanceSequence(next);
            return;
        }
        var occupied=db.queryForList("""
            SELECT p.id,p.problem_number,p.problem_title,u.username FROM problems p JOIN users u ON u.id=p.author_id
            WHERE p.problem_number BETWEEN 1000 AND 1099 ORDER BY p.problem_number FOR UPDATE
            """);
        Map<String,Object> chain=null;
        for(var row:occupied) {
            if(((Number)row.get("problem_number")).intValue()==1000 &&
               "체인 레이저".equals(row.get("problem_title")) && "cobweb-system".equals(row.get("username"))) chain=row;
            else throw conflict("problem number "+row.get("problem_number")+" is already authored");
        }
        for(var entry:catalog.problems()) {
            if(db.queryForObject("SELECT COUNT(*) FROM problems WHERE title_scope=0 AND lower(problem_title)=lower(?)",Long.class,entry.title())>0)
                throw conflict("problem title is already authored: "+entry.title());
        }
        long author=seedAuthor("Lv.0");
        if(chain!=null) {
            int archivedNumber=nextAvailableAtLeast1100();
            db.update("UPDATE problems SET problem_number=?,status='ARCHIVED',visibility='PRIVATE',requested_visibility='PRIVATE' WHERE id=?",
                archivedNumber,chain.get("id"));
            db.update("INSERT INTO problem_status_history(problem_id,status,cause,checker_id) VALUES(?,'ARCHIVED',?,?)",
                chain.get("id"),"Lv.0 과정 등록: 체인 레이저 보관 (이전 번호 1000, 보관 번호 "+archivedNumber+")",author);
        }
        for(var entry:catalog.problems()) insert(entry,author,fingerprint,PACK_KEY,"Lv.0 과정 최초 등록");
        advanceSequence(next);
    }
    private int nextAvailableAtLeast1100() {
        long maximum=db.queryForObject("SELECT COALESCE(MAX(problem_number),999) FROM problems",Long.class);
        if(maximum>=Integer.MAX_VALUE-1) throw conflict("problem number space exhausted");
        return (int)Math.max(1100,maximum+1);
    }
    void advanceSequence(int previous) {
        long maximum=db.queryForObject("SELECT COALESCE(MAX(problem_number),999) FROM problems",Long.class);
        if(maximum>=Integer.MAX_VALUE-1) throw conflict("problem number space exhausted");
        db.update("UPDATE problem_number_sequence SET next_number=? WHERE id=1",Math.max(previous,(int)maximum+1));
    }
    /** 가져온 팩의 행 수·순서·지문만 확인하고, 등록 후의 사용자 편집은 읽거나 덮어쓰지 않습니다. */
    boolean imported(String packKey,int count,int firstOrdinal,String fingerprint,String label) {
        var imported=db.queryForList("""
            SELECT ordinal,problem_id,catalog_sha256 FROM curriculum_problem_entries WHERE pack_key=? ORDER BY ordinal FOR UPDATE
            """,packKey);
        if(imported.isEmpty()) return false;
        if(imported.size()!=count) throw conflict(label,"partial previous import");
        for(int i=0;i<count;i++) {
            var row=imported.get(i);
            if(((Number)row.get("ordinal")).intValue()!=i+firstOrdinal || !fingerprint.equals(row.get("catalog_sha256")))
                throw conflict(label,"catalog identity changed");
        }
        return true;
    }
    /** 같은 이름의 실제 계정을 시드 계정으로 바꾸지 않습니다. */
    long seedAuthor(String label) {
        db.update("INSERT INTO users(username,password,status) VALUES('cobweb-system','!','STOPPED') ON CONFLICT DO NOTHING");
        var system=db.queryForMap("SELECT id,password,status FROM users WHERE lower(username)='cobweb-system' FOR UPDATE");
        if(!"STOPPED".equals(system.get("status")) || !"!".equals(system.get("password")))
            throw conflict(label,"cobweb-system is not the stopped seed account");
        return ((Number)system.get("id")).longValue();
    }
    void insert(Entry p,long author,String fingerprint,String packKey,String historyPrefix) {
        db.update("""
            INSERT INTO problems(problem_number,problem_title,description,input_description,output_description,
                constraints_text,rank_type,rank_int,time_limit_ms,memory_limit_mb,execution_mode,judge_type,
                status,visibility,requested_visibility,author_id,group_id,function_abi_compatible,function_signature,
                author_public,published_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,'FUNCTION','SPECIAL','PUBLISHED','PUBLIC','PUBLIC',?,NULL,TRUE,CAST(? AS jsonb),FALSE,(clock_timestamp() AT TIME ZONE 'UTC'))
            """,p.number(),p.title(),p.description(),p.inputDescription(),p.outputDescription(),p.constraints(),
            p.rankType(),p.rankInt(),p.timeLimitMs(),p.memoryLimitMb(),author,p.functionSignature().toJson(json));
        long id=db.queryForObject("SELECT id FROM problems WHERE problem_number=?",Long.class,p.number());
        int order=0;
        for(var example:p.examples()) db.update("""
            INSERT INTO problem_examples(problem_id,input_text,output_text,explanation,display_order) VALUES(?,?,?,?,?)
            """,id,example.input(),example.output(),example.explanation(),++order);
        order=0;
        for(var test:p.tests()) db.update("""
            INSERT INTO problem_test_cases(problem_id,input_data,expected_output,compare_type,display_order) VALUES(?,?,?,'EXACT',?)
            """,id,test.input(),test.expected(),++order);
        for(String language:Language.forMode("FUNCTION"))
            db.update("INSERT INTO problem_languages(problem_id,language) VALUES(?,?)",id,language);
        for(String tag:p.tags()) {
            db.update("INSERT INTO tags(name) VALUES(?) ON CONFLICT DO NOTHING",tag);
            long tagId=db.queryForObject("SELECT id FROM tags WHERE lower(name)=lower(?)",Long.class,tag);
            db.update("INSERT INTO problem_tags(problem_id,tag_id) VALUES(?,?)",id,tagId);
        }
        var ref=p.referenceSolution();
        db.update("INSERT INTO reference_solutions(problem_id,language,file_name,source_code) VALUES(?,?,?,?)",
            id,ref.language(),ref.fileName(),ref.sourceCode());
        db.update("INSERT INTO problem_status_history(problem_id,status,cause,checker_id) VALUES(?,'PUBLISHED',?,?)",
            id,historyPrefix+": "+p.chapter()+" / "+p.ordinal(),author);
        db.update("INSERT INTO curriculum_problem_entries(pack_key,ordinal,problem_id,catalog_sha256) VALUES(?,?,?,?)",
            packKey,p.ordinal(),id,fingerprint);
    }
    public void validate(Catalog catalog) {
        validate(catalog,100,1000,"INITIATE","Lv.0");
    }
    void validate(Catalog catalog,int count,int firstNumber,String rankType,String label) {
        if(catalog==null || catalog.version()!=1 || catalog.name()==null || catalog.name().isBlank() ||
           catalog.problems()==null || catalog.problems().size()!=count) throw conflict(label,"expected version 1 and exactly "+count+" problems");
        var titles=new HashSet<String>();
        for(int index=0;index<count;index++) {
            Entry p=catalog.problems().get(index);
            if(p==null || p.ordinal()!=index+1 || p.number()!=firstNumber+index || p.chapter()==null || p.chapter().isBlank() ||
               p.title()==null || p.title().isBlank() || p.title().length()>200 || !p.title().equals(p.title().strip()) ||
               !titles.add(p.title().toLowerCase(Locale.ROOT)) || p.description()==null || p.description().isBlank() ||
               p.description().length()>1000000 || p.inputDescription()==null || p.inputDescription().length()>65535 ||
               p.outputDescription()==null || p.outputDescription().length()>65535 ||
               p.constraints()==null || p.constraints().length()>65535 || !rankType.equals(p.rankType()) ||
               p.rankInt()<1 || p.rankInt()>5 || p.timeLimitMs()<100 || p.timeLimitMs()>10000 ||
               p.memoryLimitMb()<64 || p.memoryLimitMb()>512 || p.functionSignature()==null ||
               p.examples()==null || p.examples().isEmpty() || p.examples().size()>20 ||
               p.tests()==null || p.tests().isEmpty() || p.tests().size()>50 ||
               p.tags()==null || p.tags().size()>10) throw conflict(label,"invalid problem at ordinal "+(index+1));
            p.functionSignature().validated();
            for(var example:p.examples()) {
                if(example==null || example.explanation()!=null && example.explanation().length()>65535)
                    throw conflict(label,"invalid example");
                p.functionSignature().validateCase(json,example.input(),example.output());
            }
            for(var test:p.tests()) {
                if(test==null) throw conflict(label,"invalid test");
                p.functionSignature().validateCase(json,test.input(),test.expected());
            }
            var tags=new HashSet<String>();
            for(String tag:p.tags()) if(tag==null || tag.isBlank() || tag.length()>50 || !tags.add(tag))
                throw conflict(label,"invalid tag");
            var ref=p.referenceSolution();
            if(ref==null || !"PYTHON".equals(ref.language()) || ref.fileName()==null ||
               !ref.fileName().matches("[A-Za-z0-9_.-]{1,120}") || ref.sourceCode()==null || ref.sourceCode().isBlank() ||
               ref.sourceCode().getBytes(StandardCharsets.UTF_8).length>65536) throw conflict(label,"invalid private reference solution");
        }
    }
    String fingerprint(Catalog catalog) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(catalog)));}
        catch(java.security.NoSuchAlgorithmException error) {throw new IllegalStateException(error);}
    }
    private static IllegalStateException conflict(String reason) {
        return conflict("Lv.0",reason);
    }
    static IllegalStateException conflict(String label,String reason) {
        return new IllegalStateException(label+" import refused without overwriting existing data: "+reason);
    }
}
