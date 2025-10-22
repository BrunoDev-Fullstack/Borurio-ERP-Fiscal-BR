package br.com.borurio.web.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * =============================================================================
 * CONFIGURAÇÃO GLOBAL — PasswordEncoder (BCrypt)
 * =============================================================================
 * Finalidade:
 *   - Centralizar a definição do codificador de senhas da aplicação.
 *   - Evitar duplicação e ciclos de dependência entre SecurityConfig e
 *     UserDetailsServiceImpl.
 *
 * Boas práticas:
 *   - Define um único bean global reutilizável em todo o contexto Spring.
 *   - Utiliza o algoritmo BCrypt (robusto e seguro para aplicações corporativas).
 *   - Facilita auditorias e manutenções de segurança.
 * =============================================================================
 * Autor: Bruno Ribeiro — DevSecOps / Fullstack Java
 * Data: 22/10/2025
 * =============================================================================
 */
@Configuration
public class PasswordEncoderConfig {

    /**
     * Bean global responsável por criptografar e validar senhas.
     * Utiliza o algoritmo BCrypt com sal interno e força adaptativa.
     *
     * @return instância de PasswordEncoder (BCrypt)
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
