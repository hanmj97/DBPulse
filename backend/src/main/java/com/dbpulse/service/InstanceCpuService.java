package com.dbpulse.service;

import com.dbpulse.collector.MssqlInstanceCpuCollector;
import com.dbpulse.config.MssqlTargetProperties;
import java.sql.SQLException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Optional, 60s, independent of critical 5s incident and Query Store collectors. */
@Service
public class InstanceCpuService {
    private static final Logger log=LoggerFactory.getLogger(InstanceCpuService.class);
    public static final String LIMITATION=
        "SQL Server 프로세스가 사용하는 인스턴스 전체 CPU 비율 추정치입니다. 특정 데이터베이스 또는 쿼리의 CPU 사용률이 아닙니다. "+
        "호스트 CPU 사용률은 다른 프로세스도 포함합니다. 링 버퍼 XML 형식은 지원이 보장되지 않는 진단 데이터입니다. "+
        "개별 쿼리 비용은 Query Store CPU 시간(ms)으로 분석하세요.";
    private final MssqlTargetProperties target;
    private final MssqlInstanceCpuCollector collector;
    private final boolean enabled;
    private final AtomicReference<CpuStatus> last=new AtomicReference<>();
    public InstanceCpuService(MssqlTargetProperties target,MssqlInstanceCpuCollector collector,
                              @Value("${dbpulse.cpu.enabled:false}") boolean enabled) {
        this.target=target;this.collector=collector;this.enabled=enabled;
        last.set(status(enabled?"INITIALIZING":"DISABLED",null,null,null,null));
    }
    @Scheduled(fixedDelayString="${dbpulse.cpu.interval-ms:60000}",initialDelay=20000)
    public void sample() {
        if(!enabled)return;
        if(!target.isEnabled()){last.set(status("NOT_CONFIGURED",null,null,null,null));return;}
        try {
            var r=collector.collect();
            last.set(status(r.status(),r.sqlProcessCpuPercent(),r.hostBusyCpuPercent(),
                Instant.now(),r.ringBufferAgeMs()));
        }catch(SQLException ex){
            String code=ex.getErrorCode()==297||ex.getErrorCode()==229
                ?"PERMISSION_DENIED":"UNAVAILABLE";
            log.warn("Optional SQL Server CPU sample failed SQLState={} code={}",
                ex.getSQLState(),ex.getErrorCode());
            last.set(status(code,null,null,Instant.now(),null));
        }catch(Exception ex){
            log.warn("Optional SQL Server CPU sample unavailable ({})",ex.getClass().getSimpleName());
            last.set(status("UNAVAILABLE",null,null,Instant.now(),null));
        }
    }
    public CpuStatus current(){return last.get();}
    private static CpuStatus status(String status,Integer sqlCpu,Integer hostCpu,
                                    Instant at,Long ageMs){
        return new CpuStatus(status,"SQL_SERVER_INSTANCE","SCHEDULER_RING_BUFFER_EXPERIMENTAL",
            sqlCpu,hostCpu,at,ageMs,LIMITATION);
    }
    public record CpuStatus(String status,String scope,String source,
                            Integer sqlProcessCpuPercent,Integer hostBusyCpuPercent,
                            Instant collectedAt,Long ringBufferAgeMs,String limitation){}
}
