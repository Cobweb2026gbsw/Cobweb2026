package com.drewdrew0414.global.security;

import com.drewdrew0414.global.exception.GlobalExceptionHandler;
import com.drewdrew0414.global.security.ProblemAuthoringBodyFilter.HttpBodyLimitExceeded;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 실제 40 MiB 배열 없이 생성 스트림으로 바이트 경계와 슬롯 반환을 검사합니다. */
class ProblemAuthoringBodyFilterTest {
    private static final long LIMIT=ProblemAuthoringBodyFilter.MAX_BODY_BYTES;
    private final ProblemAuthoringBodyFilter filter=new ProblemAuthoringBodyFilter();
    private final ObjectMapper json=new ObjectMapper();

    @BeforeEach void authenticate() {setAuthenticated();}
    @AfterEach void clearAuthentication() {SecurityContextHolder.clearContext();}
    private static void setAuthenticated() {
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated("test-author",null,List.of()));
    }

    @Test void declaredOversizeIs413BeforeOpeningOrReadingTheBody() throws Exception {
        var request=new GeneratedRequest("POST","/api/problems",LIMIT+1,LIMIT+1);
        var response=new MockHttpServletResponse();
        var chained=new AtomicBoolean();
        filter.doFilter(request,response,(r,s)->chained.set(true));
        assertFalse(chained.get());assertEquals(0,request.openings);assertEquals(0,request.input.consumed);
        assertSafeError(response,413,"PAYLOAD_TOO_LARGE");
    }

    @Test void exactLimitCanBeReadIncrementallyAndRepeatedStreamAccessDoesNotResetIt() throws Exception {
        assertEquals(40L*1024*1024,LIMIT);
        var request=new GeneratedRequest("POST","/api/problems",LIMIT,LIMIT);
        var response=new MockHttpServletResponse();
        filter.doFilter(request,response,(r,s)->{
            var input=r.getInputStream();
            assertSame(input,r.getInputStream());
            byte[] chunk=new byte[8192];
            long count=0;
            for(int n;(n=input.read(chunk))!=-1;)count+=n;
            assertEquals(LIMIT,count);assertEquals(-1,input.read());
            assertTrue(input.isFinished());
        });
        assertEquals(200,response.getStatus());assertEquals(1,request.openings);
        assertEquals(LIMIT,request.input.consumed);
    }

    @Test void unknownOrLyingLengthCannotBypassStreamingLimitAndPartialResponsesAreRemoved() throws Exception {
        for(long declared:new long[]{-1,1,LIMIT}) {
            var request=new GeneratedRequest("POST","/api/problems",declared,LIMIT+1000);
            var response=new MockHttpServletResponse();
            filter.doFilter(request,response,(r,s)->{
                s.getWriter().write("private-sentinel-source-code");
                r.getInputStream().transferTo(OutputStream.nullOutputStream());
                fail("The over-limit stream must not complete.");
            });
            assertEquals(LIMIT+1,request.input.consumed);
            assertSafeError(response,413,"PAYLOAD_TOO_LARGE");
            assertFalse(response.getContentAsString().contains("private-sentinel"));
        }
    }

    @Test void readerUsesTheSameByteLimitAndCannotSwitchToAnUncountedInputStream() throws Exception {
        var request=new GeneratedRequest("PUT","/api/problems/1000",-1,LIMIT+2);
        request.setCharacterEncoding("UTF-8");request.input.pattern=new byte[]{(byte)0xc3,(byte)0xa9};
        var response=new MockHttpServletResponse();
        filter.doFilter(request,response,(r,s)->{
            BufferedReader reader=r.getReader();
            assertSame(reader,r.getReader());
            assertThrows(IllegalStateException.class,r::getInputStream);
            char[] chunk=new char[4096];
            while(reader.read(chunk)!=-1) { /* 개별 문자열이나 본문 사본을 만들지 않습니다. */ }
            fail("UTF-8 reader must count raw bytes, not characters.");
        });
        assertSafeError(response,413,"PAYLOAD_TOO_LARGE");
        assertEquals(1,request.openings);assertEquals(LIMIT+1,request.input.consumed);
    }

    @Test void skipAndServlet61ByteBufferReadsShareTheCounter() throws Exception {
        var request=new GeneratedRequest("PUT","/api/problems/1000",-1,LIMIT+1);
        var response=new MockHttpServletResponse();
        filter.doFilter(request,response,(r,s)->{
            var input=r.getInputStream();
            ByteBuffer target=ByteBuffer.allocate(20);target.position(3);
            assertEquals(17,input.read(target));assertEquals(3,target.position());assertEquals(20,target.limit());
            assertEquals(LIMIT-17,input.skip(LIMIT-17));assertEquals(0,input.available());
            input.read(ByteBuffer.allocate(2));
            fail("skip/ByteBuffer must not bypass the limit.");
        });
        assertSafeError(response,413,"PAYLOAD_TOO_LARGE");assertEquals(LIMIT+1,request.input.consumed);
    }

    @Test void everyDownstreamFailureReleasesTheSinglePermit() throws Exception {
        for(int kind=0;kind<3;kind++) {
            final int failure=kind;
            var request=new GeneratedRequest("POST","/api/problems",0,0);
            Class<? extends Exception> type=kind==0?IOException.class:kind==1?ServletException.class:IllegalStateException.class;
            assertThrows(type,()->filter.doFilter(request,new MockHttpServletResponse(),(r,s)->{
                if(failure==0)throw new IOException("private-sentinel");
                if(failure==1)throw new ServletException("private-sentinel");
                throw new IllegalStateException("private-sentinel");
            }));
            var next=new MockHttpServletResponse();var entered=new AtomicBoolean();
            filter.doFilter(new GeneratedRequest("POST","/api/problems",0,0),next,(r,s)->entered.set(true));
            assertTrue(entered.get());assertEquals(200,next.getStatus());
        }
    }

    @Test void concurrentAuthoringIsRejectedImmediatelyWithoutReadingAndPermitReturnsAfterCompletion() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var executor=Executors.newSingleThreadExecutor();
        try {
            var first=executor.submit(()->{
                setAuthenticated();
                try {
                    filter.doFilter(new GeneratedRequest("POST","/api/problems",0,0),new MockHttpServletResponse(),(r,s)->{
                        entered.countDown();
                        try {assertTrue(release.await(5,TimeUnit.SECONDS));}
                        catch(InterruptedException error) {Thread.currentThread().interrupt();throw new ServletException(error);}
                    });
                } finally {SecurityContextHolder.clearContext();}
                return null;
            });
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            var second=new GeneratedRequest("PUT","/api/problems/1000",-1,LIMIT);
            var response=new MockHttpServletResponse();
            filter.doFilter(second,response,(r,s)->fail("Concurrent authoring must not reach MVC."));
            assertSafeError(response,429,"RATE_LIMITED");assertEquals("1",response.getHeader("Retry-After"));
            assertEquals(0,second.openings);assertEquals(0,second.input.consumed);
            release.countDown();first.get(3,TimeUnit.SECONDS);
            var third=new MockHttpServletResponse();var accepted=new AtomicBoolean();
            filter.doFilter(new GeneratedRequest("POST","/api/problems",0,0),third,(r,s)->accepted.set(true));
            assertTrue(accepted.get());assertEquals(200,third.getStatus());
        } finally {release.countDown();executor.shutdownNow();}
    }

    @Test void onlyAuthenticatedAuthoringRoutesAreWrappedAndContextPathsAndSignedNumbersRemainCovered() throws Exception {
        assertEquals(0,ProblemAuthoringBodyFilter.class.getAnnotation(Order.class).value());
        for(String[] route:new String[][]{{"GET","/api/problems"},{"POST","/api/problems/1000/test-runs"},
            {"POST","/api/problems/1000/review"},{"PUT","/api/problems/permissions"},{"PUT","/api/problems/1/edit"},
            {"POST","/api/community/posts"},{"DELETE","/api/problems/1000"}}) {
            var request=new GeneratedRequest(route[0],route[1],LIMIT+1,0);
            filter.doFilter(request,new MockHttpServletResponse(),(r,s)->assertSame(request,r));
            assertEquals(0,request.openings);
        }
        for(String number:new String[]{"1000","0001","-1","+1","0","0x3e8"," 1000 "}) {
            var request=new GeneratedRequest("PUT","/app/api/problems/"+number,LIMIT+1,0);
            request.setContextPath("/app");request.setServletPath("/api/problems/"+number);
            var response=new MockHttpServletResponse();
            filter.doFilter(request,response,(r,s)->fail("Context path must not bypass the authoring guard."));
            assertSafeError(response,413,"PAYLOAD_TOO_LARGE");
        }
        var fallback=new GeneratedRequest("POST","/app/api/problems",LIMIT+1,0);
        fallback.setContextPath("/app");fallback.setServletPath("");
        var response=new MockHttpServletResponse();
        filter.doFilter(fallback,response,(r,s)->fail("Empty servletPath must use context-relative URI."));
        assertSafeError(response,413,"PAYLOAD_TOO_LARGE");
        for(boolean anonymousToken:new boolean[]{false,true}) {
            SecurityContextHolder.clearContext();
            if(anonymousToken)SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "test-only","anonymous",List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
            var request=new GeneratedRequest("POST","/api/problems",LIMIT+1,0);
            filter.doFilter(request,new MockHttpServletResponse(),(r,s)->assertSame(request,r));
            assertEquals(0,request.openings);
        }
    }

    @Test void nonblockingAndMixedReadModesAreExplicitlyUnsupported() throws Exception {
        var request=new GeneratedRequest("POST","/api/problems",0,0);
        filter.doFilter(request,new MockHttpServletResponse(),(r,s)->{
            assertFalse(r.isAsyncSupported());
            assertThrows(IllegalStateException.class,r::startAsync);
            assertThrows(IllegalStateException.class,()->r.startAsync(r,s));
            var input=r.getInputStream();assertTrue(input.isReady());
            assertThrows(IllegalStateException.class,()->input.setReadListener(new ReadListener() {
                public void onDataAvailable() {} public void onAllDataRead() {} public void onError(Throwable error) {}
            }));
            assertThrows(IllegalStateException.class,r::getReader);
            assertFalse(input.markSupported());assertThrows(IOException.class,input::reset);
        });
    }

    @Test void mvcWrappedLimitErrorsAre413WithFixedCodesButUnrelatedMalformedJsonStays400() {
        var handler=new GlobalExceptionHandler();
        var request=new ServletServerHttpRequest(new MockHttpServletRequest());
        var wrapped=new HttpMessageNotReadableException("private-sentinel source code",
            new IOException("private-sentinel body",new HttpBodyLimitExceeded()),request);
        var response=handler.handleInvalidRequest(wrapped);
        assertEquals(413,response.getStatusCode().value());
        assertEquals("PAYLOAD_TOO_LARGE",response.getBody().getErrorCode());
        assertEquals("요청 데이터가 너무 큽니다.",response.getBody().getMessage());
        assertNull(response.getBody().getData());assertFalse(response.getBody().isSuccess());
        assertEquals(413,handler.handleException(new ServletException("private-sentinel",wrapped)).getStatusCode().value());
        assertEquals(400,handler.handleInvalidRequest(new HttpMessageNotReadableException("private-sentinel",request)).getStatusCode().value());
        assertFalse(ProblemAuthoringBodyFilter.isBodyLimitExceeded(new IOException("private-sentinel")));
    }

    private void assertSafeError(MockHttpServletResponse response,int status,String code) throws Exception {
        assertEquals(status,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));
        assertTrue(response.getContentType().startsWith("application/json"));
        var body=json.readTree(response.getContentAsString());
        assertEquals(code,body.get("errorCode").asText());assertFalse(body.get("success").asBoolean());
        assertTrue(body.get("data").isNull());assertFalse(body.has("trace"));
        assertFalse(response.getContentAsString().contains("private-sentinel"));
    }

    private static final class GeneratedRequest extends MockHttpServletRequest {
        private final long declared;
        private final GeneratedStream input;
        private int openings;
        private GeneratedRequest(String method,String path,long declared,long length) {
            super(method,path);setServletPath(path);this.declared=declared;this.input=new GeneratedStream(length);
        }
        @Override public long getContentLengthLong() {return declared;}
        @Override public ServletInputStream getInputStream() {openings++;return input;}
        @Override public BufferedReader getReader() {throw new AssertionError("The wrapper must use its counted stream.");}
    }

    private static final class GeneratedStream extends ServletInputStream {
        private final long length;
        private long consumed;
        private byte[] pattern=new byte[]{'x'};
        private GeneratedStream(long length) {this.length=length;}
        @Override public int read() {
            if(consumed>=length)return -1;
            return pattern[(int)(consumed++%pattern.length)]&0xff;
        }
        @Override public int read(byte[] bytes,int offset,int count) {
            if(count==0)return 0;
            if(consumed>=length)return -1;
            int read=(int)Math.min(count,length-consumed);
            for(int i=0;i<read;i++)bytes[offset+i]=pattern[(int)((consumed+i)%pattern.length)];
            consumed+=read;return read;
        }
        @Override public int available() {return (int)Math.min(Integer.MAX_VALUE,length-consumed);}
        @Override public boolean isFinished() {return consumed>=length;}
        @Override public boolean isReady() {return true;}
        @Override public void setReadListener(ReadListener listener) {throw new UnsupportedOperationException();}
    }
}
