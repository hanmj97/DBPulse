package com.dbpulse;

import com.dbpulse.config.MssqlTargetProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(MssqlTargetProperties.class)
public class DbpulseApplication {
    public static void main(String[] args) {
        SpringApplication.run(DbpulseApplication.class, args);
    }
}
