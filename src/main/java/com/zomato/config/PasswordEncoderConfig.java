package com.zomato.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Separate config for PasswordEncoder to avoid circular dependency:
 * JwtAuthenticationFilter → UserService → SecurityConfig → JwtAuthenticationFilter
 *
 * By extracting PasswordEncoder here, UserService can depend on this bean
 * without touching SecurityConfig.
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
