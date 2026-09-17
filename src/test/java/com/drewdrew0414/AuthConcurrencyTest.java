package com.drewdrew0414;

import com.drewdrew0414.domain.user.service.AuthService;
import com.drewdrew0414.domain.user.service.PasswordResetService;
import com.drewdrew0414.domain.user.exception.PasswordMismatchException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

/** 개별 요청 테스트로 놓치기 쉬운 인증/JPA 변경감지 경합을 실제 MySQL에서 재현합니다. */
@SpringBootTest
@ActiveProfiles("test")
class AuthConcurrencyTest {
    @Autowired JdbcTemplate db;
    @Autowired AuthService auth;
    @Autowired PasswordResetService resets;
    @MockitoSpyBean PasswordEncoder encoder;

    private String createUser() {
        String name="c"+UUID.randomUUID().toString().replace("-", "").substring(0,12);
        db.update("INSERT INTO users(username,password) VALUES(?,?)",name,encoder.encode("old-password"));
        return name;
    }

    @Test void inFlightLoginCannotRestorePasswordThatWasReset() throws Exception {
        String name=createUser();
        long uid=db.queryForObject("SELECT id FROM users WHERE username=?",Long.class,name);
        String token=UUID.randomUUID().toString();
        db.update("INSERT INTO password_reset(user_id,reset_token,expires_at) VALUES(?,?,?)",
                uid,token,LocalDateTime.now().plusMinutes(5));
        var verified=new CountDownLatch(1);
        var releaseLogin=new CountDownLatch(1);
        doAnswer(call -> {
            Object matched=call.callRealMethod();
            verified.countDown();
            if(!releaseLogin.await(10,TimeUnit.SECONDS)) throw new AssertionError("login gate timeout");
            return matched;
        }).when(encoder).matches(eq("old-password"),anyString());

        try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            var login=workers.submit(()->auth.login(name,"old-password","127.0.0.1","test",new MockHttpServletResponse()));
            assertTrue(verified.await(5,TimeUnit.SECONDS));
            var reset=workers.submit(()->resets.confirmReset(token,"new-password"));
            try {
                // 취약 구현은 로그인 검증이 멈춘 사이 재설정을 커밋합니다. 수정 후에는
                // 계정 잠금 때문에 재설정이 기다렸다가 로그인 직후 처리되어야 합니다.
                reset.get(1,TimeUnit.SECONDS);
            } catch(TimeoutException expectedWhenSerialized) {
                // 완료 여부가 아닌 최종 비밀번호/세션 상태가 아래의 검증 기준입니다.
            } finally { releaseLogin.countDown(); }
            login.get(10,TimeUnit.SECONDS);
            reset.get(10,TimeUnit.SECONDS);
        } finally { releaseLogin.countDown(); }
        assertTrue(encoder.matches("new-password",db.queryForObject("SELECT password FROM users WHERE id=?",String.class,uid)),
                "늦게 끝난 로그인 UPDATE가 재설정된 비밀번호를 과거 값으로 되돌렸습니다.");
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE user_id=?",Integer.class,uid),
                "재설정과 겹친 과거 비밀번호 로그인으로 refresh token이 되살아나면 안 됩니다.");
    }

    @Test void simultaneousFailuresDoNotLoseAuditCounter() throws Exception {
        String name=createUser();
        var bothRead=new CountDownLatch(2);
        doAnswer(call -> {
            bothRead.countDown();
            bothRead.await(1,TimeUnit.SECONDS);
            return call.callRealMethod();
        }).when(encoder).matches(eq("wrong-password"),anyString());
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            Runnable attempt=()->assertThrows(PasswordMismatchException.class,()->
                    auth.login(name,"wrong-password","127.0.0.1","test",new MockHttpServletResponse()));
            var first=workers.submit(attempt);
            var second=workers.submit(attempt);
            first.get(10,TimeUnit.SECONDS);
            second.get(10,TimeUnit.SECONDS);
        }
        assertEquals(2,db.queryForObject("SELECT login_failed_count FROM users WHERE username=?",Integer.class,name));
    }
}
