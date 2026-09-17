package com.drewdrew0414;

import com.drewdrew0414.domain.user.service.PasswordResetService;
import com.drewdrew0414.domain.user.exception.InvalidResetTokenException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/** 실제 MySQL에서 일회용 토큰의 검사와 사용 처리가 경쟁 요청에도 원자적인지 검증합니다. */
@SpringBootTest
@ActiveProfiles("test")
class PasswordResetConcurrencyTest {
    @Autowired JdbcTemplate db;
    @Autowired PasswordResetService service;
    @MockitoSpyBean PasswordEncoder encoder;

    @Test void sameResetTokenCanOnlySucceedOnceUnderConcurrentUse() throws Exception {
        String name="r"+UUID.randomUUID().toString().replace("-", "").substring(0,12);
        db.update("INSERT INTO users(username,password) VALUES(?,'unused')",name);
        long uid=db.queryForObject("SELECT id FROM users WHERE username=?",Long.class,name);
        String token=UUID.randomUUID().toString();
        // 엔티티와 동일한 JVM LocalDateTime을 사용합니다(DB 서버 NOW()의 시간대와 섞지 않습니다).
        db.update("INSERT INTO password_reset(user_id,reset_token,expires_at) VALUES(?,?,?)",uid,token,
                java.time.LocalDateTime.now().plusMinutes(5));

        CountDownLatch encodeEntered=new CountDownLatch(2);
        // 취약 코드에서는 두 요청을 토큰 검사 후 같은 지점에 모읍니다. 수정 후에는 두 번째
        // 요청이 DB 잠금에서 기다리므로 첫 요청은 제한된 대기 후 진행하고 두 번째는 거절됩니다.
        doAnswer(invocation -> {
            encodeEntered.countDown();
            encodeEntered.await(2,TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(encoder).encode(any(CharSequence.class));

        CountDownLatch start=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<Boolean> attempt=()-> {
                start.await();
                try { service.confirmReset(token,"new-password"); return true; }
                catch(InvalidResetTokenException expected) { return false; }
            };
            var first=executor.submit(attempt);
            var second=executor.submit(attempt);
            start.countDown();
            int successes=(first.get(15,TimeUnit.SECONDS)?1:0)+(second.get(15,TimeUnit.SECONDS)?1:0);
            assertEquals(1,successes,"같은 재설정 토큰으로 두 번 변경되면 안 됩니다.");
        }
        assertTrue(encoder.matches("new-password",db.queryForObject("SELECT password FROM users WHERE id=?",String.class,uid)));
        assertThrows(InvalidResetTokenException.class,()->service.confirmReset(token,"replayed-password"));
    }
}
