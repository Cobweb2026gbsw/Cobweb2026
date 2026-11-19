package com.drewdrew0414.domain.problem;

/** 카탈로그 번호와 최초 등록 원장의 범위입니다. 문제 공개 여부는 조회 시 DB에서 확인합니다. */
public enum CurriculumLevel {
    INITIATE(0,1000,100,20), SKILLED(1,1101,75,15), ELITE(2,1176,90,15),
    EXPERT(3,1266,80,16), LEGEND(4,1346,50,10), MYTHIC(5,1396,35,7), ABSOLUTE(6,1431,20,5);

    public final int level, firstNumber, count, chapterSize;

    CurriculumLevel(int level,int firstNumber,int count,int chapterSize) {
        this.level=level;
        this.firstNumber=firstNumber;
        this.count=count;
        this.chapterSize=chapterSize;
    }
    public String label() {return "Lv."+level;}
    public String packKey() {return "level"+level+"-v1";}
    public String resource() {return "problems/level"+level+".json";}
    public boolean advanced() {return level>=4;}

    public void validateChapter(int ordinal,String chapter,int rankInt) {
        if(level<3) return;
        int index=(ordinal-1)/chapterSize;
        int rank=this==ABSOLUTE && ordinal==count ? 1 : 5-index;
        if(!(label()+"-"+(index+1)).equals(chapter) || rankInt!=rank) {
            throw new IllegalStateException(label()+" chapter or rank mismatch at ordinal "+ordinal);
        }
    }
}
