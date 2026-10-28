package com.drewdrew0414;

import com.drewdrew0414.domain.problem.FunctionSignature;
import com.drewdrew0414.domain.problem.ProblemRequests;
import com.drewdrew0414.domain.problem.ProblemService;
import com.drewdrew0414.domain.user.entity.Role;
import com.drewdrew0414.domain.user.entity.User;
import com.drewdrew0414.global.config.Level0Data;
import com.drewdrew0414.global.security.CustomUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 전용 테스트 DB의 각 테스트를 롤백하며 실제 PostgreSQL FK와 JSON 저장을 검증합니다. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
class Level0DataIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired ProblemService problems;
    private Level0Data importer;
    private Level0Data.Catalog catalog;
    private long chainId,author;

    @BeforeEach void setup() {
        importer=new Level0Data(db,json);
        catalog=importer.readCatalog();
        // 다른 통합 테스트가 남긴 번호는 이 테스트 트랜잭션에서만 비워두며 종료 때 원래대로 복원됩니다.
        int temporary=db.queryForObject("SELECT GREATEST(2000000,COALESCE(MAX(problem_number),999)+1000) FROM problems",Integer.class);
        for(var row:db.queryForList("SELECT id FROM problems WHERE problem_number BETWEEN 1001 AND 1099"))
            db.update("UPDATE problems SET problem_number=? WHERE id=?",temporary++,row.get("id"));
        var chain=db.queryForMap("""
            SELECT p.id,p.author_id FROM problems p JOIN users u ON u.id=p.author_id
            WHERE p.problem_number=1000 AND p.problem_title='체인 레이저' AND u.username='cobweb-system'
            """);
        chainId=((Number)chain.get("id")).longValue();
        author=((Number)chain.get("author_id")).longValue();
    }
    @Test void importsExactly100PublicInitiateFunctionsAndPreservesChainSubmissionsAndAuthoredProblems() {
        long existingTests=db.queryForObject("SELECT COUNT(*) FROM problem_test_cases WHERE problem_id=?",Long.class,chainId);
        long submission=db.queryForObject("INSERT INTO submissions(user_id,problem_id,language,source_code,status) VALUES(?,?,'PYTHON','old answer','WRONG_ANSWER') RETURNING id",Long.class,author,chainId);
        long unrelated=db.queryForObject("INSERT INTO problems(problem_number,problem_title,description,input_description,output_description,author_id) VALUES(1900000,?,'body','input','output',?) RETURNING id",Long.class,
            "Unrelated "+UUID.randomUUID(),author);

        importer.importCatalog(catalog);

        assertEquals(100,db.queryForObject("""
            SELECT COUNT(*) FROM problems WHERE problem_number BETWEEN 1000 AND 1099 AND rank_type='INITIATE'
                AND execution_mode='FUNCTION' AND status='PUBLISHED' AND visibility='PUBLIC'
                AND group_id IS NULL AND author_public=FALSE AND function_signature IS NOT NULL
            """,Integer.class));
        assertEquals(100,db.queryForObject("SELECT COUNT(*) FROM curriculum_problem_entries WHERE pack_key=?",Integer.class,Level0Data.PACK_KEY));
        assertEquals(1500,db.queryForObject("""
            SELECT COUNT(*) FROM problem_languages l JOIN curriculum_problem_entries e ON e.problem_id=l.problem_id WHERE e.pack_key=?
            """,Integer.class,Level0Data.PACK_KEY));
        assertEquals(100,db.queryForObject("""
            SELECT COUNT(DISTINCT t.problem_id) FROM problem_test_cases t JOIN curriculum_problem_entries e ON e.problem_id=t.problem_id
            WHERE e.pack_key=? AND t.is_active=TRUE
            """,Integer.class,Level0Data.PACK_KEY));
        assertEquals(100,db.queryForObject("""
            SELECT COUNT(*) FROM reference_solutions r JOIN curriculum_problem_entries e ON e.problem_id=r.problem_id WHERE e.pack_key=?
            """,Integer.class,Level0Data.PACK_KEY));
        var archived=db.queryForMap("SELECT problem_number,status,visibility,requested_visibility FROM problems WHERE id=?",chainId);
        assertTrue(((Number)archived.get("problem_number")).intValue()>=1100);
        assertEquals("ARCHIVED",archived.get("status"));assertEquals("PRIVATE",archived.get("visibility"));
        assertEquals("PRIVATE",archived.get("requested_visibility"));
        assertEquals(existingTests,db.queryForObject("SELECT COUNT(*) FROM problem_test_cases WHERE problem_id=?",Long.class,chainId));
        assertEquals(chainId,db.queryForObject("SELECT problem_id FROM submissions WHERE id=?",Long.class,submission));
        assertEquals(1900000,db.queryForObject("SELECT problem_number FROM problems WHERE id=?",Integer.class,unrelated));
        int maximum=db.queryForObject("SELECT MAX(problem_number) FROM problems",Integer.class);
        assertTrue(db.queryForObject("SELECT next_number FROM problem_number_sequence WHERE id=1",Integer.class)>maximum);
        for(int number=1000;number<=1099;number++) {
            var detail=problems.detail(number,null);
            assertEquals("GENERIC_V1",detail.get("functionAbi"));
            assertNotNull(detail.get("functionSignature"));
            assertEquals(15,((List<?>)detail.get("languages")).size());
            assertFalse(detail.containsKey("tests"));assertFalse(detail.containsKey("referenceSolutions"));
            assertFalse(detail.containsKey("author_id"));
        }
    }
    @Test void secondImportPreservesUserEditsAndDoesNotDuplicateRows() {
        importer.importCatalog(catalog);
        long count=db.queryForObject("SELECT COUNT(*) FROM problems",Long.class);
        long histories=db.queryForObject("SELECT COUNT(*) FROM problem_status_history",Long.class);
        db.update("UPDATE problems SET description='Edited after import',visibility='PRIVATE',requested_visibility='PRIVATE' WHERE problem_number=1001");
        importer.importCatalog(catalog);
        assertEquals(count,db.queryForObject("SELECT COUNT(*) FROM problems",Long.class));
        assertEquals(histories,db.queryForObject("SELECT COUNT(*) FROM problem_status_history",Long.class));
        assertEquals("Edited after import",db.queryForObject("SELECT description FROM problems WHERE problem_number=1001",String.class));
        assertEquals("PRIVATE",db.queryForObject("SELECT visibility FROM problems WHERE problem_number=1001",String.class));
    }
    @Test void anAuthoredNumberOrTitleAbortsBeforeArchivingChainOrInsertingAnyCurriculumRows() {
        db.update("INSERT INTO problems(problem_number,problem_title,description,input_description,output_description,author_id) VALUES(1001,?,'body','input','output',?)",
            "Number collision "+UUID.randomUUID(),author);
        long count=db.queryForObject("SELECT COUNT(*) FROM problems",Long.class);
        assertThrows(IllegalStateException.class,()->importer.importCatalog(catalog));
        assertEquals(count,db.queryForObject("SELECT COUNT(*) FROM problems",Long.class));
        assertEquals(1000,db.queryForObject("SELECT problem_number FROM problems WHERE id=?",Integer.class,chainId));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM curriculum_problem_entries WHERE pack_key=?",Integer.class,Level0Data.PACK_KEY));
        db.update("UPDATE problems SET problem_number=1900001 WHERE problem_number=1001");
        db.update("INSERT INTO problems(problem_number,problem_title,description,input_description,output_description,author_id) VALUES(1900002,?,'body','input','output',?)",
            catalog.problems().getFirst().title(),author);
        assertThrows(IllegalStateException.class,()->importer.importCatalog(catalog));
        assertEquals(1000,db.queryForObject("SELECT problem_number FROM problems WHERE id=?",Integer.class,chainId));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM curriculum_problem_entries WHERE pack_key=?",Integer.class,Level0Data.PACK_KEY));
    }
    @Test void partialIdentityRefusesAnImportWithoutReconstructingOrOverwritingIt() {
        db.update("INSERT INTO curriculum_problem_entries(pack_key,ordinal,problem_id,catalog_sha256) VALUES(?,1,?,?)",
            Level0Data.PACK_KEY,chainId,"a".repeat(64));
        assertThrows(IllegalStateException.class,()->importer.importCatalog(catalog));
        assertEquals(1000,db.queryForObject("SELECT problem_number FROM problems WHERE id=?",Integer.class,chainId));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM curriculum_problem_entries WHERE pack_key=?",Integer.class,Level0Data.PACK_KEY));
    }
    @Test void aRealAccountUsingTheSeedNameIsNeitherOverwrittenNorAssignedNewProblems() {
        db.update("UPDATE users SET status='ACTIVE',password='real-user-credential' WHERE id=?",author);
        assertThrows(IllegalStateException.class,()->importer.importCatalog(catalog));
        assertEquals("ACTIVE",db.queryForObject("SELECT status FROM users WHERE id=?",String.class,author));
        assertEquals("real-user-credential",db.queryForObject("SELECT password FROM users WHERE id=?",String.class,author));
        assertEquals(1000,db.queryForObject("SELECT problem_number FROM problems WHERE id=?",Integer.class,chainId));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM curriculum_problem_entries WHERE pack_key=?",Integer.class,Level0Data.PACK_KEY));
    }
    @Test void genericMetadataIsPersistedPreservedForOldClientsAndClearedWhenSwitchingMode() {
        CustomUserDetails owner=owner();
        var signature=new FunctionSignature("sol",List.of(new FunctionSignature.Parameter("value","int")),"int");
        String title="Generic contract "+UUID.randomUUID();
        int number=problems.create(write(title,"FUNCTION",signature),owner);
        assertEquals(signature,problems.edit(number,owner).get("functionSignature"));
        problems.update(number,write(title,"FUNCTION",null),owner);
        assertEquals(signature,problems.edit(number,owner).get("functionSignature"));
        problems.update(number,write(title,"STDIN",null),owner);
        assertNull(db.queryForObject("SELECT function_signature FROM problems WHERE problem_number=?",String.class,number));
        assertNull(problems.edit(number,owner).get("functionSignature"));
        assertNull(problems.edit(number,owner).get("functionAbi"));
    }
    private ProblemRequests.Write write(String title,String mode,FunctionSignature signature) {
        return new ProblemRequests.Write(title,"Body","Input","Output",null,"INITIATE",1,1000,128,mode,
            List.of(new ProblemRequests.Example("{\"args\":[1]}","1","example")),
            List.of(new ProblemRequests.TestCase("{\"args\":[1]}","1")),List.of("PYTHON"),List.of(),null,null,
            List.of(),null,"PRIVATE",List.of(),false,signature);
    }
    private CustomUserDetails owner() {
        String name="lv"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        db.update("INSERT INTO users(username,password) VALUES(?,'test-only')",name);
        var entity=User.builder().username(name).password("test-only").build();
        ReflectionTestUtils.setField(entity,"id",db.queryForObject("SELECT id FROM users WHERE username=?",Long.class,name));
        ReflectionTestUtils.setField(entity,"role",Role.USER);
        return new CustomUserDetails(entity);
    }
}
