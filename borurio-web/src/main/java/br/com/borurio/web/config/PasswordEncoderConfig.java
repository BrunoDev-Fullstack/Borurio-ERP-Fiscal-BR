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
 *   - Criptografar senhas de usuários ao salvar no banco.
 *   - Validar senhas informadas no login (BCrypt matches).
 *   - Evitar duplicações e dependências circulares entre SecurityConfig
 *     e UserDetailsServiceImpl.
 *
 * Padrões e recomendações:
 *   - Utiliza o algoritmo BCrypt com sal interno e fator de custo adaptativo.
 *   - Implementação compatível com OWASP ASVS e LGPD (proteção de credenciais).
 *   - Força padrão de 10 rounds (ajustável via construtor, se necessário).
 *
 * =============================================================================
 * Projeto: Borurio ERP Fiscal BR
 * Módulo: borurio-web
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Data: 23/10/2025
 * =============================================================================
 */
@Configuration
public class PasswordEncoderConfig {

    /**
     * Bean global de codificação de senhas.
     *
     * O BCrypt é uma função de hash adaptativa — conforme o hardware evolui,
     * o custo (fator de trabalho) pode ser aumentado para reforçar a segurança.
     *
     * Exemplo de uso:
     * <pre>
     *   String hash = passwordEncoder.encode("senha123");
     *   boolean ok = passwordEncoder.matches("senha123", hash);
     * </pre>
     *
     * @return instância singleton de {@link PasswordEncoder} configurada com BCrypt.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
