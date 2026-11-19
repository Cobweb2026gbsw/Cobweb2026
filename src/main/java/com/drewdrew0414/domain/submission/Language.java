package com.drewdrew0414.domain.submission;

import java.util.*;
import org.springframework.http.HttpStatus;
import com.drewdrew0414.global.exception.ApiException;

/** 클라이언트가 파일 경로나 명령을 지정하지 못하도록 언어를 고정된 식별자로 관리합니다. */
public enum Language {
    PYTHON("Python", "main.py", false), C("C", "main.c", true), CPP("C++", "main.cpp", true),
    JAVA("Java", "Main.java", true), JAVASCRIPT("JavaScript", "main.js", false),
    TYPESCRIPT("TypeScript", "main.ts", true), CSHARP("C#", "Main.cs", true),
    GO("Go", "main.go", true), RUST("Rust", "main.rs", true), KOTLIN("Kotlin", "Main.kt", true),
    SQL("SQL (SQLite)", "query.sql", false), RUBY("Ruby", "main.rb", false), LLVM_IR("LLVM IR", "main.ll", true),
    PASCAL("Pascal (Free Pascal)", "main.pas", true), PHP("PHP", "main.php", false),
    DART("Dart", "main.dart", true), UMJUNSIK("엄랭", "main.umm", false);
    /** Bean Validation 애너테이션에 필요한 상수입니다. 회귀 테스트에서 실제 enum 개수와 일치하는지 확인합니다. */
    public static final int CATALOG_SIZE=17;
    private final String label,fileName;
    private final boolean compiled;
    Language(String label,String fileName,boolean compiled) { this.label=label;this.fileName=fileName;this.compiled=compiled; }
    public String fileName() { return fileName; }
    public boolean compiled() { return compiled; }
    public boolean supports(String mode) {
        return switch(mode) {
            // 엄랭에는 sol 함수를 정의하는 구문이 없으므로 표준 입출력 문제에만 제공합니다.
            case "FUNCTION" -> this!=SQL && this!=UMJUNSIK;
            case "SQL" -> this==SQL;
            case "STDIN" -> this!=SQL;
            default -> false;
        };
    }
    public void requireMode(String mode) {
        if(!supports(mode)) throw new ApiException(HttpStatus.BAD_REQUEST,"이 문제 형식에서는 해당 언어를 사용할 수 없습니다.");
    }
    public static Language parse(String value) {
        // 언어 필드가 없던 기존 Python 클라이언트만 호환합니다. 잘못된 값은 기본값으로 숨기지 않습니다.
        if(value==null) return PYTHON;
        try { return valueOf(value); }
        catch(IllegalArgumentException e) { throw new ApiException(HttpStatus.BAD_REQUEST,"지원하지 않는 언어입니다."); }
    }
    public static List<String> forMode(String mode) {
        return Arrays.stream(values()).filter(v->v.supports(mode)).map(Enum::name).toList();
    }
    /** 고급 과정의 기본 제한은 네이티브 실행 기준입니다. 테스트 DB와 샌드박스 모두 최대 10초입니다. */
    public int timeLimitMs(Map<String,Object> problem) {
        int base=((Number)problem.get("time_limit_ms")).intValue();
        if(!Boolean.TRUE.equals(problem.get("language_adjusted_time"))) return base;
        int halfUnits=switch(this) {
            case PYTHON, RUBY, PHP -> 4;
            case JAVA, KOTLIN, CSHARP, JAVASCRIPT, TYPESCRIPT, DART -> 3;
            default -> 2;
        };
        return (int)Math.min(10000, (base*(long)halfUnits+1)/2);
    }
    public Map<String,Object> description() {
        return Map.of("id",name(),"label",label,"compiled",compiled,
            "modes",List.of("STDIN","FUNCTION","SQL").stream().filter(this::supports).toList());
    }
}
