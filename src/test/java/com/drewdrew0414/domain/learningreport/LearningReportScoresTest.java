package com.drewdrew0414.domain.learningreport;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LearningReportScoresTest {
    @Test
    void scoresSixAxesFromFiveProblemsAndSendsOnlyRecognizedTopicNames() {
        var firstPass = new LearningReportScores.AttemptEvidence(0, null, null, 0, 0, 0, 0, 0, "PYTHON", false);
        var mixedErrors = new LearningReportScores.AttemptEvidence(2, 1L, 2L, 1, 0, 0, 1, 0, "JAVA", false);
        var oneError = new LearningReportScores.AttemptEvidence(1, 1L, null, 0, 1, 0, 0, 0,
                "private-marker-language", false);
        var facts = LearningReportScores.evaluate(List.of(
                problem(List.of("배열", "2차원 배열", "private-marker-tag"), firstPass),
                problem(List.of("배열·문자열"), firstPass),
                problem(List.of("문자열"), firstPass),
                problem(List.of("구현"), mixedErrors),
                problem(List.of(), oneError)
        ), 7);

        assertThat(facts.radar()).isEqualTo(new LearningReportScores.Radar(60, 75, 100, 9, 60, 50));
        assertThat(facts.problemSolvingScore()).isEqualTo(57);
        assertThat(facts.firstPassCount()).isEqualTo(3);
        assertThat(facts.problemFailsBeforeAccepted()).containsExactly(0L, 0L, 0L, 2L, 1L);
        assertThat(facts.topicCounts()).containsEntry("배열", 2);
        assertThat(new ObjectMapper().writeValueAsString(facts)).doesNotContain("private-marker");
    }

    @Test
    void missingEvidenceRemainsNullAndOnlyMeasuredAxesContributeToComposite() {
        var firstPass = new LearningReportScores.AttemptEvidence(0, null, null, 0, 0, 0, 0, 0, "PYTHON", false);
        var facts = LearningReportScores.evaluate(List.of(
                problem(List.of("Lv.0"), firstPass),
                problem(List.of(), null),
                problem(List.of(), null),
                problem(List.of(), null),
                problem(List.of(), null)
        ), 1);

        assertThat(facts.radar()).isEqualTo(new LearningReportScores.Radar(100, null, null, 9, null, 7));
        assertThat(facts.problemSolvingScore()).isEqualTo(62);
        assertThat(facts.observedSubmissionCount()).isEqualTo(1);
        assertThat(facts.problemFailsBeforeAccepted()).containsExactly(0L, null, null, null, null);
    }

    @Test
    void unfinishedOrSystemErrorAttemptsDoNotEarnFirstPassOrInventRecovery() {
        var unclear = new LearningReportScores.AttemptEvidence(0, null, null, 0, 0, 0, 0, 0, "PYTHON", true);
        var uncertainRecovery = new LearningReportScores.AttemptEvidence(1, 1L, null, 1, 0, 0, 0, 0, "PYTHON", true);
        var facts = LearningReportScores.evaluate(List.of(
                problem(List.of(), unclear), problem(List.of(), uncertainRecovery)), 1);

        assertThat(facts.firstPassCount()).isZero();
        assertThat(facts.observedSubmissionCount()).isEqualTo(1);
        assertThat(facts.radar().firstPassAccuracy()).isZero();
        assertThat(facts.radar().errorRecovery()).isNull();
        assertThat(facts.problemFailsBeforeAccepted()).containsExactly(null, null);
        assertThat(facts.ordinaryErrorCounts()).containsEntry("WRONG_ANSWER", 1L);
    }

    private static LearningReportScores.ProblemEvidence problem(List<String> tags,
                                                                LearningReportScores.AttemptEvidence attempt) {
        return new LearningReportScores.ProblemEvidence("INITIATE", 3, tags, attempt);
    }
}
