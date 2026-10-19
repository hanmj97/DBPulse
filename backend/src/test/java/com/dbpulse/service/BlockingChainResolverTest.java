package com.dbpulse.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.dbpulse.domain.BlockingEdge;
import java.util.List;
import org.junit.jupiter.api.Test;

class BlockingChainResolverTest {
    private static BlockingEdge edge(int waiter, int blocker) {
        return new BlockingEdge(waiter, blocker, "LCK_M_X",
            11000, 12000, "suspended", "sleeping", 1, null, 0, false);
    }

    @Test
    void findsCandidateRootWhenBlockerIsSleepingAndHasNoActiveRequest() {
        var result = BlockingChainResolver.resolve(List.of(edge(80, 71)));
        assertThat(result.getFirst().rootCandidateSessionId()).isEqualTo(71);
        assertThat(result.getFirst().chainDepth()).isEqualTo(1);
        assertThat(result.getFirst().cycleDetected()).isFalse();
    }

    @Test
    void followsMultipleBlockingLevels() {
        var result = BlockingChainResolver.resolve(List.of(edge(80, 71), edge(71, 60)));
        assertThat(result.get(0).rootCandidateSessionId()).isEqualTo(60);
        assertThat(result.get(0).chainDepth()).isEqualTo(2);
        assertThat(result.get(1).rootCandidateSessionId()).isEqualTo(60);
    }

    @Test
    void marksCycleInsteadOfClaimingRoot() {
        var result = BlockingChainResolver.resolve(List.of(edge(80, 71), edge(71, 80)));
        assertThat(result.get(0).cycleDetected()).isTrue();
        assertThat(result.get(0).rootCandidateSessionId()).isNull();
    }
}
