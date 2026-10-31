package com.crosspaynet.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfiguration {

    @Bean
    public CookieCsrfTokenRepository csrfTokenRepository() {
        return CookieCsrfTokenRepository.withHttpOnlyFalse();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, CookieCsrfTokenRepository csrfTokenRepository) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/error",
                                "/api/v1/portal/csrf",
                                "/api/v1/auth/csrf"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .httpBasic(Customizer.withDefaults())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                )
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${crosspaynet.security.cors.allowed-origins:http://localhost:3000}") String allowedOrigins
    ) {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD"));
        config.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "X-XSRF-TOKEN",
                "X-Requested-With",
                "Accept",
                "Origin"
        ));
        config.setExposedHeaders(List.of("Location", "X-XSRF-TOKEN"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(
            @Value("${crosspaynet.security.users.customer.username}") String customerUser,
            @Value("${crosspaynet.security.users.customer.password}") String customerPass,
            @Value("${crosspaynet.security.users.compliance.username}") String complianceUser,
            @Value("${crosspaynet.security.users.compliance.password}") String compliancePass,
            @Value("${crosspaynet.security.users.operations.username}") String operationsUser,
            @Value("${crosspaynet.security.users.operations.password}") String operationsPass,
            @Value("${crosspaynet.security.users.admin.username}") String adminUser,
            @Value("${crosspaynet.security.users.admin.password}") String adminPass,
            PasswordEncoder passwordEncoder
    ) {
        validateCredential("crosspaynet.security.users.customer.username", customerUser);
        validateCredential("crosspaynet.security.users.customer.password", customerPass);
        validateCredential("crosspaynet.security.users.compliance.username", complianceUser);
        validateCredential("crosspaynet.security.users.compliance.password", compliancePass);
        validateCredential("crosspaynet.security.users.operations.username", operationsUser);
        validateCredential("crosspaynet.security.users.operations.password", operationsPass);
        validateCredential("crosspaynet.security.users.admin.username", adminUser);
        validateCredential("crosspaynet.security.users.admin.password", adminPass);

        UserDetails customer = User.builder()
                .passwordEncoder(passwordEncoder::encode)
                .username(customerUser)
                .password(customerPass)
                .roles(SecurityRoles.CUSTOMER)
                .build();

        UserDetails compliance = User.builder()
                .passwordEncoder(passwordEncoder::encode)
                .username(complianceUser)
                .password(compliancePass)
                .roles(SecurityRoles.COMPLIANCE)
                .build();

        UserDetails operations = User.builder()
                .passwordEncoder(passwordEncoder::encode)
                .username(operationsUser)
                .password(operationsPass)
                .roles(SecurityRoles.OPERATIONS, SecurityRoles.TELLER)
                .build();

        UserDetails admin = User.builder()
                .passwordEncoder(passwordEncoder::encode)
                .username(adminUser)
                .password(adminPass)
                .roles(SecurityRoles.ADMIN)
                .build();

        return new InMemoryUserDetailsManager(customer, compliance, operations, admin);
    }

    private static void validateCredential(String propertyName, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("Missing required security configuration: '" + propertyName
                    + "' must not be blank. In local development, activate the 'local' profile (--spring.profiles.active=local). "
                    + "In production, configure non-empty environment variables.");
        }
    }
}
