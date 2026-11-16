package com.drewdrew0414.domain.submission;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Docker나 제출 코드를 실행하지 않고 cleanup의 인터럽트 보존만 제어된 OS 대기로 검사합니다. */
class DockerJudgeCleanupTest {
    @Test void interruptionArrivingDuringCleanupIsPreserved() throws Exception {
        Method cleanup=DockerJudge.class.getDeclaredMethod("cleanup",String[].class);cleanup.setAccessible(true);
        var restored=new AtomicBoolean();var failure=new AtomicReference<Throwable>();
        Thread worker=Thread.ofPlatform().start(()->{
            try {cleanup.invoke(null,(Object)new String[]{"/bin/sleep","10"});restored.set(Thread.currentThread().isInterrupted());}
            catch(Throwable error){failure.set(error);}
        });
        try {
            // cleanup이 이전 인터럽트를 지운 뒤 실제 Process.waitFor에 진입한 것을 관찰합니다.
            long deadline=System.nanoTime()+Duration.ofSeconds(3).toNanos();boolean waiting=false;
            while(worker.isAlive() && System.nanoTime()<deadline) {
                waiting=Arrays.stream(worker.getStackTrace()).anyMatch(frame->frame.getMethodName().equals("waitFor")
                    && frame.getClassName().startsWith("java.lang.Process"));
                if(waiting) break;Thread.sleep(5);
            }
            assertTrue(waiting,"정리 프로세스의 대기 진입을 확인해야 합니다.");
            worker.interrupt();worker.join(3000);
            assertFalse(worker.isAlive());assertNull(failure.get());assertTrue(restored.get());
        } finally {if(worker.isAlive()){worker.interrupt();worker.join(3000);}}
    }

    @Test void anExistingInterruptIsStillRestoredAfterCleanup() throws Exception {
        Method cleanup=DockerJudge.class.getDeclaredMethod("cleanup",String[].class);cleanup.setAccessible(true);
        Thread.currentThread().interrupt();
        try {
            cleanup.invoke(null,(Object)new String[]{"/bin/true"});
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {Thread.interrupted();}
    }
}
