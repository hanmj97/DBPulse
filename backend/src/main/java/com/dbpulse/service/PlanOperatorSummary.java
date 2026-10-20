package com.dbpulse.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates only an allowlist of structural ShowPlan PhysicalOp values.
 * Query text, object names, index names and predicates are never returned.
 */
public final class PlanOperatorSummary {
    private PlanOperatorSummary() {}

    private static final Map<String, String> LABELS = Map.ofEntries(
        Map.entry("Table Scan", "TABLE_SCAN"),
        Map.entry("Clustered Index Scan", "CLUSTERED_INDEX_SCAN"),
        Map.entry("Index Scan", "INDEX_SCAN"),
        Map.entry("Key Lookup", "KEY_LOOKUP"),
        Map.entry("RID Lookup", "RID_LOOKUP"),
        Map.entry("Sort", "SORT"),
        Map.entry("Hash Match", "HASH_MATCH"),
        Map.entry("Nested Loops", "NESTED_LOOPS"),
        Map.entry("Merge Join", "MERGE_JOIN"),
        Map.entry("Index Seek", "INDEX_SEEK"),
        Map.entry("Clustered Index Seek", "CLUSTERED_INDEX_SEEK"),
        Map.entry("Filter", "FILTER")
    );

    public static Result summarize(List<String> physicalOps, int maxOps) {
        if (maxOps < 1) throw new IllegalArgumentException("maxOps");
        boolean truncated = physicalOps.size() > maxOps;
        Map<String, Integer> counts = new LinkedHashMap<>();
        int other = 0;
        for (String physicalOp : physicalOps.subList(0, Math.min(maxOps, physicalOps.size()))) {
            String code = physicalOp == null ? null : LABELS.get(physicalOp);
            if (code == null) other++;
            else counts.merge(code, 1, Integer::sum);
        }
        List<OperatorCount> operations = new ArrayList<>();
        counts.forEach((type, count) -> operations.add(new OperatorCount(type, count)));
        if (other > 0) operations.add(new OperatorCount("OTHER_OPERATOR", other));
        operations.sort((a, b) -> {
            int compare = Integer.compare(b.count(), a.count());
            return compare == 0 ? a.type().compareTo(b.type()) : compare;
        });
        return new Result(List.copyOf(operations), truncated, Math.min(maxOps, physicalOps.size()));
    }

    public record OperatorCount(String type, int count) {}
    public record Result(List<OperatorCount> operators, boolean truncated, int sampledNodes) {}
}
