package com.drewdrew0414;

import com.drewdrew0414.global.config.AlgorithmData;
import com.drewdrew0414.global.config.Level0Data;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** test 프로필의 전용 DB만 사용하고 모든 fixture·import를 롤백합니다. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
class AlgorithmDataIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    private AlgorithmData importer;
    private Level0Data.Catalog level1,level2;
    private long chainId,author;

    @BeforeEach void setup(TestInfo info) {
        assertTrue(db.queryForObject("SELECT current_database()",String.class).endsWith("_test"),"Only a dedicated test database is allowed");
        importer=new AlgorithmData(db,new Level0Data(db,json));
        level1=importer.readLevel1Catalog();level2=importer.readLevel2Catalog();
        if(!info.getTestMethod().orElseThrow().getName().equals("allThreeImportsRollBackTogetherAfterALateTransactionFailure")) prepare();
    }
    private void prepare() {
        var chain=db.queryForMap("""
            SELECT p.id,p.author_id FROM problems p JOIN users u ON u.id=p.author_id
            WHERE p.problem_title='체인 레이저' AND u.username='cobweb-system'
            """);
        chainId=((Number)chain.get("id")).longValue();author=((Number)chain.get("author_id")).longValue();
        int temporary=temporary();
        for(var row:db.queryForList("SELECT id FROM problems WHERE problem_number BETWEEN 1100 AND 1265 AND id<>?",chainId))
            db.update("UPDATE problems SET problem_number=? WHERE id=?",temporary++,row.get("id"));
        db.update("UPDATE problems SET problem_number=1100,status='ARCHIVED',visibility='PRIVATE',requested_visibility='PRIVATE',author_public=TRUE WHERE id=?",chainId);
    }
    private int temporary() {return db.queryForObject("SELECT GREATEST(2000000,COALESCE(MAX(problem_number),999)+1000) FROM problems",Integer.class);}
    private int packEntries() {return db.queryForObject("SELECT COUNT(*) FROM curriculum_problem_entries WHERE pack_key IN (?,?,?)",Integer.class,AlgorithmData.LEVEL1_PACK_KEY,AlgorithmData.LEVEL2_PACK_KEY,AlgorithmData.CHAIN_PACK_KEY);}
    private void authored(int number,String title) {
        db.update("INSERT INTO problems(problem_number,problem_title,description,input_description,output_description,author_id) VALUES(?,?,'body','input','output',?)",number,title,author);
    }

    @Test void importsSeventyFiveSkilledAndNinetyEliteFunctionsAndRestoresOnlyChainPublication() {
        long tests=db.queryForObject("SELECT COUNT(*) FROM problem_test_cases WHERE problem_id=?",Long.class,chainId);
        long references=db.queryForObject("SELECT COUNT(*) FROM reference_solutions WHERE problem_id=?",Long.class,chainId);
        db.update("UPDATE problems SET description='Preserved chain body' WHERE id=?",chainId);
        long submission=db.queryForObject("INSERT INTO submissions(user_id,problem_id,language,source_code,status) VALUES(?,?,'PYTHON','previous chain answer','WRONG_ANSWER') RETURNING id",Long.class,author,chainId);

        importer.importCatalogs(level1,level2);

        assertEquals(75,db.queryForObject("SELECT COUNT(*) FROM problems WHERE problem_number BETWEEN 1101 AND 1175 AND rank_type='SKILLED' AND execution_mode='FUNCTION' AND status='PUBLISHED' AND visibility='PUBLIC' AND function_signature IS NOT NULL AND author_public=FALSE",Integer.class));
        assertEquals(90,db.queryForObject("SELECT COUNT(*) FROM problems WHERE problem_number BETWEEN 1176 AND 1265 AND rank_type='ELITE' AND execution_mode='FUNCTION' AND status='PUBLISHED' AND visibility='PUBLIC' AND function_signature IS NOT NULL AND author_public=FALSE",Integer.class));
        assertEquals(166,packEntries());
        assertEquals(2475,db.queryForObject("SELECT COUNT(*) FROM problem_languages l JOIN curriculum_problem_entries e ON e.problem_id=l.problem_id WHERE e.pack_key IN (?,?)",Integer.class,AlgorithmData.LEVEL1_PACK_KEY,AlgorithmData.LEVEL2_PACK_KEY));
        assertEquals(165,db.queryForObject("SELECT COUNT(*) FROM reference_solutions r JOIN curriculum_problem_entries e ON e.problem_id=r.problem_id WHERE e.pack_key IN (?,?)",Integer.class,AlgorithmData.LEVEL1_PACK_KEY,AlgorithmData.LEVEL2_PACK_KEY));
        assertEquals(chainId,db.queryForObject("SELECT problem_id FROM curriculum_problem_entries WHERE pack_key=? AND ordinal=0",Long.class,AlgorithmData.CHAIN_PACK_KEY));
        assertEquals(chainId,db.queryForObject("SELECT problem_id FROM submissions WHERE id=?",Long.class,submission));
        assertEquals(tests,db.queryForObject("SELECT COUNT(*) FROM problem_test_cases WHERE problem_id=?",Long.class,chainId));
        assertEquals(references,db.queryForObject("SELECT COUNT(*) FROM reference_solutions WHERE problem_id=?",Long.class,chainId));
        var chain=db.queryForMap("SELECT description,status,visibility,requested_visibility,author_public FROM problems WHERE id=?",chainId);
        assertEquals("Preserved chain body",chain.get("description"));assertEquals("PUBLISHED",chain.get("status"));
        assertEquals("PUBLIC",chain.get("visibility"));assertEquals("PUBLIC",chain.get("requested_visibility"));assertEquals(true,chain.get("author_public"));
        assertTrue(db.queryForObject("SELECT next_number FROM problem_number_sequence WHERE id=1",Integer.class)>db.queryForObject("SELECT MAX(problem_number) FROM problems",Integer.class));
    }

    @Test void repeatedImportChecksOnlyIdentityAndPreservesAllUserEditsIncludingPrivateChain() {
        importer.importCatalogs(level1,level2);
        long count=db.queryForObject("SELECT COUNT(*) FROM problems",Long.class),history=db.queryForObject("SELECT COUNT(*) FROM problem_status_history",Long.class);
        db.update("UPDATE problems SET description='User changed body',status='ARCHIVED',visibility='PRIVATE',requested_visibility='PRIVATE',author_public=TRUE WHERE problem_number=1101");
        db.update("UPDATE problems SET problem_title='Edited ELITE title' WHERE problem_number=1176");
        db.update("UPDATE problems SET problem_title='체인 레이저 사용자 수정',status='ARCHIVED',visibility='PRIVATE',requested_visibility='PRIVATE' WHERE id=?",chainId);
        importer.importCatalogs(level1,level2);
        assertEquals(count,db.queryForObject("SELECT COUNT(*) FROM problems",Long.class));assertEquals(history,db.queryForObject("SELECT COUNT(*) FROM problem_status_history",Long.class));
        assertEquals(166,packEntries());assertEquals("User changed body",db.queryForObject("SELECT description FROM problems WHERE problem_number=1101",String.class));
        assertEquals("PRIVATE",db.queryForObject("SELECT visibility FROM problems WHERE problem_number=1101",String.class));
        assertEquals("Edited ELITE title",db.queryForObject("SELECT problem_title FROM problems WHERE problem_number=1176",String.class));
        assertEquals("ARCHIVED",db.queryForObject("SELECT status FROM problems WHERE id=?",String.class,chainId));
        assertEquals("PRIVATE",db.queryForObject("SELECT visibility FROM problems WHERE id=?",String.class,chainId));
    }

    @Test void eitherPackNumberOrTitleCollisionRefusesEverythingBeforeRestoringChain() {
        authored(1176,"Number collision "+UUID.randomUUID());
        long count=db.queryForObject("SELECT COUNT(*) FROM problems",Long.class);
        assertThrows(IllegalStateException.class,()->importer.importCatalogs(level1,level2));
        assertEquals(count,db.queryForObject("SELECT COUNT(*) FROM problems",Long.class));assertEquals(0,packEntries());
        assertEquals("PRIVATE",db.queryForObject("SELECT visibility FROM problems WHERE id=?",String.class,chainId));
        db.update("UPDATE problems SET problem_number=? WHERE problem_number=1176",temporary());
        authored(temporary(),level1.problems().getFirst().title());
        assertThrows(IllegalStateException.class,()->importer.importCatalogs(level1,level2));
        assertEquals(0,packEntries());assertEquals("ARCHIVED",db.queryForObject("SELECT status FROM problems WHERE id=?",String.class,chainId));
    }

    @Test void partialOrChangedPackIdentityRefusesWithoutReconstructingOrRepublishing() {
        db.update("INSERT INTO curriculum_problem_entries(pack_key,ordinal,problem_id,catalog_sha256) VALUES(?,1,?,?)",AlgorithmData.LEVEL1_PACK_KEY,chainId,"a".repeat(64));
        assertThrows(IllegalStateException.class,()->importer.importCatalogs(level1,level2));
        assertEquals(1,packEntries());assertEquals("PRIVATE",db.queryForObject("SELECT visibility FROM problems WHERE id=?",String.class,chainId));
    }

    @Test void changedCatalogOrChainFingerprintNeverOverwritesPreviouslyImportedContent() {
        importer.importCatalogs(level1,level2);
        db.update("UPDATE problems SET visibility='PRIVATE',requested_visibility='PRIVATE' WHERE id=?",chainId);
        var changed=new Level0Data.Catalog(level1.version(),level1.name()+" changed",level1.problems());
        assertThrows(IllegalStateException.class,()->importer.importCatalogs(changed,level2));
        db.update("UPDATE curriculum_problem_entries SET catalog_sha256=? WHERE pack_key=?","f".repeat(64),AlgorithmData.CHAIN_PACK_KEY);
        assertThrows(IllegalStateException.class,()->importer.importCatalogs(level1,level2));
        assertEquals(166,packEntries());assertEquals("PRIVATE",db.queryForObject("SELECT visibility FROM problems WHERE id=?",String.class,chainId));
    }

    @Test void theStoppedSeedAccountIsRequiredAndAnUnexpectedChainNumberIsNotMoved() {
        db.update("UPDATE users SET status='ACTIVE',password='not-a-seed-password' WHERE id=?",author);
        assertThrows(IllegalStateException.class,()->importer.importCatalogs(level1,level2));
        assertEquals("ACTIVE",db.queryForObject("SELECT status FROM users WHERE id=?",String.class,author));
        assertEquals("not-a-seed-password",db.queryForObject("SELECT password FROM users WHERE id=?",String.class,author));assertEquals(0,packEntries());
        db.update("UPDATE users SET status='STOPPED',password='!' WHERE id=?",author);
        db.update("UPDATE problems SET problem_number=? WHERE id=?",temporary(),chainId);
        assertThrows(IllegalStateException.class,()->importer.importCatalogs(level1,level2));
        assertEquals(0,packEntries());assertEquals("PRIVATE",db.queryForObject("SELECT visibility FROM problems WHERE id=?",String.class,chainId));
    }

    @Test void missingChainUsesOriginalSeedAt1100WithoutReplacingAnExistingProblemOrSeedAccount() {
        db.update("UPDATE problems SET problem_number=?,problem_title=? WHERE id=?",temporary(),"Preserved old chain "+UUID.randomUUID(),chainId);
        var preservedProblem=db.queryForMap("SELECT * FROM problems WHERE id=?",chainId);
        var preservedTests=db.queryForList("SELECT * FROM problem_test_cases WHERE problem_id=? ORDER BY id",chainId);
        var preservedReferences=db.queryForList("SELECT * FROM reference_solutions WHERE problem_id=? ORDER BY language",chainId);
        importer.importCatalogs(level1,level2);
        long fresh=db.queryForObject("SELECT id FROM problems WHERE problem_number=1100",Long.class);
        assertNotEquals(chainId,fresh);assertEquals(166,packEntries());
        assertEquals("체인 레이저",db.queryForObject("SELECT problem_title FROM problems WHERE id=?",String.class,fresh));
        assertEquals("PUBLISHED",db.queryForObject("SELECT status FROM problems WHERE id=?",String.class,fresh));
        assertEquals("PUBLIC",db.queryForObject("SELECT visibility FROM problems WHERE id=?",String.class,fresh));
        assertEquals(10,db.queryForObject("SELECT COUNT(*) FROM problem_test_cases WHERE problem_id=?",Integer.class,fresh));
        assertEquals(15,db.queryForObject("SELECT COUNT(*) FROM problem_languages WHERE problem_id=?",Integer.class,fresh));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM reference_solutions WHERE problem_id=?",Integer.class,fresh));
        assertEquals("STOPPED",db.queryForObject("SELECT status FROM users WHERE id=?",String.class,author));
        assertEquals("!",db.queryForObject("SELECT password FROM users WHERE id=?",String.class,author));
        assertEquals(preservedProblem,db.queryForMap("SELECT * FROM problems WHERE id=?",chainId));
        assertEquals(preservedTests,db.queryForList("SELECT * FROM problem_test_cases WHERE problem_id=? ORDER BY id",chainId));
        assertEquals(preservedReferences,db.queryForList("SELECT * FROM reference_solutions WHERE problem_id=? ORDER BY language",chainId));
    }

    @Test void freshChainPreflightChecksBothUniqueKeysBeforeAnySeedWrite() {
        db.update("UPDATE problems SET problem_number=?,problem_title=? WHERE id=?",temporary(),"Saved for key-lock contract "+UUID.randomUUID(),chainId);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM problems WHERE problem_number=1100 OR (title_scope=0 AND problem_title='체인 레이저')",Integer.class));
        JdbcTemplate observed=spy(db);
        var checked=new AlgorithmData(observed,new Level0Data(observed,json));
        assertNotNull(ReflectionTestUtils.invokeMethod(checked,"chainPlan"));
        var order=inOrder(observed);
        order.verify(observed).queryForList("SELECT id FROM problems WHERE problem_number=1100 FOR UPDATE");
        order.verify(observed).queryForList("SELECT id FROM problems WHERE title_scope=0 AND lower(problem_title)=lower(?) FOR UPDATE","체인 레이저");
        verify(observed,never()).update(anyString());
        assertEquals(0,packEntries());
    }

    @Test void missingChainNumberAndGlobalTitleMustBothBeUnoccupied() {
        db.update("UPDATE problems SET problem_number=?,problem_title=? WHERE id=?",temporary(),"Saved chain "+UUID.randomUUID(),chainId);
        authored(1100,"User-authored number "+UUID.randomUUID());
        assertThrows(IllegalStateException.class,()->importer.importCatalogs(level1,level2));assertEquals(0,packEntries());
        db.update("UPDATE problems SET problem_number=? WHERE problem_number=1100",temporary());
        authored(temporary(),"체인 레이저");
        // 같은 시드 작성자의 제목이 다른 번호에 있더라도 기존 문제를 1100으로 옮기지 않습니다.
        assertThrows(IllegalStateException.class,()->importer.importCatalogs(level1,level2));assertEquals(0,packEntries());
    }

    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void allThreeImportsRollBackTogetherAfterALateTransactionFailure() throws NoSuchMethodException {
        long beforeProblems=db.queryForObject("SELECT COUNT(*) FROM problems",Long.class),beforeHistory=db.queryForObject("SELECT COUNT(*) FROM problem_status_history",Long.class);
        int beforeEntries=packEntries();
        var original=db.queryForMap("SELECT p.id,p.problem_number,p.status,p.visibility FROM problems p JOIN users u ON u.id=p.author_id WHERE p.problem_title='체인 레이저' AND u.username='cobweb-system'");
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactions).executeWithoutResult(status->{
            prepare();importer.importCatalogs(level1,level2);assertEquals(166,packEntries());
            throw new IllegalStateException("Synthetic failure after both packs and chain were imported");
        }));
        assertEquals(beforeProblems,db.queryForObject("SELECT COUNT(*) FROM problems",Long.class));assertEquals(beforeHistory,db.queryForObject("SELECT COUNT(*) FROM problem_status_history",Long.class));assertEquals(beforeEntries,packEntries());
        assertEquals(original,db.queryForMap("SELECT id,problem_number,status,visibility FROM problems WHERE id=?",original.get("id")));
        assertNotNull(AlgorithmData.class.getMethod("run",org.springframework.boot.ApplicationArguments.class).getAnnotation(Transactional.class));
        assertNotNull(AlgorithmData.class.getMethod("importCatalogs",Level0Data.Catalog.class,Level0Data.Catalog.class).getAnnotation(Transactional.class));
        assertEquals(10,Level0Data.class.getAnnotation(Order.class).value());assertEquals(20,AlgorithmData.class.getAnnotation(Order.class).value());
    }
}
