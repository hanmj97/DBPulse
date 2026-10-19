package com.dbpulse.service;

/** Metric-based triage only: a candidate for investigation, never a verified root cause. */
public final class QueryDiagnosis {
    private QueryDiagnosis() {}

    public static String classify(double avgDurationMs, double avgCpuMs,
                                  double avgLogicalReadsPages) {
        if (avgDurationMs >= 500 && avgLogicalReadsPages >= 1000) return "SLOW_HIGH_READS";
        if (avgDurationMs >= 500 && avgCpuMs >= 200) return "SLOW_HIGH_CPU";
        if (avgDurationMs >= 500) return "SLOW_AVERAGE";
        if (avgLogicalReadsPages >= 1000) return "HIGH_READS";
        if (avgCpuMs >= 200) return "HIGH_CPU";
        return "OBSERVE";
    }
}
