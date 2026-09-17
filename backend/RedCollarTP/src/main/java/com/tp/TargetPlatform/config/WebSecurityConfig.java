package com.tp.TargetPlatform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import lombok.RequiredArgsConstructor;

/**
 * Baseline security configuration for the Target Platform.
 *
 * <p>Spring Security is now on the classpath (via spring-boot-starter-security in
 * Joanna's authoritative POM). Without an explicit SecurityFilterChain, Spring
 * Boot auto-configuration secures <em>every</em> endpoint — blocking the
 * Camunda webapp (Cockpit/Tasklist/Admin), the Target Platform portal served at
 * "/", actuator endpoints, and RabbitMQ messaging infrastructure.
 *
 * <p>This configuration mirrors the existing {@code camundademo} template's
 * {@code WebSecurityConfig}: permit all requests and disable CSRF so the
 * Target Platform remains functional out of the box.
 *
 * <p>A more granular security architecture (OAuth2, role-based access) can be
 * layered on top once the runtime baseline is verified in Prompt 3/4.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class WebSecurityConfig {
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorizeRequests -> authorizeRequests.anyRequest()
                .permitAll())
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
