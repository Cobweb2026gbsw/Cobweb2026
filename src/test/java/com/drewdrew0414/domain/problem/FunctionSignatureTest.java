package com.drewdrew0414.domain.problem;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FunctionSignatureTest {
    private final ObjectMapper json=new ObjectMapper();
    private FunctionSignature signature(String inputType,String outputType) {
        return new FunctionSignature("sol",List.of(new FunctionSignature.Parameter("value",inputType)),outputType);
    }
    @Test void sixPortableTypesValidateAndThePublicContractRoundTrips() {
        for(var pair:List.of(new String[]{"int","-2147483648"},new String[]{"float","1.25"},
            new String[]{"bool","true"},new String[]{"string","\"Ab c-012!?\""},
            new String[]{"int[]","[1,0,-7]"},new String[]{"string[]","[\"a\",\"\",\" z\"]"})) {
            var signature=signature(pair[0],pair[0]);
            signature.validateCase(json,"{\"args\":["+pair[1]+"]}",pair[1]);
            assertEquals(signature,FunctionSignature.fromJson(json,signature.toJson(json)));
        }
        var noArguments=new FunctionSignature("solution",List.of(),"string");
        noArguments.validateCase(json,"{\"args\":[]}","\"Hello World\"");
    }
    @Test void identifiersArePortableUniqueAndCannotInjectSourceCode() {
        for(var signature:List.of(
            new FunctionSignature("other",List.of(),"int"),
            new FunctionSignature("sol",List.of(new FunctionSignature.Parameter("x);system(\"id\");","int")),"int"),
            new FunctionSignature("sol",List.of(new FunctionSignature.Parameter("class","int")),"int"),
            new FunctionSignature("sol",List.of(new FunctionSignature.Parameter("result_length","int")),"int[]"),
            new FunctionSignature("sol",List.of(new FunctionSignature.Parameter("value_length","int")),"int"),
            new FunctionSignature("sol",List.of(new FunctionSignature.Parameter("n","int"),new FunctionSignature.Parameter("n","int")),"int"),
            new FunctionSignature("sol",List.of(new FunctionSignature.Parameter("foo","int"),new FunctionSignature.Parameter("fOO","int")),"int"),
            new FunctionSignature("sol",List.of(new FunctionSignature.Parameter("value","long")),"int"),
            new FunctionSignature("sol",List.of(),"int[][][]"),
            new FunctionSignature("sol",java.util.Collections.nCopies(5,new FunctionSignature.Parameter("value","int")),"int")))
            assertEquals(400,assertThrows(ResponseStatusException.class,signature::validated).getStatusCode().value());
    }
    @Test void argsShapeNumericBoundariesAndExpectedTypesAreStrict() {
        var signature=signature("int","int");
        for(String input:List.of("{}","[]","{\"args\":null}","{\"args\":[]}","{\"args\":[1,2]}",
            "{\"args\":[1],\"extra\":true}","{\"args\":[1],\"args\":[2]}","{\"args\":[2147483648]}","{\"args\":[true]}",
            "{\"args\":[1.0]}","{\"args\":[null]}","{\"args\":[1]} {}"))
            assertThrows(ResponseStatusException.class,()->signature.validateCase(json,input,"1"),input);
        for(String expected:List.of("true","1.0","null","[1]","\"1\"","2147483648","1 2"))
            assertThrows(ResponseStatusException.class,()->signature.validateCase(json,"{\"args\":[1]}",expected),expected);
        assertThrows(ResponseStatusException.class,()->signature("float","float").validateCase(json,"{\"args\":[1e9999]}","0"));
        assertThrows(ResponseStatusException.class,()->signature("bool","bool").validateCase(json,"{\"args\":[0]}","false"));
    }
    @Test void additionalHardKeywordsAndPascalImplicitResultAreRejectedAcrossLanguages() {
        for(String name:List.of("result","synchronized","transient","strictfp","impl","where","select",
            "checked","procedure","constructor","resourcestring","typealias","suspend","typedef","echo")) {
            var signature=new FunctionSignature("sol",List.of(new FunctionSignature.Parameter(name,"int")),"int");
            assertThrows(ResponseStatusException.class,signature::validated,name);
        }
        new FunctionSignature("sol",List.of(new FunctionSignature.Parameter("value","int"),
            new FunctionSignature.Parameter("length","int")),"int").validated();
    }
    @Test void stringsArraysAndJsonHaveExplicitPortableSizeLimits() {
        var stringSignature=signature("string","string");
        assertThrows(ResponseStatusException.class,()->stringSignature.validateCase(json,"{\"args\":[\"가\"]}","\"a\""));
        stringSignature.validateCase(json,"{\"args\":[\"a\\u0000b\\n\"]}","\"a\"");
        String tooLong=json.writeValueAsString("a".repeat(FunctionSignature.MAX_STRING_BYTES+1));
        assertThrows(ResponseStatusException.class,()->stringSignature.validateCase(json,"{\"args\":["+tooLong+"]}","\"\""));
        String hugeArray="["+String.join(",",java.util.Collections.nCopies(FunctionSignature.MAX_ARRAY_ITEMS+1,"1"))+"]";
        assertThrows(ResponseStatusException.class,()->signature("int[]","int").validateCase(json,"{\"args\":["+hugeArray+"]}","0"));
        String hugeJson=" ".repeat(FunctionSignature.MAX_JSON_BYTES)+"{\"args\":[1]}";
        assertThrows(ResponseStatusException.class,()->signature("int","int").validateCase(json,hugeJson,"0"));
        assertThrows(ResponseStatusException.class,()->signature("string[]","string[]").validateCase(json,"{\"args\":[[\"a\",1]]}","[]"));
    }
    @Test void nestedArraysAreRaggedPortableAndStrictlyTyped() {
        for(String kind:List.of("int[][]","string[][]")) {
            String value=kind.equals("int[][]")?"[[1,-2],[],[2147483647]]":"[[\"a\\u0000b\"],[],[\"\",\" z\"]]";
            signature(kind,kind).validateCase(json,"{\"args\":["+value+"]}",value);
            signature(kind,kind).validateCase(json,"{\"args\":[[]]}","[]");
        }
        for(String value:List.of("[1]","[[true]]","[[null]]","[[[1]]]","[[2147483648]]"))
            assertThrows(ResponseStatusException.class,()->signature("int[][]","int").validateCase(json,"{\"args\":["+value+"]}","0"));
        assertThrows(ResponseStatusException.class,()->signature("string[][]","int").validateCase(json,"{\"args\":[[[\"가\"]]]}","0"));
    }
    @Test void millionArraySlotsAreAcceptedButAggregateArgumentsAndEmptyRowsCannotBypassBudget() {
        String million="["+"0,".repeat(999999)+"0]";
        signature("int[]","int").validateCase(json,"{\"args\":["+million+"]}","0");
        var two=new FunctionSignature("sol",List.of(new FunctionSignature.Parameter("a","int[]"),new FunctionSignature.Parameter("b","int[]")),"int");
        assertThrows(ResponseStatusException.class,()->two.validateCase(json,"{\"args\":["+million+",[1]]}","0"));
        assertThrows(ResponseStatusException.class,()->signature("int[][]","int").validateCase(json,"{\"args\":[["+million+"]]}","0"));
        var six=new FunctionSignature("sol",java.util.stream.IntStream.range(0,6).mapToObj(i->new FunctionSignature.Parameter("arg"+i,"int")).toList(),"int");
        six.validateCase(json,"{\"args\":[1,2,3,4,5,6]}","0");
        assertThrows(ResponseStatusException.class,()->new FunctionSignature("sol",java.util.stream.IntStream.range(0,7).mapToObj(i->new FunctionSignature.Parameter("arg"+i,"int")).toList(),"int").validated());
    }
}
