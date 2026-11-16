package com.drewdrew0414.domain.problem;

import com.drewdrew0414.domain.group.ContestService;
import com.drewdrew0414.domain.group.GroupService;
import com.drewdrew0414.domain.submission.Language;
import com.drewdrew0414.domain.user.entity.Role;
import com.drewdrew0414.domain.user.entity.User;
import com.drewdrew0414.global.security.CustomUserDetails;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 시작 코드는 정답이 아닌 공개 메타데이터이며 구형 요청은 이를 지우지 않아야 합니다. */
class ProblemStarterCodeTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final ContestService contests=mock(ContestService.class);
    private final ProblemService service=new ProblemService(db,new ObjectMapper(),mock(GroupService.class),contests);
    private final CustomUserDetails author=user(5);
    private static final String TEMPLATE="def sol(x: int, y: int):\n    pass\n";

    @Test void invalidAndDuplicateStarterLanguagesNeverReachStorage() {
        for(var starters:List.of(
            List.of(new ProblemRequests.StarterCode("JAVA",TEMPLATE)),
            List.of(new ProblemRequests.StarterCode("python",TEMPLATE)),
            List.of(new ProblemRequests.StarterCode("PYTHON",TEMPLATE),new ProblemRequests.StarterCode("PYTHON",TEMPLATE)))) {
            assertEquals(400,assertThrows(ResponseStatusException.class,
                ()->service.create(write(List.of("PYTHON"),starters),author)).getStatusCode().value());
        }
        verifyNoInteractions(db);
    }

    @Test void emptyNullAndOversizedUtf8StarterSourcesAreRejected() {
        var invalid=Arrays.asList(null,new ProblemRequests.StarterCode("PYTHON",null),
            new ProblemRequests.StarterCode(null,TEMPLATE),new ProblemRequests.StarterCode("PYTHON"," \n\t"),
            new ProblemRequests.StarterCode("PYTHON","한".repeat(21846)));
        for(var starter:invalid) {
            assertEquals(400,assertThrows(ResponseStatusException.class,
                ()->service.create(write(List.of("PYTHON"),Arrays.asList(starter)),author)).getStatusCode().value());
        }
        verifyNoInteractions(db);
    }

    @Test void starterListCannotExceedTheCatalogSize() {
        var starters=Collections.nCopies(Language.CATALOG_SIZE+1,new ProblemRequests.StarterCode("PYTHON",TEMPLATE));
        assertEquals(400,assertThrows(ResponseStatusException.class,
            ()->service.create(write(List.of("PYTHON"),starters),author)).getStatusCode().value());
        verifyNoInteractions(db);
    }

    @Test void exactUtf8ByteBoundaryAndAllStdinLanguagesAreAccepted() {
        lockAuthor();
        when(db.queryForObject(contains("title_scope=?"),eq(Long.class),eq(0L),eq("Starter test"))).thenReturn(1L);
        var starters=Language.forMode("STDIN").stream().map(language->
            new ProblemRequests.StarterCode(language,"한".repeat(21845)+"a")).toList();
        // 언어·시작 코드 검증을 통과하여 제목 중복 검사까지 도달해야 합니다.
        assertEquals(409,assertThrows(ResponseStatusException.class,
            ()->service.create(write(Language.forMode("STDIN"),starters),author)).getStatusCode().value());
        verify(db,never()).queryForObject(contains("next_number"),eq(Integer.class));
    }

    @Test void creationStoresPublicStarterSeparatelyAndDoesNotInventAReferenceSolution() {
        lockAuthor();
        when(db.queryForObject(contains("next_number"),eq(Integer.class))).thenReturn(1001);
        when(db.queryForObject(contains("MAX(problem_number)"),eq(Integer.class))).thenReturn(1000);
        when(db.queryForObject("SELECT id FROM problems WHERE problem_number=?",Long.class,1001)).thenReturn(77L);

        assertEquals(1001,service.create(write(List.of("PYTHON"),List.of(new ProblemRequests.StarterCode("PYTHON",TEMPLATE))),author));

        verify(db).update("INSERT INTO problem_starter_codes(problem_id,language,source_code) VALUES(?,?,?)",77L,"PYTHON",TEMPLATE);
        verify(db,never()).update(contains("INSERT INTO reference_solutions"),any(),any(),any(),any());
    }

    @Test void publicProjectionOnlyReadsExplicitStartersWithinTheExposedLanguageSet() {
        var p=problem("PUBLISHED","PUBLIC");
        p.put("execution_mode","FUNCTION"); p.put("function_abi_compatible",false);
        when(db.queryForList("SELECT * FROM problems WHERE problem_number=?",1001)).thenReturn(List.of(p));
        when(db.queryForList(contains("SELECT language FROM problem_languages"),eq(String.class),eq(77L)))
            .thenReturn(List.of("PYTHON","JAVA"));
        when(db.queryForList(contains("FROM problem_starter_codes"),eq(77L)))
            .thenReturn(List.of(Map.of("language","PYTHON","sourceCode",TEMPLATE),
                Map.of("language","JAVA","sourceCode","public class Main {}")));

        var result=service.detail(1001,null);

        assertEquals(List.of("PYTHON"),result.get("languages"));
        assertTrue(result.containsKey("functionAbi")); assertNull(result.get("functionAbi"));
        assertEquals(List.of(Map.of("language","PYTHON","sourceCode",TEMPLATE)),result.get("starterCodes"));
        assertFalse(result.containsKey("referenceSolutions")); assertFalse(result.containsKey("tests"));
        verify(db,never()).queryForList(contains("reference_solutions"),any(Object[].class));
    }

    @Test void onlyTheKnownCompatibleFunctionAbiAdvertisesItsStarterContract() {
        var p=problem("PUBLISHED","PUBLIC");
        p.put("execution_mode","FUNCTION");p.put("function_abi_compatible",true);
        when(db.queryForList("SELECT * FROM problems WHERE problem_number=?",1001)).thenReturn(List.of(p));
        assertEquals("CHAIN_LASER_V1",service.detail(1001,null).get("functionAbi"));
        p.put("execution_mode","STDIN");
        assertNull(service.detail(1001,null).get("functionAbi"));
    }

    @Test void privateEditorUsesTheSameKnownFunctionContractWithoutGuessingLegacySignatures() {
        var p=problem("DRAFT","PRIVATE");
        p.put("execution_mode","FUNCTION");p.put("function_abi_compatible",true);
        when(db.queryForList("SELECT * FROM problems WHERE problem_number=?",1001)).thenReturn(List.of(p));
        when(db.queryForObject(contains("contest_problems"),eq(Long.class),eq(77L))).thenReturn(0L);
        assertEquals("CHAIN_LASER_V1",service.edit(1001,author).get("functionAbi"));
        p.put("function_abi_compatible",false);
        assertNull(service.edit(1001,author).get("functionAbi"));
        p.put("function_abi_compatible",true);p.put("execution_mode","STDIN");
        assertNull(service.edit(1001,author).get("functionAbi"));
    }

    @Test void aPrivateProblemNeverReadsItsStarterForAnOutsider() {
        when(db.queryForList("SELECT * FROM problems WHERE problem_number=?",1001))
            .thenReturn(List.of(problem("DRAFT","PRIVATE")));
        assertEquals(404,assertThrows(ResponseStatusException.class,()->service.detail(1001,null)).getStatusCode().value());
        verify(db,never()).queryForList(contains("problem_starter_codes"),any(Object[].class));
    }

    @Test void omittedStarterFieldPreservesExistingRows() {
        editableProblem();
        when(db.queryForList("SELECT language FROM problem_starter_codes WHERE problem_id=?",String.class,77L))
            .thenReturn(List.of("PYTHON"));
        service.update(1001,write(List.of("PYTHON"),null),author);
        verify(db,never()).update("DELETE FROM problem_starter_codes WHERE problem_id=?",77L);
        verify(db,never()).update(contains("INSERT INTO problem_starter_codes"),any(),any(),any());
    }

    @Test void explicitEmptyStarterFieldClearsRowsWithoutChangingReferences() {
        editableProblem();
        service.update(1001,write(List.of("PYTHON"),List.of()),author);
        verify(db).update("DELETE FROM problem_starter_codes WHERE problem_id=?",77L);
        verify(db,never()).update("DELETE FROM reference_solutions WHERE problem_id=?",77L);
    }

    @Test void omittedStartersCannotBeStrandedOutsideANewLanguageSet() {
        editableProblem();
        when(db.queryForList("SELECT language FROM problem_starter_codes WHERE problem_id=?",String.class,77L))
            .thenReturn(List.of("PYTHON"));
        assertEquals(400,assertThrows(ResponseStatusException.class,
            ()->service.update(1001,write(List.of("JAVA"),null),author)).getStatusCode().value());
        verify(db,never()).update(contains("DELETE FROM"),any(Object[].class));
    }

    @Test void oldFullAndMinimalConstructorsLeaveStarterMetadataUnspecified() {
        var old=new ProblemRequests.Write("Title","body","in","out",null,"INITIATE",1,1000,128,
            "STDIN",List.of(),List.of(),null,null,null,null,null,null,"PUBLIC");
        var minimal=new ProblemRequests.Write("Title","body","in","out",null,"INITIATE",1,1000,128,
            "STDIN",List.of(),List.of());
        assertNull(old.starterCodes()); assertNull(minimal.starterCodes());
    }

    private static ProblemRequests.Write write(List<String> languages,List<ProblemRequests.StarterCode> starters) {
        return new ProblemRequests.Write("Starter test","body","in","out",null,"INITIATE",1,1000,128,
            "STDIN",List.of(),List.of(),languages,null,null,null,null,null,"PUBLIC",starters);
    }
    private void lockAuthor() {
        when(db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",5L)).thenReturn(List.of(Map.of("id",5L)));
    }
    private void editableProblem() {
        lockAuthor();
        when(db.queryForList("SELECT * FROM problems WHERE problem_number=?",1001))
            .thenReturn(List.of(problem("DRAFT","PRIVATE")));
        when(db.queryForObject(contains("status IN ('QUEUED','RUNNING')"),eq(Long.class),eq(77L))).thenReturn(0L);
        when(db.queryForObject(contains("contest_problems"),eq(Long.class),eq(77L))).thenReturn(0L);
    }
    private static Map<String,Object> problem(String status,String visibility) {
        var p=new HashMap<String,Object>();
        p.put("id",77L);p.put("problem_number",1001);p.put("author_id",5L);
        p.put("status",status);p.put("visibility",visibility);p.put("group_id",null);
        p.put("execution_mode","STDIN");p.put("requested_visibility","PUBLIC");
        return p;
    }
    private static CustomUserDetails user(long id) {
        var entity=User.builder().username("starter-test").password("unused").build();
        ReflectionTestUtils.setField(entity,"id",id);ReflectionTestUtils.setField(entity,"role",Role.USER);
        return new CustomUserDetails(entity);
    }
}
