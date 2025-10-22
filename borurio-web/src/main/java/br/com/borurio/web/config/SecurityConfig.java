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
 *   - Define as regras de autenticação/autorização globais da aplicação.
 *   - Habilita autenticação Stateless via token JWT.
 *   - Libera rotas públicas como Swagger, Actuator e endpoints de teste.
 *
 * Boas práticas:
 *   - PasswordEncoder é definido globalmente em PasswordEncoderConfig.
 *   - Autenticação é gerenciada via AuthenticationManager (injeção segura).
 * =============================================================================
 * Autor: Bruno Ribeiro — DevSecOps / Fullstack Java
 * Data: 22/10/2025
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
     * Define as regras de segurança da aplicação.
     * - Desabilita CSRF (aplicação REST).
     * - Configura autenticação Stateless (sem sessão).
     * - Permite acesso às rotas públicas (Swagger, Actuator, Auth, etc.).
     * - Aplica o filtro JWT às rotas protegidas.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/auth/**",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/actuator/**",
                                "/api/test/**",
                                "/api/nfe/status",
                                "/nfe/status",
                                "/ping"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterAfter(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Exponibiliza o AuthenticationManager do contexto Spring Security.
     * Necessário para autenticação customizada (ex.: AuthController).
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
