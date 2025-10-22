package br.com.borurio.web.model;

import jakarta.persistence.*;

/**
 * =============================================================================
 * ENTIDADE: UserAccount
 * -----------------------------------------------------------------------------
 * Representa o usuário do sistema para autenticação e autorização.
 *
 * Estrutura da tabela (MySQL):
 *   CREATE TABLE user_account (
 *       id BIGINT AUTO_INCREMENT PRIMARY KEY,
 *       username VARCHAR(100) UNIQUE NOT NULL,
 *       password VARCHAR(255) NOT NULL,
 *       role VARCHAR(50) DEFAULT 'ADMIN',
 *       ativo BOOLEAN DEFAULT TRUE
 *   );
 *
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Projeto: ERP Fiscal Borurio BR
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
    private String role = "ADMIN";

    @Column(nullable = false)
    private boolean ativo = true;

    // ============================================================
    // GETTERS E SETTERS
    // ============================================================

    public Long getId() {
        return id;
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

    public boolean isAtivo() {
        return ativo;
    }

    public void setAtivo(boolean ativo) {
        this.ativo = ativo;
    }
}
