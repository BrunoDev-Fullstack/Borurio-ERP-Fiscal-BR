package br.com.borurio.web.config;

import br.com.borurio.web.auth.JwtFilter;
import lombok.RequiredArgsConstructor;
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
 * CONFIGURAÇÃO DE SEGURANÇA — BORURIO ERP FISCAL BR
 * =============================================================================
 * Funções:
 *   - Libera rotas públicas (Swagger, Auth, Actuator, Pings, Testes Fiscais).
 *   - Bloqueia rotas sensíveis exigindo autenticação JWT.
 *   - Permite corretamente a transmissão NF-e (muito importante).
 *   - Política Stateless (JWT) para ambientes dev, hom e prd.
 *
 * Autor: Bruno Ribeiro — DevSecOps / Fullstack Java
 * Revisão: 26/11/2025
 * =============================================================================
 */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtFilter jwtFilter;

    /**
     * ========================================================================
     * SecurityFilterChain — Cadeia principal de segurança
     * ========================================================================
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http
                // ---------------------------------------------------------------------
                // CSRF DESATIVADO (aplicações REST não usam sessão nem form-login)
                // ---------------------------------------------------------------------
                .csrf(csrf -> csrf.disable())

                // ---------------------------------------------------------------------
                // POLÍTICA STATELESS (JWT)
                // ---------------------------------------------------------------------
                .sessionManagement(sess ->
                        sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )

                // ---------------------------------------------------------------------
                // AUTORIZAÇÃO POR ROTAS
                // ---------------------------------------------------------------------
                .authorizeHttpRequests(auth -> auth

                        // ---- ROTAS PÚBLICAS (SEM JWT) --------------------------------
                        .requestMatchers(
                                "/auth/**",                // Autenticação
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/actuator/**",
                                "/api/test/**",
                                "/api/fiscal/nfe/test/**",
                                "/ping",

                                // Endpoints fiscais essenciais (evita bloqueio indevido)
                                "/nfe/**",
                                "/api/fiscal/nfe/status",
                                "/api/fiscal/nfe/test/**"
                        ).permitAll()

                        // ---- TODAS AS DEMAIS ROTAS EXIGEM JWT -------------------------
                        .anyRequest().authenticated()
                )

                // ---------------------------------------------------------------------
                // FILTRO JWT ANTES DO FILTRO PADRÃO
                // ---------------------------------------------------------------------
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }


    /**
     * ========================================================================
     * AuthenticationManager — usado pelo AuthController
     * ========================================================================
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /**
     * ========================================================================
     * PasswordEncoder — BCrypt padrão corporativo
     * ========================================================================
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
