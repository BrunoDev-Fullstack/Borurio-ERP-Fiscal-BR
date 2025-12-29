package br.com.borurio.web.config;

import br.com.borurio.web.auth.JwtFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
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
 * CONFIGURAÇÃO DE SEGURANÇA — BORURIO ERP FISCAL BR
 * =============================================================================
 * Aplicável exclusivamente aos perfis DEV e HOM.
 *
 * Objetivos:
 *  • Liberar Swagger/OpenAPI para uso interno
 *  • Liberar endpoints públicos básicos
 *  • Exigir JWT para endpoints fiscais reais
 *  • Manter política Stateless (JWT)
 *
 * OBS:
 *  • Produção (PRD) deve possuir configuração própria
 * =============================================================================
 */
@Configuration
@Profile({"dev", "hom"})
public class SecurityConfig {

    private final JwtFilter jwtFilter;

    public SecurityConfig(JwtFilter jwtFilter) {
        this.jwtFilter = jwtFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http
                // -----------------------------------------------------------------
                // API Stateless (JWT)
                // -----------------------------------------------------------------
                .csrf(csrf -> csrf.disable())
                .cors(cors -> { }) // preparado para front externo / swagger

                .sessionManagement(sess ->
                        sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )

                // -----------------------------------------------------------------
                // AUTORIZAÇÃO DE ENDPOINTS
                // -----------------------------------------------------------------
                .authorizeHttpRequests(auth -> auth

                        // ENDPOINTS PÚBLICOS (SEM JWT)
                        .requestMatchers(
                                "/auth/**",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/actuator/**",
                                "/api/test/**",
                                "/api/fiscal/nfe/test/**",
                                "/ping"
                        ).permitAll()

                        // ENDPOINTS FISCAIS REAIS (COM JWT)
                        .requestMatchers(
                                "/api/fiscal/nfe/status",
                                "/api/fiscal/nfe/enviar"
                        ).authenticated()

                        // DEMAIS ENDPOINTS
                        .anyRequest().authenticated()
                )

                // -----------------------------------------------------------------
                // FILTRO JWT
                // -----------------------------------------------------------------
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * AuthenticationManager padrão do Spring Security.
     */
    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config
    ) throws Exception {
        return config.getAuthenticationManager();
    }

    /**
     * Encoder padrão de senha (BCrypt).
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
