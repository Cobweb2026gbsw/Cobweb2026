package com.drewdrew0414.domain.submission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 블로킹 Docker 실행과 별도 스레드에서 제출 lease를 갱신합니다. */
final class JudgeLease implements AutoCloseable {
    private static final Logger log=LoggerFactory.getLogger(JudgeLease.class);
    private static final int MAX_CONSECUTIVE_FAILURES=3;
    private final SubmissionService submissions;
    private final Map<String,Object> submission;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean current=new AtomicBoolean(true);
    private int failures;

    JudgeLease(SubmissionService submissions,Map<String,Object> submission,long intervalMs) {
        if(intervalMs<=0) throw new IllegalArgumentException("Heartbeat interval must be positive");
        this.submissions=submissions;
        this.submission=submission;
        scheduler=Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("judge-lease-",0).factory());
        scheduler.scheduleWithFixedDelay(()->renew("periodic"),intervalMs,intervalMs,TimeUnit.MILLISECONDS);
    }

    /** false면 토큰이 바뀌었거나 연속 갱신 실패로 더는 이 제출을 처리하면 안 됩니다. */
    synchronized boolean renew(String stage) {
        if(Thread.currentThread().isInterrupted()) current.set(false);
        if(!current.get()) return false;
        try {
            if(!submissions.heartbeat(submission)) {
                if(current.compareAndSet(true,false))
                    log.warn("Judge lease lost: submissionId={} stage={}",submission.get("id"),stage);
                return false;
            }
            failures=0;
            return current.get();
        } catch(Exception ignored) {
            if(Thread.currentThread().isInterrupted()) {
                current.set(false);
                return false;
            }
            failures++;
            log.warn("Judge heartbeat failed: submissionId={} stage={} consecutiveFailures={}",
                submission.get("id"),stage,failures);
            if(failures>=MAX_CONSECUTIVE_FAILURES) current.set(false);
            return current.get();
        }
    }

    boolean current() { return current.get(); }

    @Override public void close() {
        current.set(false);
        scheduler.shutdownNow();
    }
}
