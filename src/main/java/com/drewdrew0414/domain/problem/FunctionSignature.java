package com.drewdrew0414.domain.problem;

import com.drewdrew0414.global.exception.ApiException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** GENERIC_V1의 언어 독립 함수 계약입니다. int는 부호 있는 32비트 정수입니다. */
public record FunctionSignature(String name,List<Parameter> parameters,String returnType) {
    public record Parameter(String name,String type) {}
    public static final int MAX_JSON_BYTES=16*1024*1024;
    public static final int MAX_ARRAY_ITEMS=1_000_000;
    public static final int MAX_STRING_BYTES=16384;
    public static final Set<String> TYPES=Set.of("int","float","bool","string","int[]","string[]","int[][]","string[][]");
    private static final Set<String> RESERVED=Set.of(
        "abstract","and","array","as","assert","async","await","base","begin","bool","boolean",
        "break","byte","case","catch","char","class","const","continue","crate","data","debugger",
        "decimal","def","default","defer","del","delete","do","double","dynamic","elif","else","elseif",
        "end","enum","eval","except","export","extends","external","false","final","finally","float",
        "fn","for","foreach","from","fun","func","function","global","go","goto","if","implements",
        "import","in","inline","instanceof","int","interface","internal","is","lambda","let","long",
        "loop","main","match","mod","module","mut","namespace","native","new","nil","none","not",
        "null","object","of","operator","or","out","override","package","params","pass","private",
        "protected","public","raise","readonly","record","ref","repeat","require","return","self",
        "short","signed","sizeof","sol","solution","static","string","struct","super","switch",
        "then","this","throw","throws","trait","true","try","type","typeof","uint","ulong","union",
        "unsigned","until","use","using","val","var","virtual","void","volatile","when","while",
        "with","yield");
    // 각 언어의 하드 키워드와 Pascal의 암묵적 반환 변수도 식별자로 받지 않습니다.
    // value/length 같은 일반 매개변수와 내장 함수 이름은 허용합니다.
    private static final Set<String> PORTABLE_RESERVED=Set.copyOf(List.of((
        "result synchronized transient strictfp box dyn impl macro pub where move unsafe select chan map " +
        "fallthrough range checked unchecked fixed event implicit explicit lock stackalloc ushort extern " +
        "div shl shr xor unit program implementation constructor destructor procedure packed resourcestring " +
        "threadvar absolute assembler forward inherited interrupt otherwise overload pascal register safecall " +
        "stdcall specialize published automated property typealias vararg companion crossinline infix inner " +
        "noinline open reified sealed suspend tailrec delegate sbyte nonlocal alias rescue retry redo undef " +
        "unless alignas alignof and_eq asm auto bitand bitor char8_t char16_t char32_t compl concept consteval " +
        "constexpr constinit const_cast co_await co_return co_yield decltype dynamic_cast friend mutable " +
        "noexcept not_eq nullptr or_eq reinterpret_cast requires static_assert static_cast template thread_local " +
        "typename wchar_t xor_eq typedef restrict callable clone declare die echo empty enddeclare endfor " +
        "endforeach endif endswitch endwhile exit include include_once insteadof isset list print require_once " +
        "unlet unset covariant extension factory get hide late mixin on part set show sync"
        ).split(" ")));

