package com.drewdrew0414.global.security;

import com.drewdrew0414.global.exception.ApiErrorWriter;
import com.drewdrew0414.global.exception.ApiException;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.NumberUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 큰 함수형 테스트를 포함하는 문제 제작 API만 스트리밍 본문 상한을 적용합니다.
 *
 * Spring Security의 기본 서블릿 필터 순서(-100) 뒤에 실행되므로 미인증 작성 요청은
 * 인증 경계가 먼저 거절합니다. 단일 JVM에서 제작 요청 하나만 파싱/처리하도록 제한하여
 * 동시에 여러 40 MiB JSON 트리를 만들지 않습니다. 대기열이나 본문 사본은 만들지 않습니다.
 * 이 두 MVC API는 동기식이며 비동기 시작/ReadListener는 지원하지 않습니다.
 */
@Component
@Order(0)
public final class ProblemAuthoringBodyFilter extends OncePerRequestFilter {
    public static final long MAX_BODY_BYTES=40L*1024*1024;
    private static final String EDIT_PREFIX="/api/problems/";
    private final Semaphore authoringSlot=new Semaphore(1);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=request.getServletPath();
        if(path==null||path.isEmpty()) {
            path=request.getRequestURI();
            String context=request.getContextPath();
            if(path!=null&&context!=null&&!context.isEmpty()&&path.startsWith(context))
                path=path.substring(context.length());
        }
        boolean authoring="POST".equals(request.getMethod())&&"/api/problems".equals(path)
            ||"PUT".equals(request.getMethod())&&isEditPath(path);
        if(!authoring)return true;
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        return authentication==null||!authentication.isAuthenticated()
            ||authentication instanceof AnonymousAuthenticationToken;
    }

    private static boolean isEditPath(String path) {
        if(path==null||!path.startsWith(EDIT_PREFIX))return false;
        String number=path.substring(EDIT_PREFIX.length());
        if(number.isEmpty()||number.indexOf('/')>=0)return false;
        try {
            // MVC가 사용하는 숫자 파싱과 동일한 규칙입니다. 부호·앞쪽 0 외에도
            // 0x 표기나 공백 정리로 int가 되는 경로가 정규식 상한을 우회하지 못합니다.
            NumberUtils.parseNumber(number,Integer.class);
            return true;
        } catch(IllegalArgumentException invalidNumber) {
            return false;
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
        throws ServletException,IOException {
        // 헤더만으로 확정되는 초과 요청은 스트림을 열거나 슬롯을 얻기 전에 거절합니다.
        if(request.getContentLengthLong()>MAX_BODY_BYTES) {
            reject(response,ApiException.Type.PAYLOAD_TOO_LARGE);
            return;
        }
        if(!authoringSlot.tryAcquire()) {
            response.setHeader("Retry-After","1");
            reject(response,ApiException.Type.RATE_LIMITED);
            return;
        }
        try {
            chain.doFilter(new LimitedRequest(request),response);
        } catch(IOException|ServletException error) {
            // MVC가 처리하지 않은 스트림 예외도 원문/입력을 노출하지 않는 동일 계약입니다.
            if(isBodyLimitExceeded(error))reject(response,ApiException.Type.PAYLOAD_TOO_LARGE);
            else throw error;
        } finally {
            authoringSlot.release();
        }
    }

    private static void reject(HttpServletResponse response,ApiException.Type type) throws IOException {
        if(response.isCommitted())return;
        // 아직 커밋되지 않은 부분 응답이 있더라도 안전한 JSON 한 개만 남깁니다.
        response.resetBuffer();
        ApiErrorWriter.write(response,type.status(),type.name(),type.message());
    }

    /** Jackson/MVC의 IOException 포장 깊이에 관계없이 고정 413으로 분류합니다. */
    public static boolean isBodyLimitExceeded(Throwable failure) {
        // 외부 예외의 순환 cause나 비정상적으로 깊은 사슬을 무한히 순회하지 않습니다.
        for(int depth=0;failure!=null&&depth<32;depth++,failure=failure.getCause())
            if(failure instanceof HttpBodyLimitExceeded)return true;
        return false;
    }

    /** 외부 입력·경로·자격 증명을 담지 않는 바이트 상한 표시용 예외입니다. */
    public static final class HttpBodyLimitExceeded extends IOException {
        private static final long serialVersionUID=1L;
        public HttpBodyLimitExceeded() {super("Problem authoring request body exceeds the byte limit.");}
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private LimitedStream stream;
        private BufferedReader reader;
        private boolean streamSelected,readerSelected;

        private LimitedRequest(HttpServletRequest request) {super(request);}
        private LimitedStream boundedStream() throws IOException {
            if(stream==null)stream=new LimitedStream(super.getInputStream());
            return stream;
        }
        @Override public ServletInputStream getInputStream() throws IOException {
            if(readerSelected)throw new IllegalStateException("getReader() has already been called.");
            streamSelected=true;
            return boundedStream();
        }
        @Override public BufferedReader getReader() throws IOException {
            if(streamSelected)throw new IllegalStateException("getInputStream() has already been called.");
            readerSelected=true;
            if(reader==null) {
                String encoding=getCharacterEncoding();
                reader=new BufferedReader(new InputStreamReader(boundedStream(),
                    encoding==null?StandardCharsets.ISO_8859_1.name():encoding));
            }
            return reader;
        }
        @Override public boolean isAsyncSupported() {return false;}
        @Override public AsyncContext startAsync() {throw syncOnly();}
        @Override public AsyncContext startAsync(ServletRequest request,ServletResponse response) {throw syncOnly();}
    }

    private static IllegalStateException syncOnly() {
        return new IllegalStateException("Problem authoring requests require blocking input processing.");
    }

    private static final class LimitedStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private long consumed;
        private boolean exceeded,eof;

        private LimitedStream(ServletInputStream delegate) {this.delegate=delegate;}
        private void checkLimit(int count) throws HttpBodyLimitExceeded {
            if(count>0)consumed+=count;
            if(exceeded||consumed>MAX_BODY_BYTES) {
                exceeded=true;
                throw new HttpBodyLimitExceeded();
            }
        }
        @Override public int read() throws IOException {
            checkLimit(0);
            int value=delegate.read();
            if(value<0)eof=true;
            else checkLimit(1);
            return value;
        }
        @Override public int read(byte[] target,int offset,int length) throws IOException {
            Objects.checkFromIndexSize(offset,length,target.length);
            if(length==0)return 0;
            checkLimit(0);
            // 초과를 알아내기 위한 1바이트 외에는 상한 밖 데이터를 선행 읽기하지 않습니다.
            int allowed=(int)Math.min(length,MAX_BODY_BYTES-consumed+1);
            int count=delegate.read(target,offset,allowed);
            if(count<0)eof=true;
            else checkLimit(count);
            return count;
        }
        @Override public int read(ByteBuffer target) throws IOException {
            Objects.requireNonNull(target);
            if(!target.hasRemaining())return 0;
            if(target.isReadOnly())throw new ReadOnlyBufferException();
            // Servlet 6.1의 기본 구현은 target.remaining() 크기 임시 배열을 만듭니다.
            // 이 구현은 최대 8 KiB만 쓰고 동일한 바이트 계수 경로를 거칩니다.
            byte[] chunk=new byte[Math.min(8192,target.remaining())];
            int count=read(chunk,0,chunk.length);
            if(count>0) {
                int position=target.position();
                target.put(chunk,0,count);
                target.position(position);
                target.limit(position+count);
            }
            return count;
        }
        @Override public long skip(long count) throws IOException {
            if(count<=0)return 0;
            byte[] chunk=new byte[(int)Math.min(8192,count)];
            long skipped=0;
            while(skipped<count) {
                int read=read(chunk,0,(int)Math.min(chunk.length,count-skipped));
                if(read<0)break;
                if(read==0) {
                    if(read()<0)break;
                    skipped++;
                    continue;
                }
                skipped+=read;
            }
            return skipped;
        }
        @Override public int available() throws IOException {
            return (int)Math.min(delegate.available(),Math.max(0,MAX_BODY_BYTES-consumed));
        }
        @Override public boolean isFinished() {return eof||delegate.isFinished();}
        @Override public boolean isReady() {return true;} // blocking 전용: 읽기는 필요하면 대기할 수 있습니다.
        @Override public void setReadListener(ReadListener listener) {
            Objects.requireNonNull(listener);
            throw syncOnly();
        }
        @Override public void close() throws IOException {delegate.close();}
    }
}
