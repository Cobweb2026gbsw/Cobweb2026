package com.drewdrew0414.domain.submission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JudgeDiagnosticTest {
    @Test void compilationKeepsOnlyFixedReasonAndValidOwnSourceLocations() {
        String source="int main() {\n  return missing;\n}\n";
        var value=JudgeDiagnostic.compilation(Language.C,source,
            "/source/main.c:2:10: error: undeclared identifier PRIVATE_SOURCE_TOKEN\n"+
            "  return PRIVATE_SOURCE_TOKEN;\n/work/harness.c:3:2: hidden-harness\n"+
            "/Users/private/secret.c:1:1: private-host-path\n");
        assertEquals(JudgeDiagnostic.Kind.UNDEFINED_NAME,value.errorType());
        assertEquals(List.of(new JudgeDiagnostic.Location("main.c",2,10)),value.locations());
        String json=value.toJson();
        for(String forbidden:List.of("PRIVATE_SOURCE_TOKEN","return missing","/source","/work","/Users","harness","hidden"))
            assertFalse(json.contains(forbidden),forbidden);
        assertTrue(json.contains("COMPILE"));assertTrue(value.matches("COMPILATION_ERROR"));assertFalse(value.matches("RUNTIME_ERROR"));
    }
    @Test void runtimeNeverCopiesInputEchoExceptionArgumentsOrHarnessFrames() {
        var value=JudgeDiagnostic.runtime(Language.PYTHON,"def sol(value):\n    raise ValueError(value)\n",
            "Traceback (most recent call last):\n  File \"/opt/judge/generic_function.py\", line 199, in run\n"+
            "  File \"/source/main.py\", line 2, in sol\n"+
            "ValueError: hidden-test-input=8675309 expected=private-answer token=secret\n",false);
        assertEquals(JudgeDiagnostic.Kind.INVALID_VALUE,value.errorType());
        assertEquals(List.of(new JudgeDiagnostic.Location("main.py",2,null)),value.locations());
        for(String forbidden:List.of("hidden-test","8675309","private-answer","secret","ValueError:","generic_function","raise ValueError"))
            assertFalse(value.toJson().contains(forbidden),forbidden);
    }
    @Test void commonCompilerAndRuntimeKindsAreRecognizedWithoutReturningOriginalText() {
        Map<String,JudgeDiagnostic.Kind> examples=Map.ofEntries(
            Map.entry("SyntaxError: private",JudgeDiagnostic.Kind.SYNTAX_ERROR),
            Map.entry("TypeError: private",JudgeDiagnostic.Kind.TYPE_ERROR),
            Map.entry("ReferenceError: private",JudgeDiagnostic.Kind.UNDEFINED_NAME),
            Map.entry("too few arguments to function sol",JudgeDiagnostic.Kind.ARGUMENT_MISMATCH),
            Map.entry("main method not found",JudgeDiagnostic.Kind.MISSING_ENTRYPOINT),
            Map.entry("error[E0382]: private",JudgeDiagnostic.Kind.BORROW_ERROR),
            Map.entry("Cannot find module 'private'",JudgeDiagnostic.Kind.IMPORT_ERROR),
            Map.entry("ZeroDivisionError: private",JudgeDiagnostic.Kind.DIVISION_BY_ZERO),
            Map.entry("java.lang.ArrayIndexOutOfBoundsException: private",JudgeDiagnostic.Kind.INDEX_OUT_OF_BOUNDS),
            Map.entry("NullPointerException: private",JudgeDiagnostic.Kind.NULL_REFERENCE),
            Map.entry("AssertionError: private",JudgeDiagnostic.Kind.ASSERTION_FAILED),
            Map.entry("RecursionError: private",JudgeDiagnostic.Kind.STACK_OVERFLOW),
            Map.entry("NumberFormatException: private",JudgeDiagnostic.Kind.INVALID_VALUE),
            Map.entry("ArithmeticException: private",JudgeDiagnostic.Kind.ARITHMETIC_ERROR),
            Map.entry("FileNotFoundError: private",JudgeDiagnostic.Kind.FILE_ACCESS_ERROR),
            Map.entry("sqlite3.OperationalError: private",JudgeDiagnostic.Kind.SQL_ERROR),
            Map.entry("Segmentation fault: private",JudgeDiagnostic.Kind.PROCESS_SIGNAL));
        for(var entry:examples.entrySet()) {
            var value=JudgeDiagnostic.runtime(Language.PYTHON,"pass",entry.getKey(),false);
            assertEquals(entry.getValue(),value.errorType(),entry.getKey());
            assertFalse(value.toJson().contains("private"));
        }
    }
    @Test void outputOverflowHasAConstantExplanationAndUnknownErrorsUseSafeFallbacks() {
        assertEquals(JudgeDiagnostic.Kind.OUTPUT_LIMIT,JudgeDiagnostic.runtime(Language.C,"int main(){}","secret",true).errorType());
        assertEquals(JudgeDiagnostic.Kind.RUNTIME_FAILED,JudgeDiagnostic.runtime(Language.PYTHON,"pass","arbitrary hidden stdin",false).errorType());
        assertEquals(JudgeDiagnostic.Kind.COMPILATION_FAILED,JudgeDiagnostic.compilation(Language.C,"int main(){}","private unknown compiler message").errorType());
    }
    @Test void genericWrapperFailureIsNotMistakenForAUserValueErrorAndItsOutputLimitIsExplicit() {
        String wrapper="Traceback (most recent call last):\n"+
            "  File \"/opt/judge/generic_function.py\", line 481, in run\n"+
            "    raise ValueError('FUNCTION solution failed')\nValueError: FUNCTION solution failed\n";
        var unknown=JudgeDiagnostic.runtime(Language.JAVASCRIPT,"function sol() { throw new Error('source-only'); }",
            "Error: private-child-input-and-arguments\n"+wrapper,false);
        assertEquals(JudgeDiagnostic.Kind.RUNTIME_FAILED,unknown.errorType());
        assertFalse(unknown.toJson().contains("private-child"));assertFalse(unknown.toJson().contains("/opt/judge"));
        var actualValueError=JudgeDiagnostic.runtime(Language.PYTHON,"raise ValueError('source-only')",
            "ValueError: private-child-input\n"+wrapper,false);
        assertEquals(JudgeDiagnostic.Kind.INVALID_VALUE,actualValueError.errorType());
        var outputLimit=JudgeDiagnostic.runtime(Language.JAVASCRIPT,"function sol() {}",
            "  File \"/opt/judge/generic_function.py\", line 479, in run\n"+
            "    child.kill();raise ValueError('FUNCTION output limit exceeded')\n"+
            "ValueError: FUNCTION output limit exceeded\n",false);
        assertEquals(JudgeDiagnostic.Kind.OUTPUT_LIMIT,outputLimit.errorType());
        assertTrue(outputLimit.locations().isEmpty());assertFalse(outputLimit.toJson().contains("generic_function"));
    }
    @Test void javascriptGenericHarnessLocationsMapOnlySubmittedLinesAndNeverExposeSourceOrPrivateArguments() {
        String source="function sol() {\n    throw new Error('private-source-only');\n}";
        String raw="/tmp/generic_harness.js:2\n    throw new Error('private-source-only');\n"+
            "Error: private-test-input private-expected-output\n"+
            "    at sol (/tmp/generic_harness.js:2:9)\n"+
            "    at Object.<anonymous> (/tmp/generic_harness.js:20:16)\n"+
            "    at hidden (/secret/generic_harness.js:1:1)\n"+
            "ValueError: FUNCTION solution failed\n";
        var diagnostic=JudgeDiagnostic.runtime(Language.JAVASCRIPT,source,raw,false);
        assertEquals(JudgeDiagnostic.Kind.RUNTIME_FAILED,diagnostic.errorType());
        assertFalse(diagnostic.locations().isEmpty());
        assertTrue(diagnostic.locations().stream().allMatch(location->location.file().equals("main.js") && location.line()==2));
        assertTrue(diagnostic.locations().contains(new JudgeDiagnostic.Location("main.js",2,9)));
        for(String forbidden:List.of("private-source-only","private-test-input","private-expected-output","generic_harness","/tmp/","/secret/","throw new Error"))
            assertFalse(diagnostic.toJson().contains(forbidden),forbidden);
        // TypeScript line numbers may shift during compilation; do not pretend these are source positions.
        assertTrue(JudgeDiagnostic.runtime(Language.TYPESCRIPT,source,raw,false).locations().isEmpty());
    }
    @Test void locationsRejectArbitraryFilesAndImpossibleLinesAndAreBoundedDeduplicated() {
        String raw="/source/main.py:0:0\n/source/main.py:9999999:1\n/secret/main.py:1:1\n"+
            "/opt/judge/main.py:2:1\n/source/harness.py:1:1\n"+
            "/source/main.py:1:999\n/source/main.py:1:999\n/source/main.py:2:1\n"+
            "/source/main.py:3:1\n/source/main.py:4:1\n/source/main.py:5:1\n/source/main.py:6:1\n";
        var value=JudgeDiagnostic.runtime(Language.PYTHON,"a\nb\nc\nd\ne\nf\n",raw,false);
        assertEquals(5,value.locations().size());
        assertEquals(new JudgeDiagnostic.Location("main.py",1,null),value.locations().getFirst());
        assertEquals(5,value.locations().getLast().line());
        assertTrue(value.locations().stream().allMatch(row->row.file().equals("main.py")));
    }
    @ParameterizedTest @EnumSource(Language.class)
    void everySupportedLanguageHasASafeFallbackAndOwnFilename(Language language) {
        var value=JudgeDiagnostic.runtime(language,"one\ntwo\n","/source/"+language.fileName()+":2:1: unknown private-output",false);
        assertEquals(JudgeDiagnostic.Kind.RUNTIME_FAILED,value.errorType());
        assertEquals(List.of(new JudgeDiagnostic.Location(language.fileName(),2,1)),value.locations());
        assertFalse(value.toJson().contains("private-output"));
    }
    @Test void storedMessagesAndUnknownFieldsCannotBecomePublicDiagnosticText() {
        String stored="{\"phase\":\"RUN\",\"errorType\":\"INVALID_VALUE\",\"message\":\"private-input\",\"sourceCode\":\"private-source\",\"locations\":[{\"file\":\"main.py\",\"line\":2,\"column\":null}]}";
        var value=JudgeDiagnostic.fromJson(stored,"RUNTIME_ERROR");assertNotNull(value);
        assertEquals(JudgeDiagnostic.Kind.INVALID_VALUE.message(),value.publicValue().get("message"));
        assertFalse(value.toJson().contains("private"));
        for(String status:List.of("QUEUED","RUNNING","ACCEPTED","WRONG_ANSWER","SYSTEM_ERROR","TIME_LIMIT_EXCEEDED","MEMORY_LIMIT_EXCEEDED","COMPILATION_ERROR"))
            assertNull(JudgeDiagnostic.fromJson(stored,status),status);
        assertEquals(value,JudgeDiagnostic.fromJson(value.toJson(),"RUNTIME_ERROR"));
    }
    @Test void damagedOversizedAndUntrustedLocationDataIsDiscardedWithoutThrowing() {
        for(String invalid:List.of("","not-json","{}","[]","null","x".repeat(8193),
            "{\"phase\":\"RUN\",\"errorType\":\"private-secret\",\"locations\":[]}",
            "{\"phase\":\"RUN\",\"errorType\":\"INVALID_VALUE\",\"locations\":[{\"file\":\"/secret/main.py\",\"line\":1}]}",
            "{\"phase\":\"RUN\",\"errorType\":\"INVALID_VALUE\",\"locations\":[{\"file\":\"main.py\",\"line\":0}]}",
            "{\"phase\":\"RUN\",\"errorType\":\"INVALID_VALUE\",\"locations\":[{\"file\":\"main.py\",\"line\":1.5}]}"))
            assertNull(JudgeDiagnostic.fromJson(invalid,"RUNTIME_ERROR"),invalid.substring(0,Math.min(invalid.length(),100)));
        assertNull(JudgeDiagnostic.fromJson(null,"RUNTIME_ERROR"));
    }
}
