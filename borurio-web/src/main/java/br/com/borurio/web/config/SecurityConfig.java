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

@Configuration
public class SecurityConfig {

    private final JwtFilter jwtFilter;

    public SecurityConfig(JwtFilter jwtFilter) {
        this.jwtFilter = jwtFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http
                // API stateless
                .csrf(csrf -> csrf.disable())

                // CORS liberado
                .cors(cors -> {})

                // Sem sessão
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )

                // Regras de autorização
                .authorizeHttpRequests(auth -> auth

                        // ==========================
                        // PÚBLICOS
                        // ==========================
                        .requestMatchers(
                                "/auth/**",
                                "/ping",
                                "/api/test/**",
                                "/api/fiscal/nfe/test/**",

                                // SWAGGER
                                "/swagger-ui/**",
                                "/v3/api-docs/**",

                                // ACTUATOR
                                "/actuator/health",
                                "/actuator/info",

                                // ==========================
                                // NF-e (HOM LIBERADO)
                                // ==========================
                                "/api/fiscal/nfe/enviar",
                                "/api/fiscal/nfe/status"
                        ).permitAll()

                        // ==========================
                        // RESTO PROTEGIDO
                        // ==========================
                        .anyRequest().authenticated()
                )

                // JWT filter
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration configuration
    ) throws Exception {
        return configuration.getAuthenticationManager();
    }
}