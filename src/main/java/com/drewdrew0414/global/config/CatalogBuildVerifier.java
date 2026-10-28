package com.drewdrew0414.global.config;

import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * JAR에 포함될 고정 카탈로그의 순수 데이터 계약만 검사하는 빌드 진입점입니다.
 * Spring을 기동하거나 DB에 접근하거나 참고 풀이를 실행하지 않습니다.
 */
public final class CatalogBuildVerifier {
    private record Pack(int level,int count,int firstNumber,String rank) {}
    private static final List<Pack> PACKS=List.of(
        new Pack(0,100,1000,"INITIATE"),new Pack(1,75,1101,"SKILLED"),
        new Pack(2,90,1176,"ELITE"),new Pack(3,80,1266,"EXPERT"));
    private static final int CATALOG_LIMIT=32*1024*1024;

    private CatalogBuildVerifier() {}

    public static void main(String[] arguments) {
        if(arguments.length!=0)throw new IllegalArgumentException("Catalog verification accepts no arguments");
        int total=verifyResources();
        System.out.println("Curriculum catalogs verified: "+total+" problems (INITIATE=100, SKILLED=75, ELITE=90, EXPERT=80).");
    }

    /** main 리소스 클래스패스만 읽으며 성공 시 총 문제 수를 반환합니다. */
    public static int verifyResources() {
        var data=new Level0Data(null,new ObjectMapper());
        var catalogs=new ArrayList<Level0Data.Catalog>();
        for(var pack:PACKS) catalogs.add(data.readCatalog("problems/level"+pack.level()+".json",
            pack.level()==0?8*1024*1024:CATALOG_LIMIT,"Lv."+pack.level()));
        return validateCatalogs(catalogs);
    }

    /** 테스트에서도 같은 검사만 호출하며 JdbcTemplate이 없어 DB 호출은 불가능합니다. */
    static int validateCatalogs(List<Level0Data.Catalog> catalogs) {
        if(catalogs==null || catalogs.size()!=PACKS.size())throw invalid("exactly four catalogs are required");
        var data=new Level0Data(null,new ObjectMapper());
        var titles=new HashSet<String>();
        // 별도 보관/복구하는 체인 레이저의 전역 제목 공간도 선점하지 않습니다.
        titles.add("체인 레이저");
        int total=0;
        for(int index=0;index<PACKS.size();index++) {
            var pack=PACKS.get(index);var catalog=catalogs.get(index);
            data.validate(catalog,pack.count(),pack.firstNumber(),pack.rank(),"Lv."+pack.level());
            for(int ordinal=0;ordinal<pack.count();ordinal++) {
                var entry=catalog.problems().get(ordinal);
                if(!titles.add(entry.title().toLowerCase(Locale.ROOT)))throw invalid("duplicate title between catalogs");
                if(pack.level()==3 && (!("Lv.3-"+(ordinal/16+1)).equals(entry.chapter()) || entry.rankInt()!=5-ordinal/16))
                    throw invalid("EXPERT chapter or rank subdivision changed at ordinal "+entry.ordinal());
            }
            total+=pack.count();
        }
        return total;
    }

    private static IllegalStateException invalid(String reason) {
        return new IllegalStateException("Curriculum catalog build verification failed: "+reason);
    }
}
