package com.dbpulse.service;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class WaitCategoryTest {
    @Test
    void classifiesRelevantWaitTypePrefixesConservatively() {
        assertThat(WaitCategory.classify("LCK_M_X")).isEqualTo("LOCK");
        assertThat(WaitCategory.classify("LCK_M_S")).isEqualTo("LOCK");
        assertThat(WaitCategory.classify("PAGEIOLATCH_SH")).isEqualTo("STORAGE_IO");
        assertThat(WaitCategory.classify("WRITELOG")).isEqualTo("TRANSACTION_LOG");
        assertThat(WaitCategory.classify("RESOURCE_SEMAPHORE")).isEqualTo("MEMORY");
        assertThat(WaitCategory.classify("CXCONSUMER")).isEqualTo("PARALLELISM");
        assertThat(WaitCategory.classify("SOS_SCHEDULER_YIELD")).isEqualTo("SCHEDULER");
        assertThat(WaitCategory.classify("ASYNC_NETWORK_IO")).isEqualTo("NETWORK");
        assertThat(WaitCategory.classify("HADR_SYNC_COMMIT")).isEqualTo("AVAILABILITY");
        assertThat(WaitCategory.classify(null)).isEqualTo("OTHER");
        assertThat(WaitCategory.classify("SLEEP_TASK")).isEqualTo("OTHER");
    }
}
