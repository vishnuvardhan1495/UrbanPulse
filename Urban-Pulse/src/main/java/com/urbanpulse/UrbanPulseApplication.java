package com.urbanpulse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class UrbanPulseApplication {

    public static void main(String[] args) {
        SpringApplication.run(UrbanPulseApplication.class, args);
    }
}
