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
 * Padrão DevSecOps aplicado para API REST com autenticação via JWT.
 *
 * Rotas públicas:
 *   - Swagger / OpenAPI
 *   - Healthchecks / Pings
 *   - Testes fiscais (assinatura, validação, status SEFAZ mock)
 *
 * Rotas privadas:
 *   - Qualquer outro endpoint sensível; exige JWT válido.
 *
 * Autor: Bruno Ribeiro — DevSecOps / Fullstack Java
 * Revisão: 02/12/2025
 * =============================================================================
 */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtFilter jwtFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http
                // ---------------------------------------------------------------------
                // DESABILITAÇÃO DE CSRF EM API REST (sem sessão e sem formulário)
                // ---------------------------------------------------------------------
                .csrf(csrf -> csrf.disable())

                // ---------------------------------------------------------------------
                // API STATELESS (JWT)
                // ---------------------------------------------------------------------
                .sessionManagement(sess ->
                        sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )

                // ---------------------------------------------------------------------
                // CONTROLE DE ROTAS — LIBERADAS X PROTEGIDAS
                // ---------------------------------------------------------------------
                .authorizeHttpRequests(auth -> auth

                        // =============================================================
                        // ROTAS PÚBLICAS (SEM JWT) — DEVSECOPS
                        // =============================================================
                        .requestMatchers(
                                // Swagger / documentação
                                "/swagger-ui/**",
                                "/v3/api-docs/**",

                                // Healthcheck
                                "/actuator/**",
                                "/api/test/**",
                                "/ping",

                                // Autenticação
                                "/auth/**",

                                // Status NF-e
                                "/nfe/status",
                                "/api/fiscal/nfe/status",

                                // Testes de NF-e
                                "/api/fiscal/nfe/validar-local",
                                "/api/fiscal/nfe/assinatura/teste",
                                "/api/fiscal/nfe/test/**",

                                // Envio NF-e modo simulado
                                "/nfe/envio/teste"
                        ).permitAll()

                        // =============================================================
                        // QUALQUER OUTRA ROTA EXIGE JWT
                        // =============================================================
                        .anyRequest().authenticated()
                )

                // ---------------------------------------------------------------------
                // FILTRO JWT ANTES DO FILTRO PADRÃO DO SPRING
                // ---------------------------------------------------------------------
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    // =========================================================================
    // AuthenticationManager — usado pelo AuthController
    // =========================================================================
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    // =========================================================================
    // PasswordEncoder — BCrypt corporativo
    // =========================================================================
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
