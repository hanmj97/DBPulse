package com.dbpulse.service;

import static org.junit.jupiter.api.Assertions.*;
import com.dbpulse.config.MssqlTargetProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class LocalTargetDisconnectTest {
    private static MssqlTargetProperties.RuntimeTarget active(String id) {
        return new MssqlTargetProperties.RuntimeTarget(id,"QA","host.docker.internal",
            1433,"DBPulseLab","reader","memory-only",true);
    }

    @Test void stopOnlyExactActiveUiTargetAndPreserveRegistry() {
        var props=new MssqlTargetProperties();
        var service=new LocalTargetService(new JdbcTemplate(),true,true,
            "host.docker.internal",props);
        assertTrue(props.activateOnce(active("registered-uuid")));
        var foreign=assertThrows(ResponseStatusException.class,
            () -> service.stopMonitoring("another-id"));
        assertEquals(HttpStatus.CONFLICT,foreign.getStatusCode());
        assertTrue(props.isEnabled());
        var stopped=service.stopMonitoring("registered-uuid");
        assertEquals("DISCONNECTED",stopped.status());
        assertEquals("MONITORING_STOPPED",stopped.code());
        assertEquals("registered-uuid",stopped.id());
        assertFalse(props.isUiActivated());
        assertEquals("",props.getPassword());
        assertThrows(ResponseStatusException.class,
            () -> service.stopMonitoring("registered-uuid"));
    }

    @Test void cannotStopLegacyEnvConfiguredMonitorOrDisabledFeature() {
        var props=new MssqlTargetProperties();
        props.setEnabled(true);
        var enabled=new LocalTargetService(new JdbcTemplate(),true,true,
            "host.docker.internal",props);
        assertThrows(ResponseStatusException.class,
            () -> enabled.stopMonitoring("local-mssql"));
        assertTrue(props.isEnabled());
        var disabled=new LocalTargetService(new JdbcTemplate(),false,true,
            "host.docker.internal",props);
        var ex=assertThrows(ResponseStatusException.class,
            () -> disabled.stopMonitoring("local-mssql"));
        assertEquals(HttpStatus.NOT_FOUND,ex.getStatusCode());
    }
}
