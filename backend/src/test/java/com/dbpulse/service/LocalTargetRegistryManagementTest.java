package com.dbpulse.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.dbpulse.config.MssqlTargetProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/** The registry stores metadata, never SQL passwords or historic samples. */
class LocalTargetRegistryManagementTest {
    private static final String ID="e89fda4a-27e5-4e38-91b7-ddaac822eed3";
    private static final String OTHER="fa7e2402-c4cb-4dba-8dd4-8e11c6e5f8d2";

    private static LocalTargetService service(JdbcTemplate jdbc,MssqlTargetProperties settings) {
        return new LocalTargetService(jdbc,true,false,"host.docker.internal",settings);
    }

    @Test void renameChangesOnlyDisplayNameForInactiveUuid() {
        var jdbc=mock(JdbcTemplate.class);
        var settings=new MssqlTargetProperties();
        when(jdbc.update("UPDATE local_target_registry SET display_name = ? WHERE id = ?",
            "운영 DB",ID)).thenReturn(1);

        var result=service(jdbc,settings).renameRegistered(ID," 운영 DB ");
        assertEquals("RENAMED",result.status());
        assertEquals(ID,result.id());
        verify(jdbc).update("UPDATE local_target_registry SET display_name = ? WHERE id = ?",
            "운영 DB",ID);
        verifyNoMoreInteractions(jdbc);
    }

    @Test void unregisterRemovesOnlyRegistryRowWithoutTouchingHistoryOrMssql() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.update("DELETE FROM local_target_registry WHERE id = ?",OTHER))
            .thenReturn(1);
        var result=service(jdbc,new MssqlTargetProperties()).unregister(OTHER);
        assertEquals("UNREGISTERED",result.status());
        assertEquals("TARGET_UNREGISTERED_HISTORY_PRESERVED",result.code());
        verify(jdbc).update("DELETE FROM local_target_registry WHERE id = ?",OTHER);
        verifyNoMoreInteractions(jdbc);
    }

    @Test void modifyingActiveTargetRequiresDisconnect() {
        var jdbc=mock(JdbcTemplate.class);
        var settings=new MssqlTargetProperties();
        assertTrue(settings.activateOnce(new MssqlTargetProperties.RuntimeTarget(
            ID,"Live","host.docker.internal",1433,"DBPulseLab","reader","secret",false)));
        var registry=service(jdbc,settings);

        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->registry.renameRegistered(ID,"Different")).getStatusCode());
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->registry.unregister(ID)).getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test void rejectInvalidIdBlankLabelAndMissingRowWithoutMutation() {
        var jdbc=mock(JdbcTemplate.class);
        var registry=service(jdbc,new MssqlTargetProperties());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->registry.unregister("local-mssql")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->registry.renameRegistered(ID,"\n")).getStatusCode());
        verifyNoInteractions(jdbc);
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->registry.unregister(ID)).getStatusCode());
    }

    @Test void featureDisabledDisallowsLocalRegistryMutation() {
        var jdbc=mock(JdbcTemplate.class);
        var disabled=new LocalTargetService(jdbc,false,false,"localhost",
            new MssqlTargetProperties());
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->disabled.unregister(ID)).getStatusCode());
        verifyNoInteractions(jdbc);
    }
}
