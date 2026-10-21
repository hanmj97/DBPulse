package com.dbpulse;

import com.dbpulse.config.MssqlTargetProperties;
import com.dbpulse.desktop.WindowsDesktopBoot;
import com.dbpulse.desktop.DesktopStartupDiagnostics;
import com.dbpulse.desktop.DesktopInstanceHandoff;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(MssqlTargetProperties.class)
public class DbpulseApplication {
    public static void main(String[] args) {
        // Existing Docker and Windows dev scripts still launch normally.
        // Only jpackage app-image sets this explicit JVM property.
        if (args.length == 1 && "--desktop-provision".equals(args[0])) {
            WindowsDesktopBoot.provision();
            return;
        }
        if (args.length == 1 && "--desktop-check".equals(args[0])) {
            WindowsDesktopBoot.checkProvisioned();
            return;
        }
        boolean desktop = Boolean.getBoolean("dbpulse.desktop.enabled");
        try {
            if (desktop) {
                // Probe BEFORE DPAPI/Postgres/collector startup. Reopening an
                // already-running local DBPulse must not bind port 18081 again.
                DesktopInstanceHandoff.Result existing = DesktopInstanceHandoff.inspect();
                if (existing == DesktopInstanceHandoff.Result.DBPULSE_RUNNING) {
                    DesktopStartupDiagnostics.record("REOPENED_EXISTING_APP");
                    WindowsDesktopBoot.openDashboard();
                    return;
                }
                if (existing == DesktopInstanceHandoff.Result.OTHER_SERVICE_ON_PORT) {
                    throw new IllegalStateException("PORT_18081_ALREADY_IN_USE");
                }
                DesktopStartupDiagnostics.record("STARTING");
            }
            SpringApplication app = new SpringApplication(DbpulseApplication.class);
            if (desktop) {
                WindowsDesktopBoot.configure(app);
                app.addListeners((org.springframework.context.ApplicationListener<
                    org.springframework.boot.context.event.ApplicationReadyEvent>)
                    event -> DesktopStartupDiagnostics.record("READY"));
            }
            app.run(args);
        } catch (RuntimeException | Error failure) {
            if (desktop) {
                String code = DesktopStartupDiagnostics.categorize(failure);
                DesktopStartupDiagnostics.record(code);
                DesktopStartupDiagnostics.displayFailure(code);
            }
            throw failure;
        }
    }
}
