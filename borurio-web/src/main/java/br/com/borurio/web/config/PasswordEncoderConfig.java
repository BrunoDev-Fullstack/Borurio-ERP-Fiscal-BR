package br.com.borurio.web.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * =============================================================================
 * CONFIGURAÇÃO GLOBAL DE SENHAS — PasswordEncoder (BCrypt)
 * =============================================================================
 * Responsável por fornecer o codificador de senhas padrão da aplicação.
 *
 * Funções principais:
 *   - Criptografar senhas antes de persistir no banco;
 *   - Validar senhas no fluxo de login via BCryptPasswordEncoder;
 *   - Evitar acoplamento indevido entre SecurityConfig e UserDetailsServiceImpl.
 *
 * Características de Segurança:
 *   - BCrypt → algoritmo recomendado pela OWASP (ASVS) e LGPD;
 *   - Salt interno automático;
 *   - Fator de custo adaptativo (work factor);
 *   - Proteção contra rainbow tables e ataques de força bruta.
 *
 * Observações:
 *   - Work factor padrão (strength=10) já atende ambientes de produção;
 *   - Pode ser aumentado futuramente (ex.: new BCryptPasswordEncoder(12)).
 *
 * Projeto: Borurio ERP Fiscal BR
 * Módulo: borurio-web
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Última revisão: 02/12/2025
 * =============================================================================
 */
@Configuration
public class PasswordEncoderConfig {

    /**
     * Bean global de codificação de senhas com BCrypt.
     *
     * Exemplo de uso:
     *   String hash = passwordEncoder.encode("senha123");
     *   boolean ok = passwordEncoder.matches("senha123", hash);
     *
     * @return instância de PasswordEncoder configurada com BCrypt.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(); // strength=10 (default OWASP)
    }
}
