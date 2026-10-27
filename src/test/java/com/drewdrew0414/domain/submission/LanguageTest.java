package com.drewdrew0414.domain.submission;

import com.drewdrew0414.domain.problem.ProblemRequests;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 언어 카탈로그·실행 형식·입력 DTO 상한이 함께 확장되는지 확인합니다. */
class LanguageTest {
    @Test void catalogCountAndBeanValidationCapsMatchTheEnum() throws Exception {
        assertEquals(17,Language.values().length);
        assertEquals(Language.values().length,Language.CATALOG_SIZE);
        for(String field:List.of("languages","referenceSolutions","starterCodes"))
            assertEquals(Language.values().length,
                ProblemRequests.Write.class.getDeclaredField(field).getAnnotation(Size.class).max());
    }

    @Test void newGeneralLanguagesSupportBothStdinAndFunctionButNotSql() {
        for(Language language:List.of(Language.PASCAL,Language.PHP,Language.DART)) {
            assertTrue(language.supports("STDIN"));
            assertTrue(language.supports("FUNCTION"));
            assertFalse(language.supports("SQL"));
        }
        assertEquals(16,Language.forMode("STDIN").size());
        assertEquals(15,Language.forMode("FUNCTION").size());
        assertEquals(List.of("SQL"),Language.forMode("SQL"));
    }

    @Test void umjunsikOnlyAdvertisesItsRealStdinInterface() {
        assertEquals("엄랭",Language.UMJUNSIK.description().get("label"));
        assertEquals(List.of("STDIN"),Language.UMJUNSIK.description().get("modes"));
        assertTrue(Language.UMJUNSIK.supports("STDIN"));
        assertThrows(ResponseStatusException.class,()->Language.UMJUNSIK.requireMode("FUNCTION"));
        assertThrows(ResponseStatusException.class,()->Language.UMJUNSIK.requireMode("SQL"));
    }

    @Test void fixedSourceNamesAndCompileFlagsMatchTheNewToolchains() {
        assertEquals("main.pas",Language.PASCAL.fileName());
        assertEquals("main.php",Language.PHP.fileName());
        assertEquals("main.dart",Language.DART.fileName());
        assertEquals("main.umm",Language.UMJUNSIK.fileName());
        assertTrue(Language.PASCAL.compiled());
        assertTrue(Language.DART.compiled());
        assertFalse(Language.PHP.compiled());
        assertFalse(Language.UMJUNSIK.compiled());
        var seen=new HashSet<String>();
        for(Language language:Language.values()) {
            assertTrue(seen.add(language.fileName()));
            assertFalse(language.fileName().contains("/"));
            assertFalse(language.fileName().contains("\\"));
            assertEquals(language,Language.parse(language.name()));
        }
    }

    @Test void invalidClientLanguageCannotBecomeACommandOrPath() {
        assertEquals(Language.PYTHON,Language.parse(null));
        for(String invalid:List.of("PASCAL;sh","php","../main.dart","UMJUNSIK\n",""))
            assertThrows(ResponseStatusException.class,()->Language.parse(invalid));
    }

    @Test void freshChainLaserSeedIncludesExactlyEveryFunctionLanguage() throws Exception {
        try(var resource=LanguageTest.class.getResourceAsStream("/demo/chain-laser.sql")) {
            assertNotNull(resource,"the fresh DB seed must be packaged");
            String sql=new String(resource.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            var block=java.util.regex.Pattern.compile(
                "SELECT @chain_laser_problem_id, language FROM \\((.*?)\\) supported_languages;",
                java.util.regex.Pattern.DOTALL).matcher(sql);
            assertTrue(block.find(),"the seed language insert must remain explicit");
            var ids=java.util.regex.Pattern.compile("SELECT\\s+'([A-Z0-9_]+)'")
                .matcher(block.group(1)).results().map(match->match.group(1)).toList();
            assertEquals(15,ids.size());
            assertEquals(ids.size(),new HashSet<>(ids).size(),"seed languages must not repeat");
            assertEquals(new HashSet<>(Language.forMode("FUNCTION")),new HashSet<>(ids));
        }
    }
}
