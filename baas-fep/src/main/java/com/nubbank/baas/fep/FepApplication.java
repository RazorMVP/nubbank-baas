package com.nubbank.baas.fep;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class FepApplication {
    public static void main(String[] args) {
        SpringApplication.run(FepApplication.class, args);
    }
}