    public FunctionSignature validated() {
        if(!Set.of("sol","solution").contains(name==null?"":name) || parameters==null ||
           parameters.size()>6 || !TYPES.contains(returnType==null?"":returnType)) throw invalid();
        var seen=new HashSet<String>();
        for(var p:parameters) {
            if(p==null || p.name()==null || !p.name().matches("[a-z][A-Za-z0-9_]{0,31}") ||
               p.name().toLowerCase(java.util.Locale.ROOT).endsWith("_length") ||
               RESERVED.contains(p.name().toLowerCase(java.util.Locale.ROOT)) ||
               PORTABLE_RESERVED.contains(p.name().toLowerCase(java.util.Locale.ROOT)) ||
               !seen.add(p.name().toLowerCase(java.util.Locale.ROOT)) ||
               !TYPES.contains(p.type()==null?"":p.type())) throw invalid();
        }
        return new FunctionSignature(name,List.copyOf(parameters),returnType);
    }
    public String toJson(ObjectMapper json) {return json.writeValueAsString(validated());}
    public static FunctionSignature fromJson(ObjectMapper json,Object stored) {
        if(stored==null) return null;
        try {return json.readValue(stored.toString(),FunctionSignature.class).validated();}
        catch(Exception error) {throw invalid();}
    }
    public void validateCase(ObjectMapper json,String input,String expected) {
        validateInput(json,input);
        if(!valueMatches(parse(json,expected),returnType)) throw invalidCase();
    }
    public void validateInput(ObjectMapper json,String input) {
        validated();
        JsonNode value=parse(json,input);
        if(!value.isObject() || value.size()!=1 || !value.has("args")) throw invalidCase();
        JsonNode args=value.get("args");
        if(!args.isArray() || args.size()!=parameters.size()) throw invalidCase();
        // 인자마다 한도를 새로 주지 않습니다. 여러 배열/빈 행을 조합한 우회도 같은 예산으로 제한합니다.
        int[] remaining={MAX_ARRAY_ITEMS};
        for(int i=0;i<parameters.size();i++)
            if(!valueMatches(args.get(i),parameters.get(i).type(),remaining)) throw invalidCase();
    }
    private static JsonNode parse(ObjectMapper json,String text) {
        if(text==null || !StandardCharsets.UTF_8.newEncoder().canEncode(text) ||
           text.getBytes(StandardCharsets.UTF_8).length>MAX_JSON_BYTES) throw invalidCase();
        try {
            // readTree의 뒤따르는 JSON 허용 여부와 무관하게 정확히 JSON 값 하나만 받습니다.
            try(var parser=json.reader().with(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).createParser(text)) {
                JsonNode value=json.readTree(parser);
                if(value==null || parser.nextToken()!=null) throw invalidCase();
                return value;
            }
        } catch(Exception error) {throw invalidCase();}
    }
    public static boolean valueMatches(JsonNode value,String type) {
        return valueMatches(value,type,new int[]{MAX_ARRAY_ITEMS});
    }
    private static boolean valueMatches(JsonNode value,String type,int[] remaining) {
        if(value==null || value.isNull()) return false;
        return switch(type) {
            case "int" -> value.isIntegralNumber() && value.canConvertToInt();
            case "float" -> value.isNumber() && Double.isFinite(value.doubleValue());
            case "bool" -> value.isBoolean();
            case "string" -> value.isString() &&
                StandardCharsets.UTF_8.newEncoder().canEncode(value.stringValue()) &&
                value.stringValue().chars().allMatch(c->c<=127) &&
                value.stringValue().getBytes(StandardCharsets.UTF_8).length<=MAX_STRING_BYTES;
            case "int[]","string[]","int[][]","string[][]" -> {
                if(!value.isArray() || value.size()>remaining[0]) yield false;
                remaining[0]-=value.size();
                String scalar=type.substring(0,type.length()-2);
                boolean valid=true;
                for(JsonNode item:value) if(!valueMatches(item,scalar,remaining)) {valid=false;break;}
                yield valid;
            }
            default -> false;
        };
    }
    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST,"함수 이름은 sol/solution, 인자는 고유한 식별자 최대 6개이며 지원하는 자료형을 사용해야 합니다.");
    }
    private static ApiException invalidCase() {
        return new ApiException(HttpStatus.BAD_REQUEST,"함수 입력은 {\"args\":[...]}이며 입력과 정답은 함수 자료형 및 크기 제한과 일치해야 합니다. 문자열은 ASCII 문자만 지원합니다.");
    }
}
