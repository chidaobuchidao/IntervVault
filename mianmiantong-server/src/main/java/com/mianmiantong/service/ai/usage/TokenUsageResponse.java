package com.mianmiantong.service.ai.usage;

import java.time.LocalDate;
import java.util.List;

/** Dates use the reporting timezone; endDate is exclusive. Token totals contain known components only. */
public record TokenUsageResponse(
        String timezone, LocalDate startDate, LocalDate endDate, Metrics summary,
        List<Daily> daily, List<Group> models, List<Group> features, List<Group> users) {

    public record Metrics(long calls, long inputTokens, long outputTokens, long totalTokens,
                          long unknownCalls, long failedCalls) {
        static final Metrics ZERO = new Metrics(0, 0, 0, 0, 0, 0);
    }

    public record Daily(LocalDate date, long calls, long inputTokens, long outputTokens,
                        long totalTokens, long unknownCalls, long failedCalls) {
        static Daily of(LocalDate date, Metrics metrics) {
            return new Daily(date, metrics.calls(), metrics.inputTokens(), metrics.outputTokens(),
                    metrics.totalTokens(), metrics.unknownCalls(), metrics.failedCalls());
        }
    }

    public record Group(String key, long calls, long inputTokens, long outputTokens,
                        long totalTokens, long unknownCalls, long failedCalls) {
        static Group of(String key, Metrics metrics) {
            return new Group(key, metrics.calls(), metrics.inputTokens(), metrics.outputTokens(),
                    metrics.totalTokens(), metrics.unknownCalls(), metrics.failedCalls());
        }
    }
}
