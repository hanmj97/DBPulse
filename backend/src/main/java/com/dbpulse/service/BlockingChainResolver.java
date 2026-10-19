package com.dbpulse.service;

import com.dbpulse.domain.BlockingEdge;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Derives candidate blocking paths from the edges observed in a single bounded sample. */
public final class BlockingChainResolver {
    private BlockingChainResolver() {}

    public static List<BlockingEdge> resolve(List<BlockingEdge> raw) {
        Map<Integer, Integer> nextByWaiter = new HashMap<>();
        for (BlockingEdge edge : raw) {
            if (edge.blockedSessionId() > 0 && edge.blockingSessionId() > 0) {
                nextByWaiter.put(edge.blockedSessionId(), edge.blockingSessionId());
            }
        }
        List<BlockingEdge> resolved = new ArrayList<>(raw.size());
        for (BlockingEdge edge : raw) {
            int current = edge.blockedSessionId();
            int depth = 0;
            boolean cycle = false;
            Integer root = null;
            Set<Integer> visited = new HashSet<>();
            visited.add(current);
            for (int i = 0; i <= 100; i++) {
                Integer next = nextByWaiter.get(current);
                if (next == null) {
                    root = current;
                    break;
                }
                depth++;
                current = next;
                if (!visited.add(current)) {
                    cycle = true;
                    break;
                }
                if (depth >= 100) break; // bounded; unresolved rather than false root
            }
            resolved.add(new BlockingEdge(
                edge.blockedSessionId(), edge.blockingSessionId(), edge.waitType(),
                edge.waitMs(), edge.elapsedMs(), edge.blockedStatus(),
                edge.blockerStatus(), edge.blockerOpenTransactions(),
                cycle ? null : root, depth, cycle));
        }
        return List.copyOf(resolved);
    }
}
