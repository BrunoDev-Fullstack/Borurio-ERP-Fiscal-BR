package br.com.borurio.web.config;

import br.com.borurio.web.auth.JwtFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * =============================================================================
 * CONFIGURAÇÃO DE SEGURANÇA (Spring Security + JWT)
 * =============================================================================
 * - Libera rotas públicas (Swagger, Actuator, AuthController)
 * - Exige token JWT válido nas demais rotas
 * - Em ambiente HOM (Homologação SEFAZ-SP), libera rotas fiscais para integração real
 * - Política de sessão stateless (sem cookies)
 * =============================================================================
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * =============================================================================
 */
@Configuration
public class SecurityConfig {

    private final JwtFilter jwtFilter;

    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    public SecurityConfig(JwtFilter jwtFilter) {
        this.jwtFilter = jwtFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    // Rotas públicas globais
                    auth.requestMatchers(
                            "/auth/**",
                            "/swagger-ui/**",
                            "/v3/api-docs/**",
                            "/actuator/**",
                            "/api/test/**",
                            "/ping"
                    ).permitAll();

                    // Libera rotas fiscais apenas em ambiente HOM
                    if ("hom".equalsIgnoreCase(activeProfile)) {
                        auth.requestMatchers("/api/fiscal/nfe/**").permitAll();
                    }

                    // Demais rotas exigem autenticação JWT
                    auth.anyRequest().authenticated();
                })
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
