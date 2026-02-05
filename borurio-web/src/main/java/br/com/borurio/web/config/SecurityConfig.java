package br.com.borurio.web.config;

import br.com.borurio.web.auth.JwtFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * =============================================================================
 * CONFIGURAÇÃO DE SEGURANÇA — BORURIO ERP FISCAL BR
 * -----------------------------------------------------------------------------
 * Ambientes suportados: DEV | HOM | PRD
 *
 * Diretrizes:
 * - Autenticação stateless via JWT
 * - Liberação controlada de endpoints públicos
 * - Proteção total de endpoints fiscais e de negócio
 * - Compatível com Spring Boot 3.x / Spring Security 6.x
 * =============================================================================
 */
@Configuration
public class SecurityConfig {

    private final JwtFilter jwtFilter;

    public SecurityConfig(JwtFilter jwtFilter) {
        this.jwtFilter = jwtFilter;
    }

    /**
     * =============================================================================
     * Security Filter Chain
     * -----------------------------------------------------------------------------
     * Define políticas de segurança, autenticação e autorização.
     * =============================================================================
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http
                // CSRF desabilitado (API REST stateless com JWT)
                .csrf(csrf -> csrf.disable())

                // Política de sessão stateless
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )

                // Regras de autorização
                .authorizeHttpRequests(auth -> auth

                        // ==========================
                        // ENDPOINTS PÚBLICOS
                        // ==========================
                        .requestMatchers(
                                "/auth/**",                 // Autenticação / refresh token
                                "/ping",                    // Health simples
                                "/api/test/**",             // Testes DEV
                                "/api/fiscal/nfe/test/**",  // Simulações fiscais

                                // Swagger / OpenAPI
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**",

                                // Actuator (liberação mínima e consciente)
                                "/actuator",
                                "/actuator/",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info"
                        ).permitAll()

                        // ==========================
                        // DEMAIS ROTAS PROTEGIDAS
                        // ==========================
                        .anyRequest().authenticated()
                )

                // Filtro JWT antes da autenticação padrão
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * =============================================================================
     * AuthenticationManager
     * -----------------------------------------------------------------------------
     * Gerenciador padrão de autenticação do Spring Security.
     * =============================================================================
     */
    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration configuration
    ) throws Exception {
        return configuration.getAuthenticationManager();
    }
}
