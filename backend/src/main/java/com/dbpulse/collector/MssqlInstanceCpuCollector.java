package com.dbpulse.collector;

import com.dbpulse.config.MssqlTargetProperties;
import java.sql.*;
import java.util.Properties;
import org.springframework.stereotype.Component;

/** Optional lab-only estimate of SQL Server PROCESS CPU%, not per-database CPU%. 
 * SchedulerMonitorEvent XML is internal and NOT a supported stable data contract. */
@Component
public class MssqlInstanceCpuCollector {
    private static final String CPU_SQL = """
        /* DBPULSE_INTERNAL_CPU_SAMPLE_V1 */
        SELECT
          q.x.value('(Record/SchedulerMonitorEvent/SystemHealth/ProcessUtilization)[1]', 'int') AS sql_process_cpu_percent,
          q.x.value('(Record/SchedulerMonitorEvent/SystemHealth/SystemIdle)[1]', 'int') AS host_idle_percent,
          s.ms_ticks - q.sample_tick_ms AS sample_age_ms
        FROM (
          SELECT TOP (1) TRY_CONVERT(xml,rb.record) AS x, rb.[timestamp] AS sample_tick_ms
          FROM sys.dm_os_ring_buffers rb
          WHERE rb.ring_buffer_type = N'RING_BUFFER_SCHEDULER_MONITOR'
            AND rb.record LIKE N'%<SystemHealth>%'
          ORDER BY rb.[timestamp] DESC
        ) q
        CROSS JOIN sys.dm_os_sys_info s
        """;
    private final MssqlTargetProperties target;
    public MssqlInstanceCpuCollector(MssqlTargetProperties target) { this.target = target; }

    public Sample collect() throws SQLException {
        if (!target.isEnabled()) throw new IllegalStateException("MSSQL_NOT_CONFIGURED");
        if (target.getUsername() == null || target.getUsername().isBlank()
            || target.getPassword() == null || target.getPassword().isBlank())
            throw new IllegalStateException("MSSQL_LOGIN_NOT_CONFIGURED");
        String url="jdbc:sqlserver://"+target.getHost()+":"+target.getPort()
            +";databaseName="+target.getDatabase()+";encrypt=true;trustServerCertificate="
            +target.isTrustServerCertificate()
            +";loginTimeout=3;applicationIntent=ReadOnly;applicationName=DBPulseCpuSample";
        Properties auth=new Properties();
        auth.setProperty("user",target.getUsername());
        auth.setProperty("password",target.getPassword());
        try(Connection conn=DriverManager.getConnection(url,auth)) {
            conn.setReadOnly(true);
            try(PreparedStatement stmt=conn.prepareStatement(CPU_SQL)) {
                stmt.setQueryTimeout(4);
                try(ResultSet rs=stmt.executeQuery()) {
                    if(!rs.next() || rs.getObject("sql_process_cpu_percent")==null
                        || rs.getObject("host_idle_percent")==null)
                        return new Sample("UNAVAILABLE",null,null,null);
                    return evaluate(rs.getInt("sql_process_cpu_percent"),
                      rs.getInt("host_idle_percent"),rs.getLong("sample_age_ms"));
                }
            }
        }
    }
    static Sample evaluate(int processCpu,int idleCpu,long ageMs) {
        if(processCpu<0||processCpu>100||idleCpu<0||idleCpu>100
            ||processCpu+idleCpu>100||ageMs<0||ageMs>180_000)
            return new Sample("UNAVAILABLE",null,null,ageMs<0?null:ageMs);
        return new Sample("ONLINE",processCpu,100-idleCpu,ageMs);
    }
    public record Sample(String status,Integer sqlProcessCpuPercent,
                         Integer hostBusyCpuPercent,Long ringBufferAgeMs) {}
}
