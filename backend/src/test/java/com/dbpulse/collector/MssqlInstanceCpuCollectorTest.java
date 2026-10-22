package com.dbpulse.collector;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class MssqlInstanceCpuCollectorTest {
    @Test
    void reportsInstanceProcessPercentRatherThanPerDatabaseCpu() {
        var r=MssqlInstanceCpuCollector.evaluate(31,40,45_000);
        assertThat(r.status()).isEqualTo("ONLINE");
        assertThat(r.sqlProcessCpuPercent()).isEqualTo(31);
        assertThat(r.hostBusyCpuPercent()).isEqualTo(60);
    }
    @Test
    void staleOrInvalidSamplesProduceNullPercentages() {
        var invalid=new MssqlInstanceCpuCollector.Sample[]{
            MssqlInstanceCpuCollector.evaluate(35,40,250_000),
            MssqlInstanceCpuCollector.evaluate(101,0,1_000),
            MssqlInstanceCpuCollector.evaluate(-1,50,1_000),
            MssqlInstanceCpuCollector.evaluate(70,60,1_000),
            MssqlInstanceCpuCollector.evaluate(20,20,-1)
        };
        for(var sample:invalid){
            assertThat(sample.status()).isEqualTo("UNAVAILABLE");
            assertThat(sample.sqlProcessCpuPercent()).isNull();
        }
    }
}
