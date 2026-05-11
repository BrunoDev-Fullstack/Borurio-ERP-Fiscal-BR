package br.com.borurio.app.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * Entidade que representa um usuário do sistema ERP Borurio Brasil.
 *
 * Boas práticas aplicadas:
 * - Padrão JavaBean (atributos privados + getters/setters via Lombok)
 * - Compatível com MyBatis e JPA (estrutura simples, sem anotações ORM por enquanto)
 * - UTF-8 sem BOM
 *
 * Autor: Bruno Ribeiro – Desenvolvedor Java Fullstack
 * Graduação: Cyber Security
 * Desde: Sprint Fiscal 2.2 (Nacionalização e integração SEFAZ-SP)
 */
@Data
public class DbUser {

    private Long id;
    private Long empresaId;
    private String nome;
    private String email;
    private String senha;
    private String role;
    private Boolean ativo;
    private LocalDateTime dataCriacao;
    private LocalDateTime dataAtualizacao;
}
