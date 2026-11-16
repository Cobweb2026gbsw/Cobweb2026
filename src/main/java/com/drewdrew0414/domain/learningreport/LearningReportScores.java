package com.drewdrew0414.domain.learningreport;

import com.drewdrew0414.domain.submission.Language;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class LearningReportScores {
    static final int BATCH_SIZE = 5;
    static final int PRACTICE_DAYS = 14;
    private static final List<String> RANKS =
            List.of("INITIATE", "SKILLED", "ELITE", "EXPERT", "LEGEND", "MYTHIC", "ABSOLUTE");
    private static final Set<String> LANGUAGES =
            Set.copyOf(Arrays.stream(Language.values()).map(Language::name).toList());

    // 작성자가 넣은 임의 태그 원문은 전송하지 않습니다. 등록된 이름만 고정 주제로 매핑합니다.
    private static final Map<String, List<String>> TOPICS = Map.ofEntries(
            Map.entry("구현", List.of("구현")),
            Map.entry("변수", List.of("기초 문법")),
            Map.entry("함수", List.of("함수")),
            Map.entry("반환", List.of("함수")),
            Map.entry("형 변환", List.of("기초 문법")),
            Map.entry("산술 연산", List.of("수학")),
            Map.entry("실수", List.of("수학")),
            Map.entry("수학", List.of("수학")),
            Map.entry("정수론", List.of("수학")),
            Map.entry("조건문", List.of("조건문")),
            Map.entry("비교", List.of("조건문")),
            Map.entry("논리 연산", List.of("조건문")),
            Map.entry("반복문", List.of("반복문")),
            Map.entry("배열", List.of("배열")),
            Map.entry("2차원 배열", List.of("배열")),
            Map.entry("문자열", List.of("문자열")),
            Map.entry("배열·문자열", List.of("배열", "문자열")),
            Map.entry("문자열·해시", List.of("문자열", "자료구조")),
            Map.entry("정렬", List.of("정렬")),
            Map.entry("탐색", List.of("탐색")),
            Map.entry("정렬·탐색", List.of("정렬", "탐색")),
            Map.entry("정렬·이진 탐색", List.of("정렬", "탐색")),
            Map.entry("이진 탐색", List.of("탐색")),
            Map.entry("자료구조", List.of("자료구조")),
            Map.entry("투 포인터·누적 합", List.of("투 포인터", "누적 합")),
            Map.entry("완전탐색·그리디", List.of("탐색", "그리디")),
            Map.entry("재귀·백트래킹", List.of("재귀", "백트래킹")),
            Map.entry("DFS·BFS", List.of("그래프")),
            Map.entry("동적 계획법", List.of("동적 계획법")),
            Map.entry("그래프·트리", List.of("그래프", "트리")),
            Map.entry("그래프 탐색", List.of("그래프")),
            Map.entry("너비 우선 탐색", List.of("그래프")),
            Map.entry("그리디·탐색·복합 구현", List.of("그리디", "탐색", "구현"))
    );

    private LearningReportScores() {}

    static List<String> normalizedTopics(List<String> tags) {
        return tags.stream().flatMap(tag -> TOPICS.getOrDefault(tag, List.of()).stream()).distinct().sorted().toList();
    }

    static Facts evaluate(List<ProblemEvidence> problems, int activeDays) {
        int firstPassCount = 0;
        int observedSubmissionCount = 0;
        int difficultyTotal = 0;
        int difficultyCount = 0;
        var ordinaryErrors = new TreeMap<String, Long>();
        var limitErrors = new TreeMap<String, Long>();
        var subjectCounts = new TreeMap<String, Integer>();
        var rankCounts = new TreeMap<String, Integer>();
        var languages = new TreeMap<String, Integer>();
        var failuresByProblem = new ArrayList<Long>();
        var errorRecovery = new ArrayList<Integer>();
        var limitRecovery = new ArrayList<Integer>();
        for (ProblemEvidence problem : problems) {
            int rank = RANKS.indexOf(problem.rankType());
            if (rank >= 0 && problem.rankLevel() >= 1 && problem.rankLevel() <= 5) {
                difficultyTotal += rank * 5 + (6 - problem.rankLevel());
                difficultyCount++;
                rankCounts.merge(RANKS.get(rank), 1, Integer::sum);
            }
            normalizedTopics(problem.tags()).forEach(topic -> subjectCounts.merge(topic, 1, Integer::sum));
            AttemptEvidence attempt = problem.attempt();
            failuresByProblem.add(attempt == null || attempt.earlierAttemptUncertain() ? null : attempt.failures());
            if (attempt == null) continue;
            if (attempt.failures() > 0 || !attempt.earlierAttemptUncertain()) observedSubmissionCount++;
            if (attempt.failures() == 0 && !attempt.earlierAttemptUncertain()) firstPassCount++;
            if (!attempt.earlierAttemptUncertain()) {
                addRecovery(errorRecovery, attempt.failures(), attempt.firstOrdinaryError());
                addRecovery(limitRecovery, attempt.failures(), attempt.firstLimitError());
            }
            mergeCount(ordinaryErrors, "WRONG_ANSWER", attempt.wrongAnswers());
            mergeCount(ordinaryErrors, "COMPILATION_ERROR", attempt.compilationErrors());
            mergeCount(ordinaryErrors, "RUNTIME_ERROR", attempt.runtimeErrors());
            mergeCount(limitErrors, "TIME_LIMIT_EXCEEDED", attempt.timeLimits());
            mergeCount(limitErrors, "MEMORY_LIMIT_EXCEEDED", attempt.memoryLimits());
            if (attempt.acceptedLanguage() != null && LANGUAGES.contains(attempt.acceptedLanguage()))
                languages.merge(attempt.acceptedLanguage(), 1, Integer::sum);
        }
        var radar = new Radar(
                percentage(firstPassCount, observedSubmissionCount),
                average(errorRecovery),
                average(limitRecovery),
                percentage(difficultyTotal, difficultyCount * 5 * RANKS.size()),
                subjectCounts.isEmpty() ? null : percentage(Math.min(BATCH_SIZE, subjectCounts.size()), BATCH_SIZE),
                percentage(activeDays, PRACTICE_DAYS));
        return new Facts(problems.size(), firstPassCount, observedSubmissionCount, ordinaryErrors, limitErrors,
                failuresByProblem, rankCounts, subjectCounts, languages, activeDays, radar, composite(radar));
    }

    private static void mergeCount(Map<String, Long> counts, String key, long value) {
        if (value > 0) counts.merge(key, value, Long::sum);
    }

    private static void addRecovery(List<Integer> scores, long failures, Long firstFailureOrder) {
        // 첫 오류 이후 다음 제출에서 해결하면 100점입니다. 이후 제출마다 회복 점수가 줄어듭니다.
        if (firstFailureOrder != null) scores.add(percentage(1, failures - firstFailureOrder + 1));
    }

    private static Integer average(List<Integer> scores) {
        if (scores.isEmpty()) return null;
        return (int) Math.round(scores.stream().mapToInt(Integer::intValue).average().orElseThrow());
    }

    private static Integer percentage(long numerator, long denominator) {
        return denominator > 0 ? (int) Math.round(100.0 * numerator / denominator) : null;
    }

    private static Integer composite(Radar radar) {
        Integer[] components = {radar.firstPassAccuracy(), radar.errorRecovery(), radar.limitRecovery(),
                radar.difficultyChallenge()};
        int[] weights = {35, 25, 15, 25};
        int total = 0;
        int denominator = 0;
        for (int i = 0; i < components.length; i++) {
            if (components[i] == null) continue;
            total += components[i] * weights[i];
            denominator += weights[i];
        }
        return denominator == 0 ? null : (int) Math.round((double) total / denominator);
    }

    record ProblemEvidence(String rankType, int rankLevel, List<String> tags, AttemptEvidence attempt) {}

    record AttemptEvidence(long failures, Long firstOrdinaryError, Long firstLimitError, long wrongAnswers,
                           long compilationErrors, long runtimeErrors, long timeLimits, long memoryLimits,
                           String acceptedLanguage, boolean earlierAttemptUncertain) {}

    public record Radar(Integer firstPassAccuracy, Integer errorRecovery, Integer limitRecovery,
                        Integer difficultyChallenge, Integer topicVariety, Integer practiceConsistency) {}

    record Facts(int solvedCount, int firstPassCount, int observedSubmissionCount,
                 Map<String, Long> ordinaryErrorCounts, Map<String, Long> limitErrorCounts,
                 List<Long> problemFailsBeforeAccepted, Map<String, Integer> difficultyCounts,
                 Map<String, Integer> topicCounts, Map<String, Integer> languages, int activeDaysInLastTwoWeeks,
                 Radar radar, Integer problemSolvingScore) {}
}
