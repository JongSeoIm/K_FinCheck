package com.kfincheck;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
public class KFinCheckApplication {

    public static void main(String[] args) {
        SpringApplication.run(KFinCheckApplication.class, args);
    }
}

