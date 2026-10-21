package com.dbpulse.security;

import java.nio.file.Path;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TargetCredentialStoreConfiguration {

    @Bean
    public TargetCredentialStore targetCredentialStore(
        @Value("${dbpulse.credentials.windows-dpapi-enabled:false}") boolean enabled) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
            .startsWith("windows");
        String localAppData = System.getenv("LOCALAPPDATA");
        String currentUser = System.getProperty("user.name", "");
        boolean sharedSystemAccount = currentUser.equalsIgnoreCase("SYSTEM")
            || currentUser.equalsIgnoreCase("LOCAL SERVICE")
            || currentUser.equalsIgnoreCase("NETWORK SERVICE");
        if (!enabled || !windows || sharedSystemAccount
            || localAppData == null || localAppData.isBlank()) {
            return new DisabledTargetCredentialStore();
        }
        return new WindowsDpapiCredentialStore(
            Path.of(localAppData, "DBPulse", "credentials"));
    }
}
