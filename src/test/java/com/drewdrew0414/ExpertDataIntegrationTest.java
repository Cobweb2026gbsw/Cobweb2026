package com.drewdrew0414;

import com.drewdrew0414.global.config.AlgorithmData;
import com.drewdrew0414.global.config.Level0Data;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** EXPERT 등록은 전용 PostgreSQL test DB의 롤백 트랜잭션 안에서만 검증합니다. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
class ExpertDataIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    private AlgorithmData importer;
    private Level0Data.Catalog skilled,elite,expert;
    private long chainId,author;

    @BeforeEach void setup(TestInfo info) {
        assertTrue(db.queryForObject("SELECT current_database()",String.class).endsWith("_test"),"Only a dedicated test database is allowed");
        importer=new AlgorithmData(db,new Level0Data(db,json));
        skilled=importer.readLevel1Catalog();elite=importer.readLevel2Catalog();expert=importer.readLevel3Catalog();
        if(!info.getTestMethod().orElseThrow().getName().equals("aLateFailureRollsBackEveryPackAndChainTogether")) prepare();
    }
    private void prepare() {
        var chain=db.queryForMap("SELECT p.id,p.author_id FROM problems p JOIN users u ON u.id=p.author_id WHERE p.problem_title='체인 레이저' AND u.username='cobweb-system'");
        chainId=((Number)chain.get("id")).longValue();author=((Number)chain.get("author_id")).longValue();
        int temporary=temporary();
        for(var row:db.queryForList("SELECT id FROM problems WHERE problem_number BETWEEN 1100 AND 1345 AND id<>?",chainId))
            db.update("UPDATE problems SET problem_number=? WHERE id=?",temporary++,row.get("id"));
        db.update("UPDATE problems SET problem_number=1100,status='ARCHIVED',visibility='PRIVATE',requested_visibility='PRIVATE' WHERE id=?",chainId);
    }
    private int temporary() {return db.queryForObject("SELECT GREATEST(2000000,COALESCE(MAX(problem_number),999)+1000) FROM problems",Integer.class);}
    private void importAll() {importer.importCatalogs(skilled,elite,expert);}
    private int expertEntries() {return db.queryForObject("SELECT COUNT(*) FROM curriculum_problem_entries WHERE pack_key=?",Integer.class,AlgorithmData.LEVEL3_PACK_KEY);}
    private int allEntries() {return db.queryForObject("SELECT COUNT(*) FROM curriculum_problem_entries WHERE pack_key IN (?,?,?,?)",Integer.class,AlgorithmData.LEVEL1_PACK_KEY,AlgorithmData.LEVEL2_PACK_KEY,AlgorithmData.LEVEL3_PACK_KEY,AlgorithmData.CHAIN_PACK_KEY);}
    private void authored(int number,String title) {db.update("INSERT INTO problems(problem_number,problem_title,description,input_description,output_description,author_id) VALUES(?,?,'body','input','output',?)",number,title,author);}

    @Test void importsEightyExpertProblemsWithEightyLedgerEntriesAndTwelveHundredLanguages() {
        importAll();
        assertEquals(80,expertEntries());assertEquals(246,allEntries());
        assertEquals(80,db.queryForObject("SELECT COUNT(*) FROM problems WHERE problem_number BETWEEN 1266 AND 1345 AND rank_type='EXPERT' AND execution_mode='FUNCTION' AND status='PUBLISHED' AND visibility='PUBLIC' AND function_signature IS NOT NULL AND author_public=FALSE",Integer.class));
        assertEquals(1200,db.queryForObject("SELECT COUNT(*) FROM problem_languages l JOIN curriculum_problem_entries e ON e.problem_id=l.problem_id WHERE e.pack_key=?",Integer.class,AlgorithmData.LEVEL3_PACK_KEY));
        assertEquals(80,db.queryForObject("SELECT COUNT(*) FROM reference_solutions r JOIN curriculum_problem_entries e ON e.problem_id=r.problem_id WHERE e.pack_key=?",Integer.class,AlgorithmData.LEVEL3_PACK_KEY));
        assertEquals(expert.problems().stream().mapToInt(p->p.examples().size()).sum(),db.queryForObject("SELECT COUNT(*) FROM problem_examples x JOIN curriculum_problem_entries e ON e.problem_id=x.problem_id WHERE e.pack_key=?",Integer.class,AlgorithmData.LEVEL3_PACK_KEY));
        for(int chapter=0;chapter<5;chapter++) assertEquals(16,db.queryForObject("SELECT COUNT(*) FROM problems WHERE problem_number BETWEEN ? AND ? AND rank_type='EXPERT' AND rank_int=?",Integer.class,1266+chapter*16,1281+chapter*16,5-chapter));
        assertTrue(db.queryForObject("SELECT next_number FROM problem_number_sequence WHERE id=1",Integer.class)>=1346);
        assertTrue(db.queryForObject("SELECT next_number FROM problem_number_sequence WHERE id=1",Integer.class)>db.queryForObject("SELECT MAX(problem_number) FROM problems",Integer.class));
    }

    @Test void repeatImportPreservesEditedPrivateProblemAndItsSubmission() {
        importAll();
        long id=db.queryForObject("SELECT problem_id FROM curriculum_problem_entries WHERE pack_key=? AND ordinal=1",Long.class,AlgorithmData.LEVEL3_PACK_KEY);
        db.update("UPDATE problems SET problem_title='Expert user edit',description='Do not replace this body',status='ARCHIVED',visibility='PRIVATE',requested_visibility='PRIVATE',author_public=TRUE WHERE id=?",id);
        long submission=db.queryForObject("INSERT INTO submissions(user_id,problem_id,language,source_code,status) VALUES(?,?,'PYTHON','preserved user source','WRONG_ANSWER') RETURNING id",Long.class,author,id);
        var problem=db.queryForMap("SELECT * FROM problems WHERE id=?",id);
        var storedSubmission=db.queryForMap("SELECT * FROM submissions WHERE id=?",submission);
        var cases=db.queryForList("SELECT * FROM problem_test_cases WHERE problem_id=? ORDER BY id",id);
        var refs=db.queryForList("SELECT * FROM reference_solutions WHERE problem_id=? ORDER BY language",id);
        long count=db.queryForObject("SELECT COUNT(*) FROM problems",Long.class),history=db.queryForObject("SELECT COUNT(*) FROM problem_status_history",Long.class);
        importAll();
        assertEquals(80,expertEntries());assertEquals(count,db.queryForObject("SELECT COUNT(*) FROM problems",Long.class));
        assertEquals(history,db.queryForObject("SELECT COUNT(*) FROM problem_status_history",Long.class));
        assertEquals(problem,db.queryForMap("SELECT * FROM problems WHERE id=?",id));
        assertEquals(storedSubmission,db.queryForMap("SELECT * FROM submissions WHERE id=?",submission));
        assertEquals(cases,db.queryForList("SELECT * FROM problem_test_cases WHERE problem_id=? ORDER BY id",id));
        assertEquals(refs,db.queryForList("SELECT * FROM reference_solutions WHERE problem_id=? ORDER BY language",id));
    }

    @Test void appendingExpertLeavesEarlierCatalogFingerprintsAndAllEarlierRowsUntouched() {
        importer.importCatalogs(skilled,elite);
        db.update("UPDATE problems SET visibility='PRIVATE',requested_visibility='PRIVATE' WHERE problem_number IN (1100,1101,1176)");
        var ledger=db.queryForList("SELECT * FROM curriculum_problem_entries ORDER BY pack_key,ordinal");
        var earlier=db.queryForList("SELECT * FROM problems WHERE problem_number BETWEEN 1100 AND 1265 ORDER BY id");
        var cases=db.queryForList("SELECT c.* FROM problem_test_cases c JOIN problems p ON p.id=c.problem_id WHERE p.problem_number BETWEEN 1100 AND 1265 ORDER BY c.id");
        var refs=db.queryForList("SELECT r.* FROM reference_solutions r JOIN problems p ON p.id=r.problem_id WHERE p.problem_number BETWEEN 1100 AND 1265 ORDER BY r.problem_id,r.language");
        importAll();
        assertEquals(80,expertEntries());
        assertEquals(ledger,db.queryForList("SELECT * FROM curriculum_problem_entries WHERE pack_key<>? ORDER BY pack_key,ordinal",AlgorithmData.LEVEL3_PACK_KEY));
        assertEquals(earlier,db.queryForList("SELECT * FROM problems WHERE problem_number BETWEEN 1100 AND 1265 ORDER BY id"));
        assertEquals(cases,db.queryForList("SELECT c.* FROM problem_test_cases c JOIN problems p ON p.id=c.problem_id WHERE p.problem_number BETWEEN 1100 AND 1265 ORDER BY c.id"));
        assertEquals(refs,db.queryForList("SELECT r.* FROM reference_solutions r JOIN problems p ON p.id=r.problem_id WHERE p.problem_number BETWEEN 1100 AND 1265 ORDER BY r.problem_id,r.language"));
    }

    @Test void expertNumberCollisionPreventsEveryPackAndChainWrite() {
        authored(1345,"Expert number collision "+UUID.randomUUID());
        long problems=db.queryForObject("SELECT COUNT(*) FROM problems",Long.class),history=db.queryForObject("SELECT COUNT(*) FROM problem_status_history",Long.class);
        assertThrows(IllegalStateException.class,this::importAll);
        assertEquals(0,allEntries());assertEquals(problems,db.queryForObject("SELECT COUNT(*) FROM problems",Long.class));
        assertEquals(history,db.queryForObject("SELECT COUNT(*) FROM problem_status_history",Long.class));
        assertEquals("PRIVATE",db.queryForObject("SELECT visibility FROM problems WHERE id=?",String.class,chainId));
    }

    @Test void expertTitleCollisionPreventsEveryPackAndChainWrite() {
        authored(temporary(),expert.problems().getLast().title().toUpperCase(java.util.Locale.ROOT));
        assertThrows(IllegalStateException.class,this::importAll);
        assertEquals(0,allEntries());assertEquals("ARCHIVED",db.queryForObject("SELECT status FROM problems WHERE id=?",String.class,chainId));
    }

    @Test void partialExpertLedgerIsRefusedBeforeAnyOtherPackIsWritten() {
        db.update("INSERT INTO curriculum_problem_entries(pack_key,ordinal,problem_id,catalog_sha256) VALUES(?,1,?,?)",AlgorithmData.LEVEL3_PACK_KEY,chainId,"a".repeat(64));
        assertThrows(IllegalStateException.class,this::importAll);
        assertEquals(1,allEntries());assertEquals("PRIVATE",db.queryForObject("SELECT visibility FROM problems WHERE id=?",String.class,chainId));
    }

    @Test void changedExpertFingerprintNeverReconstructsOrPublishesUserContent() {
        importAll();
        db.update("UPDATE problems SET status='ARCHIVED',visibility='PRIVATE',requested_visibility='PRIVATE' WHERE problem_number=1266");
        var changed=new Level0Data.Catalog(expert.version(),expert.name()+" changed",expert.problems());
        assertThrows(IllegalStateException.class,()->importer.importCatalogs(skilled,elite,changed));
        assertEquals(246,allEntries());assertEquals("PRIVATE",db.queryForObject("SELECT visibility FROM problems WHERE problem_number=1266",String.class));
    }

    @Test void aChangedEarlierLedgerStillPreventsExpertFromBeingAdded() {
        importer.importCatalogs(skilled,elite);
        db.update("UPDATE curriculum_problem_entries SET catalog_sha256=? WHERE pack_key=?","f".repeat(64),AlgorithmData.LEVEL2_PACK_KEY);
        assertThrows(IllegalStateException.class,this::importAll);
        assertEquals(0,expertEntries());assertEquals(166,allEntries());
    }

    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void aLateFailureRollsBackEveryPackAndChainTogether() {
        long problems=db.queryForObject("SELECT COUNT(*) FROM problems",Long.class),history=db.queryForObject("SELECT COUNT(*) FROM problem_status_history",Long.class);
        int entries=allEntries();
        var chain=db.queryForMap("SELECT p.* FROM problems p JOIN users u ON u.id=p.author_id WHERE p.problem_title='체인 레이저' AND u.username='cobweb-system'");
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).executeWithoutResult(status->{
            prepare();importAll();assertEquals(246,allEntries());
            throw new IllegalStateException("Synthetic failure after all three catalogs and chain");
        }));
        assertEquals(problems,db.queryForObject("SELECT COUNT(*) FROM problems",Long.class));assertEquals(history,db.queryForObject("SELECT COUNT(*) FROM problem_status_history",Long.class));
        assertEquals(entries,allEntries());assertEquals(chain,db.queryForMap("SELECT * FROM problems WHERE id=?",chain.get("id")));
    }
}
