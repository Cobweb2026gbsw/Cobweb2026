package com.drewdrew0414.global.exception;

import com.drewdrew0414.global.common.ApiResponse;
import com.drewdrew0414.domain.user.exception.InvalidTokenException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatusCode;
import org.springframework.mail.MailSendException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** 실제 MVC 예외 선택과 응답 JSON을 검사합니다. 외부 서비스·DB·비밀값은 사용하지 않습니다. */
class GlobalExceptionHandlerTest {
    private MockMvc mvc;
    private final ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup() {mvc=MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(new GlobalExceptionHandler()).build();}

    @Test void domainFailureTypesPreserveStatusesAndExposeStableCodes() throws Exception {
        for(var type:ApiException.Type.values()) {
            var response=mvc.perform(get("/check/typed/"+type.status())).andReturn().getResponse();
            assertEquals(type.status(),response.getStatus());
            var body=json.readTree(response.getContentAsString());
            assertEquals(type.name(),body.get("errorCode").asText());assertFalse(body.get("success").asBoolean());
            assertTrue(body.get("data").isNull());
            if(type.status()>=500)assertFalse(response.getContentAsString().contains("private-sentinel"));
        }
    }
    @Test void missingAndMalformedParametersAndJsonAre400NotServerFailures() throws Exception {
        for(var request:new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[]{
            get("/check/required"),get("/check/required").param("count","not-a-number"),
            post("/check/json").contentType("application/json").content("{"),
            post("/check/json").contentType("application/json").content("{\"value\":\"\"}")}) {
            var response=mvc.perform(request).andReturn().getResponse();
            assertEquals(400,response.getStatus());assertEquals("INVALID_REQUEST",json.readTree(response.getContentAsString()).get("errorCode").asText());
        }
    }
    @Test void unsupportedMethodAndMediaRetainProtocolStatusAndAllowHeader() throws Exception {
        var method=mvc.perform(delete("/check/json")).andReturn().getResponse();
        assertEquals(405,method.getStatus());assertTrue(method.getHeader("Allow").contains("POST"));
        assertEquals("METHOD_NOT_ALLOWED",json.readTree(method.getContentAsString()).get("errorCode").asText());
        var media=mvc.perform(post("/check/json").contentType("text/plain").content("private-sentinel")).andReturn().getResponse();
        assertEquals(415,media.getStatus());assertEquals("UNSUPPORTED_MEDIA_TYPE",json.readTree(media.getContentAsString()).get("errorCode").asText());
    }
    @Test void infrastructureFailuresAreClassifiedWithoutLeakingProviderOrSqlMessages() throws Exception {
        for(var entry:java.util.Map.of("database",503,"deadlock",409,"integrity",409,"mail",503,"upstream",502,"denied",403,"legacy",400,"token",401).entrySet()) {
            var response=mvc.perform(get("/check/failure/"+entry.getKey())).andReturn().getResponse();
            assertEquals(entry.getValue(),response.getStatus(),entry.getKey());
            assertFalse(response.getContentAsString().contains("private-sentinel"));
            assertNotNull(json.readTree(response.getContentAsString()).get("errorCode"));
        }
    }
    @Test void unknownFailureReturnsOnlySafeMessageAndOpaqueDiagnosticId() throws Exception {
        var response=mvc.perform(get("/check/failure/unknown")).andReturn().getResponse();
        assertEquals(500,response.getStatus());var body=json.readTree(response.getContentAsString());
        assertEquals("INTERNAL_ERROR",body.get("errorCode").asText());
        assertDoesNotThrow(()->java.util.UUID.fromString(body.get("errorId").asText()));
        assertFalse(response.getContentAsString().contains("private-sentinel"));
        assertFalse(response.getContentAsString().contains("IllegalStateException"));assertFalse(body.has("trace"));
    }
    @Test void filterErrorsHaveTheSameJsonShapeAndNoCacheWithoutTouchingCommittedResponses() throws Exception {
        for(int status:new int[]{401,403,503}) {
            var response=new MockHttpServletResponse();
            ApiErrorWriter.write(response,status,"TEST_CODE","공개 오류 안내");
            assertEquals(status,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));
            assertTrue(response.getContentType().startsWith("application/json"));
            var body=json.readTree(response.getContentAsString());assertFalse(body.get("success").asBoolean());
            assertEquals("TEST_CODE",body.get("errorCode").asText());assertEquals("공개 오류 안내",body.get("message").asText());
            response.flushBuffer();String prior=response.getContentAsString();
            ApiErrorWriter.write(response,500,"DO_NOT_WRITE","not-written");assertEquals(prior,response.getContentAsString());
        }
    }

    @RestController static class Probe {
        record Payload(@NotBlank String value) {}
        @GetMapping("/check/required") public Object required(@RequestParam int count) {return ApiResponse.success(count);}
        @PostMapping(value="/check/json",consumes="application/json") public Object input(@Valid @RequestBody Payload body) {return ApiResponse.success(body);}
        @GetMapping("/check/typed/{status}") public Object typed(@PathVariable int status) {throw new ApiException(HttpStatusCode.valueOf(status),"private-sentinel");}
        @GetMapping("/check/failure/{kind}") public Object failed(@PathVariable String kind) {
            throw switch(kind) {
                case "database"->new DataAccessResourceFailureException("private-sentinel SQL");
                case "deadlock"->new CannotAcquireLockException("private-sentinel lock");
                case "integrity"->new DataIntegrityViolationException("private-sentinel SQL");
                case "mail"->new MailSendException("private-sentinel SMTP");
                case "upstream"->new RestClientException("private-sentinel OAuth");
                case "denied"->new AccessDeniedException("private-sentinel permission");
                case "legacy"->new ResponseStatusException(HttpStatusCode.valueOf(400),"private-sentinel legacy");
                case "token"->new InvalidTokenException();
                default->new IllegalStateException("private-sentinel source-code/credentials");
            };
        }
    }
}
