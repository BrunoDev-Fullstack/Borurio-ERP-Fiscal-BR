package br.com.borurio.web.config;

import br.com.borurio.web.auth.JwtFilter;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Controla autenticação via JWT, define endpoints públicos e aplica política
 * stateless (sem sessão) para as APIs REST do ERP Fiscal BR.
 *
 * Perfis suportados:
 *   • dev — libera endpoints fiscais e de observabilidade (para homologação SEFAZ)
 *   • hom / prd — exige autenticação JWT para endpoints sensíveis
 *
 * Endpoints públicos (todos os perfis):
 *   /auth/login
 *   /swagger-ui/**
 *   /v3/api-docs/**
 *   /actuator/**
 *   /api/test/**
 *
 * Endpoints adicionais liberados apenas no perfil dev:
 *   /api/fiscal/**
 *
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Data: Outubro/2025
 * =============================================================================
 */
@Configuration
public class SecurityConfig {

    @Autowired
    private JwtFilter jwtFilter;

    // __________________________________________________________________________
    // PERFIS: HOMOLOGAÇÃO / PRODUÇÃO
    // --------------------------------------------------------------------------
    @Bean
    @Profile({"hom", "prd"})
    public SecurityFilterChain filterChainDefault(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/auth/login",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/actuator/**",
                                "/api/test/**"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    // __________________________________________________________________________
    // PERFIL: DESENVOLVIMENTO
    // --------------------------------------------------------------------------
    @Bean
    @Profile("dev")
    public SecurityFilterChain filterChainDev(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/auth/login",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/actuator/**",
                                "/api/test/**",
                                "/api/fiscal/**"   // Liberação completa para endpoints fiscais (ping, status, envio)
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    // __________________________________________________________________________
    // BEANS AUXILIARES
    // --------------------------------------------------------------------------

    /**
     * Algoritmo padrão de hashing de senha.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Gerenciador de autenticação global usado pelo AuthService.
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
