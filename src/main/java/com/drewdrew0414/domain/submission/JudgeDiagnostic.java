package com.drewdrew0414.domain.submission;

import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.Pattern;

/**
 * stderr 원문은 비공개 입력을 그대로 출력할 수 있으므로 저장/반환하지 않습니다.
 * 알려진 오류 유형과 제출 파일의 유효한 위치만 추출하고 설명은 고정 문구로 만듭니다.
 */
public record JudgeDiagnostic(String phase,Kind errorType,List<Location> locations) {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<String> FILES=new HashSet<>();
    static {for(var language:Language.values()) FILES.add(language.fileName());}
    public enum Kind {
        SYNTAX_ERROR("문법 오류입니다. 괄호·구분자·들여쓰기와 함수 선언 형식을 확인하세요."),
        TYPE_ERROR("자료형이 일치하지 않습니다. 인자·반환값·변수의 자료형을 확인하세요."),
        UNDEFINED_NAME("정의되지 않은 이름을 사용했습니다. 변수·함수 이름과 선언 범위를 확인하세요."),
        ARGUMENT_MISMATCH("함수 인자의 개수나 형식이 맞지 않습니다. 문제의 함수 규격을 확인하세요."),
        MISSING_ENTRYPOINT("실행 진입점이나 필수 함수가 없습니다. 문제 형식의 main 또는 지정된 함수를 확인하세요."),
        BORROW_ERROR("값의 소유권·대여 또는 수명이 올바르지 않습니다. 참조와 이동 후 사용을 확인하세요."),
        IMPORT_ERROR("사용한 모듈·패키지 또는 가져오기를 찾을 수 없습니다. 지원 라이브러리와 이름을 확인하세요."),
        COMPILATION_FAILED("컴파일에 실패했습니다. 제출 언어와 문제의 함수 규격·문법을 확인하세요."),
        DIVISION_BY_ZERO("0으로 나누는 연산이 발생했습니다. 나눗셈과 나머지 연산의 분모를 확인하세요."),
        INDEX_OUT_OF_BOUNDS("배열이나 문자열의 유효 범위를 벗어났습니다. 인덱스와 빈 값 처리를 확인하세요."),
        NULL_REFERENCE("null 또는 비어 있는 참조를 사용했습니다. 값의 존재 여부를 확인하세요."),
        ASSERTION_FAILED("코드의 검증 조건이 실패했습니다. assert 또는 명시적으로 중단한 조건을 확인하세요."),
        STACK_OVERFLOW("호출 스택 한도를 넘었습니다. 재귀 종료 조건과 호출 깊이를 확인하세요."),
        INVALID_VALUE("유효하지 않은 값 또는 변환이 사용됐습니다. 입력 처리와 값의 범위를 확인하세요."),
        ARITHMETIC_ERROR("허용되지 않는 산술 연산이 발생했습니다. 0 나눗셈과 정수 범위를 확인하세요."),
        FILE_ACCESS_ERROR("허용되지 않거나 존재하지 않는 파일에 접근했습니다. 제출 프로그램의 파일 사용을 확인하세요."),
        SQL_ERROR("SQL 실행에 실패했습니다. 쿼리 문법과 문제에서 제공한 스키마를 확인하세요."),
        PROCESS_SIGNAL("프로그램이 비정상 종료됐습니다. 잘못된 메모리 접근과 명시적 중단을 확인하세요."),
        OUTPUT_LIMIT("프로그램의 출력 한도를 넘었습니다. 반복 출력과 디버그 출력을 확인하세요."),
        RUNTIME_FAILED("프로그램 실행에 실패했습니다. 함수 규격·입력 처리와 예외 발생 가능성을 확인하세요.");
        private final String message;
        Kind(String message){this.message=message;}
        public String message(){return message;}
    }
    public record Location(String file,int line,Integer column) {}
    public JudgeDiagnostic {
        if(!Set.of("COMPILE","RUN").contains(phase) || errorType==null || locations==null || locations.size()>5)
            throw new IllegalArgumentException("Invalid safe diagnostic");
        for(var location:locations) if(location==null || !FILES.contains(location.file()) || location.line()<1 ||
                location.line()>65537 || location.column()!=null && (location.column()<1 || location.column()>65537))
            throw new IllegalArgumentException("Invalid safe diagnostic location");
        locations=List.copyOf(locations);
    }
    public Map<String,Object> publicValue() {
        return Map.of("phase",phase,"errorType",errorType.name(),"message",errorType.message(),"locations",locations);
    }
    public String toJson(){return JSON.writeValueAsString(publicValue());}
    public boolean matches(String status) {
        return "COMPILATION_ERROR".equals(status)?phase.equals("COMPILE"):
            "RUNTIME_ERROR".equals(status) && phase.equals("RUN");
    }
    /** DB 값도 신뢰하지 않으며 원문 message/임의 필드는 공개 projection에 복사하지 않습니다. */
    public static JudgeDiagnostic fromJson(Object stored,String status) {
        if(!(stored instanceof String value) || value.length()>8192) return null;
        try {
            var node=JSON.readTree(value);
            String phase=node.path("phase").asText(),kind=node.path("errorType").asText();
            var rows=node.path("locations");
            if(!rows.isArray() || rows.size()>5) return null;
            var locations=new ArrayList<Location>();
            for(var row:rows) {
                if(!row.path("line").isIntegralNumber() || !row.path("line").canConvertToInt()) return null;
                var column=row.get("column");
                if(column!=null && !column.isNull() && (!column.isIntegralNumber() || !column.canConvertToInt())) return null;
                locations.add(new Location(row.path("file").asText(),row.path("line").intValue(),
                    column==null || column.isNull()?null:column.intValue()));
            }
            var diagnostic=new JudgeDiagnostic(phase,Kind.valueOf(kind),locations);
            return diagnostic.matches(status)?diagnostic:null;
        }catch(IllegalArgumentException | tools.jackson.core.JacksonException invalid){return null;}
    }
    static JudgeDiagnostic compilation(Language language,String source,String output) {
        return create("COMPILE",language,source,output,false);
    }
    static JudgeDiagnostic runtime(Language language,String source,String output,boolean outputExceeded) {
        return create("RUN",language,source,output,outputExceeded);
    }
    private static JudgeDiagnostic create(String phase,Language language,String source,String raw,boolean exceeded) {
        // Process capture is already bounded; keep this boundary safe for direct/unit callers too.
        String text=raw==null?"":raw.substring(0,Math.min(raw.length(),131072));
        Kind kind=classify(phase,text,exceeded);
        return new JudgeDiagnostic(phase,kind,sourceLocations(language,source,text));
    }
    private static Kind classify(String phase,String raw,boolean exceeded) {
        // 범용 하네스의 종료 알림은 제출 프로그램의 ValueError가 아닙니다.
        String text=raw.lines().filter(line->!line.contains("FUNCTION solution failed"))
            .collect(java.util.stream.Collectors.joining("\n")).toLowerCase(Locale.ROOT);
        if(exceeded) return Kind.OUTPUT_LIMIT;
        if(text.contains("function output limit exceeded"))return Kind.OUTPUT_LIMIT;
        if(contains(text,"syntaxerror","syntax error","parseerror","parse error","indentationerror","taberror","unexpected token","unexpected end","expected ';'","expected ‘;’","expected expression","expecting an expression","expected a declaration")) return Kind.SYNTAX_ERROR;
        if(contains(text,"zerodivisionerror","divisionbyzeroerror","division by zero","dividebyzeroexception","/ by zero","integer divide by zero")) return Kind.DIVISION_BY_ZERO;
        if(contains(text,"indexerror","indexoutofboundsexception","indexoutofrangeexception","rangeerror","index out of range","index out of bounds")) return Kind.INDEX_OUT_OF_BOUNDS;
        if(contains(text,"nullpointerexception","nullreferenceexception","null check operator used on a null value","cannot read properties of null","cannot read properties of undefined")) return Kind.NULL_REFERENCE;
        if(contains(text,"recursionerror","stackoverflowerror","stackoverflowexception","stack overflow","maximum call stack size exceeded")) return Kind.STACK_OVERFLOW;
        if(contains(text,"assertionerror","assertion failed","assert failed","panicked at")) return Kind.ASSERTION_FAILED;
        if(contains(text,"modulenotfounderror","importerror","cannot find module","module not found","package does not exist","unresolved import","can't find unit")) return Kind.IMPORT_ERROR;
        if(contains(text,"e0382","e0499","e0502","borrowed value","does not live long enough","use of moved value")) return Kind.BORROW_ERROR;
        if(contains(text,"argumentcounterror","too few arguments","too many arguments","argument count","positional argument","no matching function for call","no overload for method")) return Kind.ARGUMENT_MISMATCH;
        if(contains(text,"undefined reference to `main'","undefined reference to 'main'","main method not found","does not contain a static 'main'","entry point","undefined: sol","has no member named 'sol'")) return Kind.MISSING_ENTRYPOINT;
        if(contains(text,"nameerror","referenceerror","cannot find symbol","cannot find name","unresolved reference","not declared in this scope","undeclared","undefined variable","undefined function","cannot find value")) return Kind.UNDEFINED_NAME;
        if(contains(text,"typeerror","incompatible types","mismatched types","type mismatch","cannot convert","not assignable","invalid conversion")) return Kind.TYPE_ERROR;
        if(contains(text,"valueerror","numberformatexception","formatexception","invalidargumentexception","invalid_argument")) return Kind.INVALID_VALUE;
        if(contains(text,"arithmeticexception","overflowerror","overflowexception","floating point exception")) return Kind.ARITHMETIC_ERROR;
        if(contains(text,"filenotfounderror","filenotfoundexception","permissionerror","permission denied")) return Kind.FILE_ACCESS_ERROR;
        if(contains(text,"sqlite3.operationalerror","sqlite3.databaseerror","sqlite3.integrityerror")) return Kind.SQL_ERROR;
        if(contains(text,"segmentation fault","sigsegv","sigabrt","abort trap","accessviolationexception","bus error")) return Kind.PROCESS_SIGNAL;
        return phase.equals("COMPILE")?Kind.COMPILATION_FAILED:Kind.RUNTIME_FAILED;
    }
    private static boolean contains(String text,String... needles){for(String needle:needles)if(text.contains(needle))return true;return false;}
    private static List<Location> sourceLocations(Language language,String source,String raw) {
        String[] lines=(source==null?"":source).split("\n",-1);
        String file=Pattern.quote(language.fileName());
        // JS 하네스는 제출 코드를 첫 줄부터 그대로 붙입니다. 다른 변환 언어의 행은 추측하지 않습니다.
        if(language==Language.JAVASCRIPT)file="(?:"+file+"|/tmp/generic_harness\\.js|generic_harness\\.js)";
        // Only the known submission filename is returned, never arbitrary directories/harness names.
        String prefix="(?:^|[\\s\\\"'(<])(?:file://)?(?:/source/|/work/)?"+file;
        var colon=Pattern.compile(prefix+"(?::|\\()(\\d{1,7})(?:[:,](\\d{1,7}))?(?:\\)|\\b)",Pattern.MULTILINE).matcher(raw);
        var python=Pattern.compile(prefix+"[\\\"']?,? (?:on )?line (\\d{1,7})\\b",Pattern.MULTILINE).matcher(raw);
        var locations=new LinkedHashSet<Location>();
        for(var matcher:List.of(colon,python)) while(matcher.find() && locations.size()<5) {
            int line=Integer.parseInt(matcher.group(1));
            if(line<1 || line>lines.length || line>65537) continue;
            Integer column=matcher.groupCount()>1 && matcher.group(2)!=null?Integer.valueOf(matcher.group(2)):null;
            if(column!=null && (column<1 || column>lines[line-1].length()+1)) column=null;
            locations.add(new Location(language.fileName(),line,column));
        }
        return List.copyOf(locations);
    }
}
