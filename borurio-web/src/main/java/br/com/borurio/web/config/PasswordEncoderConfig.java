package br.com.borurio.web.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * =============================================================================
 * CONFIGURAÇÃO DE ENCODER DE SENHAS
 * -----------------------------------------------------------------------------
 * Isola a criação do bean PasswordEncoder para evitar ciclos de dependência
 * entre SecurityConfig e UserDetailsServiceImpl.
 * =============================================================================
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
