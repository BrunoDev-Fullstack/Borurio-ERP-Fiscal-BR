package br.com.borurio.web.model;

import jakarta.persistence.*;

/**
 * =============================================================================
 * ENTIDADE: UserAccount
 * -----------------------------------------------------------------------------
 * Representa o usuário do sistema para autenticação e autorização.
 *
 * Estrutura da tabela (MySQL):
 *   CREATE TABLE IF NOT EXISTS user_account (
 *       id BIGINT AUTO_INCREMENT PRIMARY KEY,
 *       username VARCHAR(100) NOT NULL UNIQUE,
 *       password VARCHAR(255) NOT NULL,
 *       role VARCHAR(50) DEFAULT 'ROLE_ADMIN',
 *       enabled BOOLEAN DEFAULT TRUE
 *   );
 *
 * Requisitos:
 *   - Integração com Spring Security e JWT.
 *   - Campo "enabled" indica se o usuário está ativo no sistema.
 *   - Campo "role" segue o padrão "ROLE_ADMIN", "ROLE_USER", etc.
 *
 * Projeto: ERP Fiscal Borurio BR
 * Módulo: borurio-web
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Última revisão: 04/11/2025
 * =============================================================================
 */
@Entity
@Table(name = "user_account")
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    @Column(nullable = false, length = 255)
    private String password;

    @Column(length = 50)
    private String role = "ROLE_ADMIN";

    @Column(nullable = false)
    private boolean enabled = true;

    // ============================================================
    // CONSTRUTORES
    // ============================================================

    public UserAccount() {
    }

    public UserAccount(String username, String password, String role, boolean enabled) {
        this.username = username;
        this.password = password;
        this.role = role;
        this.enabled = enabled;
    }

    // ============================================================
    // GETTERS E SETTERS
    // ============================================================

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    // ============================================================
    // MÉTODOS AUXILIARES
    // ============================================================

    @Override
    public String toString() {
        return "UserAccount{" +
                "id=" + id +
                ", username='" + username + '\'' +
                ", role='" + role + '\'' +
                ", enabled=" + enabled +
                '}';
    }
}
