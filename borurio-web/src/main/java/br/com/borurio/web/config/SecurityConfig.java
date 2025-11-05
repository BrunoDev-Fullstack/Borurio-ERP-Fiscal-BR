package br.com.borurio.web.config;

import br.com.borurio.web.auth.JwtFilter;
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
 * -----------------------------------------------------------------------------
 * Ambiente unificado DEV + HOM.
 *
 * Funções principais:
 * - Libera rotas públicas (Swagger, Actuator, AuthController, PingController, mocks de teste).
 * - Exige autenticação JWT para endpoints fiscais e de negócio (/nfe/**, /api/fiscal/nfe/**).
 * - Define política stateless (sem sessão, sem cookies).
 * - Garante que o filtro JWT seja processado antes da autenticação padrão.
 *
 * Compatível com os ambientes: dev, hom e prd.
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
     * MÉTODO: securityFilterChain
     * -----------------------------------------------------------------------------
     * Define toda a cadeia de filtros e permissões.
     * =============================================================================
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Desabilita CSRF (não há uso de sessões)
                .csrf(csrf -> csrf.disable())

                // Define a política stateless (JWT sem sessão)
                .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Define as permissões por endpoint
                .authorizeHttpRequests(auth -> auth
                        // Endpoints públicos liberados (sem JWT)
                        .requestMatchers(
                                "/auth/**",                    // Login e refresh
                                "/swagger-ui/**",              // Documentação Swagger
                                "/v3/api-docs/**",             // Especificação OpenAPI
                                "/actuator/**",                // Healthcheck e métricas
                                "/api/test/**",                // Pings e validações DEV
                                "/api/fiscal/nfe/test/**",     // Testes fiscais simulados (HOM/DEV)
                                "/ping"                        // Verificação rápida
                        ).permitAll()

                        // Demais endpoints exigem autenticação JWT
                        .anyRequest().authenticated()
                )

                // Adiciona o filtro JWT antes do filtro padrão de autenticação
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * =============================================================================
     * BEAN: AuthenticationManager
     * -----------------------------------------------------------------------------
     * Gerenciador padrão de autenticação do Spring.
     * =============================================================================
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /**
     * =============================================================================
     * BEAN: PasswordEncoder
     * -----------------------------------------------------------------------------
     * Codificador de senhas padrão (BCrypt).
     * =============================================================================
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
