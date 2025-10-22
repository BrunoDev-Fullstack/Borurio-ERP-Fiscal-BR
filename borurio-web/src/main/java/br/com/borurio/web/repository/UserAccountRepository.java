package br.com.borurio.web.repository;

import br.com.borurio.web.model.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

/**
 * =============================================================================
 * REPOSITÓRIO: UserAccountRepository
 * -----------------------------------------------------------------------------
 * Responsável pelas operações de persistência na tabela user_account.
 *
 * Métodos padrão:
 *   - findAll(), findById(), save(), deleteById()
 *   - findByUsername(String username)
 *
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Projeto: ERP Fiscal Borurio BR
 * =============================================================================
 */
@Repository
public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    /**
     * Busca um usuário pelo nome de login.
     *
     * @param username nome do usuário
     * @return usuário encontrado (ou vazio se não existir)
     */
    Optional<UserAccount> findByUsername(String username);
}
