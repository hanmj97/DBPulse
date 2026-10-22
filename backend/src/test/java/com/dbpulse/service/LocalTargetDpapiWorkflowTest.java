package com.dbpulse.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.dbpulse.config.MssqlTargetProperties;
import com.dbpulse.security.TargetCredentialStore;
import com.dbpulse.security.DisabledTargetCredentialStore;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class LocalTargetDpapiWorkflowTest {
    private static final String ID="e89fda4a-27e5-4e38-91b7-ddaac822eed3";

    @Test void dockerOrLinuxVaultIsExplicitlyUnavailableAndNeverPersistsSecrets() {
        var config = new MssqlTargetProperties();
        var disabled = new LocalTargetService(new JdbcTemplate(),true,false,
            "localhost",config,new DisabledTargetCredentialStore());
        assertFalse(disabled.credentialCapability().available());
        assertEquals("UNAVAILABLE_IN_DOCKER",disabled.credentialCapability().provider());
        assertThrows(ResponseStatusException.class,
            ()->disabled.rememberActiveCredential(ID));
        assertThrows(ResponseStatusException.class,
            ()->disabled.startWithSavedCredential(ID,false));
    }

    @Test void saveOnlyActiveUiPasswordAndNeverReturnsIt() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        TargetCredentialStore vault=mock(TargetCredentialStore.class);
        when(vault.isAvailable()).thenReturn(true);
        MssqlTargetProperties current=new MssqlTargetProperties();
        var service=new LocalTargetService(jdbc,true,false,"localhost",current,vault);

        assertThrows(ResponseStatusException.class,
            ()->service.rememberActiveCredential(ID));
        assertTrue(current.activateOnce(new MssqlTargetProperties.RuntimeTarget(
            ID,"Development","localhost",1433,"DBPulseLab","readonly","secret-in-memory",false)));

        var saved=service.rememberActiveCredential(ID);
        assertEquals("SAVED",saved.status());
        assertEquals(ID,saved.id());
        assertFalse(saved.toString().contains("secret-in-memory"));
        verify(vault).save(ID,"secret-in-memory");

        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.rememberActiveCredential("fa7e2402-c4cb-4dba-8dd4-8e11c6e5f8d2"))
            .getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test void savedSecretLookupRequiresRegistryIdAndDoesNotExposePassword() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        TargetCredentialStore vault=mock(TargetCredentialStore.class);
        when(vault.isAvailable()).thenReturn(true);
        when(vault.load(ID)).thenReturn(Optional.empty());
        // No saved blob => explicit error. Don't attempt new JDBC connection.
        var service=new LocalTargetService(jdbc,true,false,"localhost",
            new MssqlTargetProperties(),vault);
        when(jdbc.query(anyString(),any(org.springframework.jdbc.core.RowMapper.class),eq(ID)))
            .thenAnswer(invocation -> {
                org.springframework.jdbc.core.RowMapper<?> mapper=invocation.getArgument(1);
                // Use a minimal SQL row stub to simulate registered metadata.
                java.sql.ResultSet row=mock(java.sql.ResultSet.class);
                when(row.getString("display_name")).thenReturn("Local");
                when(row.getString("server_host")).thenReturn("localhost");
                when(row.getInt("server_port")).thenReturn(1433);
                when(row.getString("database_name")).thenReturn("DBPulseLab");
                when(row.getString("login_name")).thenReturn("reader");
                return List.of(mapper.mapRow(row,0));
            });
        var ex=assertThrows(ResponseStatusException.class,
            ()->service.startWithSavedCredential(ID,false));
        assertEquals(HttpStatus.CONFLICT,ex.getStatusCode());
        verify(vault).load(ID);
    }

    @Test void forgetDeletesOnlyEncryptedSecretNotRegistryRow() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        TargetCredentialStore vault=mock(TargetCredentialStore.class);
        when(vault.isAvailable()).thenReturn(true);
        var service=new LocalTargetService(jdbc,true,false,"localhost",
            new MssqlTargetProperties(),vault);
        var forgotten=service.forgetCredential(ID);
        assertEquals("FORGOTTEN",forgotten.status());
        verify(vault).remove(ID);
        verifyNoInteractions(jdbc);
    }
}
