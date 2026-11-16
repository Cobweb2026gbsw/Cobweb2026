package com.drewdrew0414.domain.submission;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GenericFunctionComparisonTest {
    @Test void comparesJsonValuesRatherThanPresentation() {
        assertTrue(DockerJudge.matchesGenericResult("[1, 2]\n","[1,2]"));
        assertTrue(DockerJudge.matchesGenericResult("1.0","1"));
        assertTrue(DockerJudge.matchesGenericResult("0.3333333333333333","0.3333333333"));
        assertTrue(DockerJudge.matchesGenericResult("\"a\\nb\"","\"a\\nb\""));
        assertTrue(DockerJudge.matchesGenericResult("[]","[]"));
    }
    @Test void rejectsTypeMismatchNoiseAndWrongOrder() {
        for(String actual:new String[]{"\"true\"","1","null","true false","true\nlog",""})
            assertFalse(DockerJudge.matchesGenericResult(actual,"true"),actual);
        assertFalse(DockerJudge.matchesGenericResult("[2,1]","[1,2]"));
        assertFalse(DockerJudge.matchesGenericResult("[1]","[1,2]"));
        assertFalse(DockerJudge.matchesGenericResult("\"ab\"","\"a b\""));
        assertFalse(DockerJudge.matchesGenericResult("null","null"));
        assertFalse(DockerJudge.matchesGenericResult("{}","{}"));
        assertFalse(DockerJudge.matchesGenericResult("NaN","1"));
        assertFalse(DockerJudge.matchesGenericResult("1e999","1"));
        assertFalse(DockerJudge.matchesGenericResult("1.1","1"));
        assertFalse(DockerJudge.matchesGenericResult("2147483646","2147483647"));
        assertFalse(DockerJudge.matchesGenericResult("[-2147483647]","[-2147483648]"));
    }
}
