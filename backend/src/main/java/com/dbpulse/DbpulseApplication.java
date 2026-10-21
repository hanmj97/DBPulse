package com.dbpulse;

import com.dbpulse.config.MssqlTargetProperties;
import com.dbpulse.desktop.WindowsDesktopBoot;
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
        SpringApplication app = new SpringApplication(DbpulseApplication.class);
        if (Boolean.getBoolean("dbpulse.desktop.enabled")) {
            WindowsDesktopBoot.configure(app);
        }
        app.run(args);
    }
}
