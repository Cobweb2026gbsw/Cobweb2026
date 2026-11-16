package com.drewdrew0414.domain.learningreport;

import com.drewdrew0414.global.openai.LearningReportGateway;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class LearningReportService {
    private static final Logger LOG = LoggerFactory.getLogger(LearningReportService.class);
    private static final int MAX_CONCURRENT_GENERATIONS = 2;

    private final LearningReportRepository repository;
    private final LearningReportGateway gateway;
    private final Semaphore generationSlots = new Semaphore(MAX_CONCURRENT_GENERATIONS);
    private final ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENT_GENERATIONS,
            Thread.ofPlatform().daemon().name("learning-report-", 0).factory());

    public LearningReportService(LearningReportRepository repository, LearningReportGateway gateway) {
        this.repository = repository;
        this.gateway = gateway;
    }

    public Dashboard dashboard(long userId) {
        return repository.dashboard(userId, LocalDate.now(LearningReportRepository.SEOUL), gateway.isAvailable());
    }

    public Generation generate(long userId) {
        if (!gateway.isAvailable()) return new Generation("unavailable");
        if (!generationSlots.tryAcquire())
            return new Generation(repository.generationRunning(userId) ? "running" : "retry_later");

        boolean delegated = false;
        try {
            // Returning from begin commits before a background job contacts OpenAI.
            var decision = repository.begin(userId, LocalDate.now(LearningReportRepository.SEOUL));
            var claim = decision.claim();
            if (claim != null) {
                try {
                    executor.execute(() -> process(claim));
                    delegated = true;
                } catch (RejectedExecutionException rejected) {
                    finishFailure(claim);
                    return new Generation("retry_later");
                }
            }
            return new Generation(decision.state());
        } finally {
            if (!delegated) generationSlots.release();
        }
    }

    private void process(LearningReportRepository.Claim claim) {
        try {
            var summary = gateway.generate(claim.facts(), claim.previousSummary());
            repository.commit(claim, summary.userSummary(), summary.carryForwardSummary(), Instant.now().toString());
        } catch (RuntimeException failure) {
            // Never log model output, prompt input, API errors or secret configuration.
            LOG.warn("Learning report generation failed. The report can be retried later.");
            finishFailure(claim);
        } finally {
            generationSlots.release();
        }
    }

    private void finishFailure(LearningReportRepository.Claim claim) {
        try {
            repository.fail(claim);
        } catch (RuntimeException failure) {
            LOG.warn("Could not record a learning report failure. The generation lease will expire.");
        }
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    public record ActivityDay(String date, int solvedCount) {}
    public record Generation(String generationState) {}

    public record Report(int schemaVersion, int reportNumber, List<Integer> problemIds, LearningReportScores.Radar radar,
                         Integer problemSolvingScore, String userSummary, String createdAt) {}

    public record Dashboard(List<ActivityDay> activity, int remainingToNextReport, int availableBatches,
                            String generationState, List<Report> reports) {}
}
