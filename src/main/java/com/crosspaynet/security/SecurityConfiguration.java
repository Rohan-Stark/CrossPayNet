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

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfiguration {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/error").permitAll()
                        .anyRequest().authenticated()
                )
                .httpBasic(Customizer.withDefaults())
                .csrf(Customizer.withDefaults());

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(
            @Value("${crosspaynet.security.users.customer.username:customer}") String customerUser,
            @Value("${crosspaynet.security.users.customer.password:customer123!}") String customerPass,
            @Value("${crosspaynet.security.users.compliance.username:compliance}") String complianceUser,
            @Value("${crosspaynet.security.users.compliance.password:compliance123!}") String compliancePass,
            @Value("${crosspaynet.security.users.operations.username:operations}") String operationsUser,
            @Value("${crosspaynet.security.users.operations.password:operations123!}") String operationsPass,
            @Value("${crosspaynet.security.users.admin.username:admin}") String adminUser,
            @Value("${crosspaynet.security.users.admin.password:admin123!}") String adminPass,
            PasswordEncoder passwordEncoder
    ) {
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
}
