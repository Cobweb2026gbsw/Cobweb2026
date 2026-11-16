package com.drewdrew0414.global.config;

import com.drewdrew0414.domain.problem.FunctionSignature;
import com.drewdrew0414.global.exception.ApiException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** DB·Spring 컨텍스트·외부 프로세스 없이 빌드 전 검증의 성공/실패를 확인합니다. */
class CatalogBuildVerifierTest {
    private List<Level0Data.Catalog> catalogs() {
        var data=new Level0Data(null,new ObjectMapper());
        var result=new ArrayList<Level0Data.Catalog>();
        for(int level=0;level<4;level++)result.add(data.readCatalog("problems/level"+level+".json",32*1024*1024,"Lv."+level));
        return result;
    }
    private List<Level0Data.Catalog> replaced(int index,Level0Data.Entry entry) {
        var result=new ArrayList<>(catalogs());var original=result.get(3);
        var entries=new ArrayList<>(original.problems());entries.set(index,entry);
        result.set(3,new Level0Data.Catalog(original.version(),original.name(),entries));
        return result;
    }
    private Level0Data.Entry changed(Level0Data.Entry p,String title,String chapter,int rankInt,FunctionSignature signature) {
        return new Level0Data.Entry(p.ordinal(),p.number(),chapter,title,p.description(),p.inputDescription(),p.outputDescription(),p.constraints(),p.rankType(),rankInt,p.timeLimitMs(),p.memoryLimitMb(),signature,p.tags(),p.examples(),p.tests(),p.referenceSolution());
    }

    @Test void packagedCatalogsValidateWithoutDatabaseOrSpringStartup() {
        assertEquals(345,CatalogBuildVerifier.verifyResources());
        assertEquals(345,CatalogBuildVerifier.validateCatalogs(catalogs()));
    }

    @Test void reservedParameterThatPreviouslyStoppedWebStartupFailsBuildValidation() {
        var original=catalogs().get(3).problems().get(29);
        var signature=new FunctionSignature("sol",List.of(new FunctionSignature.Parameter("box","int[][]")),"int");
        var broken=changed(original,original.title(),original.chapter(),original.rankInt(),signature);
        assertThrows(ApiException.class,()->CatalogBuildVerifier.validateCatalogs(replaced(29,broken)));
    }

    @Test void collisionsWithEarlierPackOrReservedChainTitleAreRejected() {
        var all=catalogs();var original=all.get(3).problems().getFirst();
        var duplicate=changed(original,all.getFirst().problems().getFirst().title(),original.chapter(),original.rankInt(),original.functionSignature());
        assertThrows(IllegalStateException.class,()->CatalogBuildVerifier.validateCatalogs(replaced(0,duplicate)));
        var chain=changed(original,"체인 레이저",original.chapter(),original.rankInt(),original.functionSignature());
        assertThrows(IllegalStateException.class,()->CatalogBuildVerifier.validateCatalogs(replaced(0,chain)));
    }

    @Test void incorrectExpertChapterAndSubdivisionAreRejectedBeforePackaging() {
        var original=catalogs().get(3).problems().getFirst();
        var chapter=changed(original,original.title(),"Lv.3-2",original.rankInt(),original.functionSignature());
        assertThrows(IllegalStateException.class,()->CatalogBuildVerifier.validateCatalogs(replaced(0,chapter)));
        var rank=changed(original,original.title(),original.chapter(),4,original.functionSignature());
        assertThrows(IllegalStateException.class,()->CatalogBuildVerifier.validateCatalogs(replaced(0,rank)));
    }

    @Test void missingPackAndUnexpectedMainArgumentsAreRejected() {
        assertThrows(IllegalStateException.class,()->CatalogBuildVerifier.validateCatalogs(catalogs().subList(0,3)));
        assertThrows(IllegalArgumentException.class,()->CatalogBuildVerifier.main(new String[]{"unexpected"}));
    }
}
