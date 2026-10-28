package com.drewdrew0414.global.config;

import com.drewdrew0414.domain.submission.Language;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** DB/Docker 없이 순번·랭크·ABI·이전 팩 불변성과 기동 경계를 확인합니다. */
class ExpertCatalogContractTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final ObjectMapper json=new ObjectMapper();
    private final Level0Data catalogs=new Level0Data(db,json);
    private final AlgorithmData importer=new AlgorithmData(db,catalogs);

    @Test void eightyExpertProblemsUseFiveSixteenProblemChaptersAndTheExistingFifteenLanguageAbi() {
        var expert=importer.readLevel3Catalog();
        catalogs.validate(expert,80,1266,"EXPERT","Lv.3");
        var allTitles=new HashSet<String>();
        for(var catalog:new Level0Data.Catalog[]{catalogs.readCatalog(),importer.readLevel1Catalog(),importer.readLevel2Catalog(),expert})
            for(var problem:catalog.problems()) assertTrue(allTitles.add(problem.title().toLowerCase(Locale.ROOT)),problem.title());
        assertEquals(15,Language.forMode("FUNCTION").size());
        assertFalse(Language.forMode("FUNCTION").contains("SQL"));
        assertFalse(Language.forMode("FUNCTION").contains("UMJUNSIK"));
        for(int i=0;i<80;i++) {
            var entry=expert.problems().get(i);
            assertEquals(i+1,entry.ordinal());assertEquals(1266+i,entry.number());
            assertEquals("EXPERT",entry.rankType());assertEquals(5-i/16,entry.rankInt());
            assertEquals("Lv.3-"+(i/16+1),entry.chapter());
            assertTrue(entry.examples().size()>=2,entry.title());
            assertTrue(entry.tests().size()>=2,entry.title());
            assertNotNull(entry.functionSignature().validated());
        }
        verifyNoInteractions(db);
    }

    @Test void addingExpertDoesNotChangeTheEarlierCatalogBytes() throws Exception {
        assertEquals("90eaea58e0a9714c70dfb40ca9e7a51ff37dcc88e7fb651d3d74e75deec3cecf",sha256("problems/level1.json"));
        assertEquals("d771d1a7533b3305da91d46ab716fc274c2263b6c8d30ce2c5db9268dbc1d73c",sha256("problems/level2.json"));
        verifyNoInteractions(db);
    }

    @Test void startupUsesTheThreePackTransactionAndRetainsTheTwoPackCompatibilityMethod() throws Exception {
        var watched=spy(importer);
        var skilled=importer.readLevel1Catalog();var elite=importer.readLevel2Catalog();var expert=importer.readLevel3Catalog();
        doReturn(skilled).when(watched).readLevel1Catalog();doReturn(elite).when(watched).readLevel2Catalog();doReturn(expert).when(watched).readLevel3Catalog();
        doNothing().when(watched).importCatalogs(skilled,elite,expert);
        watched.run(null);
        verify(watched).importCatalogs(skilled,elite,expert);
        verify(watched,never()).importCatalogs(any(),any());
        assertNotNull(AlgorithmData.class.getMethod("run",ApplicationArguments.class).getAnnotation(Transactional.class));
        assertNotNull(AlgorithmData.class.getMethod("importCatalogs",Level0Data.Catalog.class,Level0Data.Catalog.class).getAnnotation(Transactional.class));
        assertNotNull(AlgorithmData.class.getMethod("importCatalogs",Level0Data.Catalog.class,Level0Data.Catalog.class,Level0Data.Catalog.class).getAnnotation(Transactional.class));
        verifyNoInteractions(db);
    }

    @Test void aDuplicateBetweenAnyCatalogsIsRejectedBeforeDatabaseAccess() {
        var skilled=importer.readLevel1Catalog();var elite=importer.readLevel2Catalog();var expert=importer.readLevel3Catalog();
        var entries=new ArrayList<>(expert.problems());var original=entries.getFirst();
        entries.set(0,changed(original,skilled.problems().getFirst().title(),original.chapter(),original.rankInt()));
        assertThrows(IllegalStateException.class,()->importer.importCatalogs(skilled,elite,new Level0Data.Catalog(1,expert.name(),entries)));
        verifyNoInteractions(db);
    }

    @Test void expertChapterOrRankSubdivisionCannotBeSilentlyReassigned() {
        var skilled=importer.readLevel1Catalog();var elite=importer.readLevel2Catalog();var expert=importer.readLevel3Catalog();
        var entries=new ArrayList<>(expert.problems());var original=entries.getFirst();
        entries.set(0,changed(original,original.title(),"Lv.3-2",original.rankInt()));
        assertThrows(IllegalStateException.class,()->importer.importCatalogs(skilled,elite,new Level0Data.Catalog(1,expert.name(),entries)));
        entries.set(0,changed(original,original.title(),original.chapter(),4));
        assertThrows(IllegalStateException.class,()->importer.importCatalogs(skilled,elite,new Level0Data.Catalog(1,expert.name(),entries)));
        verifyNoInteractions(db);
    }

    private static Level0Data.Entry changed(Level0Data.Entry p,String title,String chapter,int rankInt) {
        return new Level0Data.Entry(p.ordinal(),p.number(),chapter,title,p.description(),p.inputDescription(),p.outputDescription(),p.constraints(),p.rankType(),rankInt,p.timeLimitMs(),p.memoryLimitMb(),p.functionSignature(),p.tags(),p.examples(),p.tests(),p.referenceSolution());
    }
    private static String sha256(String resource) throws Exception {
        try(var stream=new ClassPathResource(resource).getInputStream()) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
        }
    }
}
