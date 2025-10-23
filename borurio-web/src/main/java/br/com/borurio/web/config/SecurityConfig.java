package br.com.borurio.web.config;

import br.com.borurio.web.auth.JwtFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * =============================================================================
 * CONFIGURAÇÃO DE SEGURANÇA — SPRING SECURITY + JWT
 * =============================================================================
 * Finalidade:
 *   - Define políticas globais de autenticação e autorização da aplicação.
 *   - Implementa autenticação Stateless baseada em token JWT.
 *   - Libera rotas públicas específicas para observabilidade e integração SEFAZ.
 *
 * Diretrizes técnicas:
 *   - PasswordEncoder definido em {@link PasswordEncoderConfig}.
 *   - AuthenticationManager exposto para uso em controladores de autenticação.
 *   - Rotas públicas declaradas explicitamente para evitar falsos positivos.
 *   - Filtro JWT inserido antes do UsernamePasswordAuthenticationFilter.
 *
 * Boas práticas DevSecOps:
 *   - Princípio de privilégio mínimo.
 *   - Separação entre rotas públicas e autenticadas.
 *   - Stateless Session Policy (segurança e escalabilidade).
 * =============================================================================
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Projeto: Borurio ERP Fiscal BR
 * Data: 23/10/2025
 * =============================================================================
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtFilter jwtFilter;

    public SecurityConfig(JwtFilter jwtFilter) {
        this.jwtFilter = jwtFilter;
    }

    /**
     * =============================================================================
     * MÉTODO: securityFilterChain
     * -----------------------------------------------------------------------------
     * - Desabilita CSRF (não aplicável em APIs REST).
     * - Define o gerenciamento de sessão como Stateless.
     * - Libera rotas públicas (Swagger, Actuator, Ping e endpoints SEFAZ).
     * - Aplica autenticação JWT a todas as demais requisições.
     * =============================================================================
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Desabilita CSRF (não aplicável para APIs REST)
                .csrf(csrf -> csrf.disable())

                // Define gerenciamento de sessão Stateless (sem estado no servidor)
                .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Configura as políticas de autorização
                .authorizeHttpRequests(auth -> auth
                        // ================================
                        // ROTAS PÚBLICAS LIBERADAS
                        // ================================
                        .requestMatchers(
                                // Autenticação e Swagger
                                "/auth/**",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",

                                // Observabilidade
                                "/actuator/**",
                                "/api/actuator/**",

                                // Testes e monitoramento
                                "/api/test/**",
                                "/ping",
                                "/health",

                                // Endpoints fiscais e mock SEFAZ-SP
                                "/api/nfe/**",
                                "/nfe/**"
                        ).permitAll()

                        // ================================
                        // ROTAS RESTRITAS (JWT)
                        // ================================
                        .anyRequest().authenticated()
                )

                // Adiciona o filtro JWT antes do filtro padrão de autenticação
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * =============================================================================
     * MÉTODO: authenticationManager
     * -----------------------------------------------------------------------------
     * Expõe o AuthenticationManager do contexto Spring Security.
     * Necessário para autenticação customizada via AuthController.
     * =============================================================================
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
