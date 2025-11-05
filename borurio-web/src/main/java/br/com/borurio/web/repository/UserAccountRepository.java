package br.com.borurio.web.repository;

import br.com.borurio.web.model.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

/**
 * =============================================================================
 * REPOSITÓRIO: UserAccountRepository
 * -----------------------------------------------------------------------------
 * Responsável pelas operações de acesso e persistência na tabela user_account.
 *
 * Integração:
 *   - Framework: Spring Data JPA
 *   - Entidade: UserAccount
 *   - Banco: MySQL (datasource principal)
 *
 * Métodos padrão herdados:
 *   - findAll(), findById(), save(), deleteById()
 *
 * Método customizado:
 *   - findByUsername(String username): busca um usuário pelo login.
 *
 * Projeto: ERP Fiscal Borurio BR
 * Módulo: borurio-web
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Última revisão: 04/11/2025
 * =============================================================================
 */
@Repository
public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    /**
     * Busca um usuário pelo nome de login.
     *
     * @param username nome de usuário (único)
     * @return Optional contendo o usuário, se existir
     */
    Optional<UserAccount> findByUsername(String username);
}
