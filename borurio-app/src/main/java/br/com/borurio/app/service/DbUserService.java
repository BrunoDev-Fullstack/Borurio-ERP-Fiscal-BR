package br.com.borurio.app.service;

import br.com.borurio.app.entity.DbUser;
import br.com.borurio.app.mapper.DbUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Serviço responsável pelas regras de negócio da entidade DbUser.
 *
 * Responsabilidades:
 * - Intermediar acesso entre Controller e Mapper.
 * - Aplicar validações e regras de consistência.
 * - Garantir transações e atomicidade (via @Transactional).
 *
 * Compatibilidade:
 * - Spring Boot 3.3.x
 * - Java 17
 * - MyBatis Mapper Integration
 *
 * Autor: Bruno Ribeiro – Desenvolvedor Java Fullstack
 * Graduação: Cyber Security
 * Desde: Sprint Fiscal 2.2 (Refatoração e nacionalização ERP)
 */
@Service
@RequiredArgsConstructor
public class DbUserService {

    private final DbUserMapper dbUserMapper;

    /**
     * Retorna a lista completa de usuários.
     *
     * @return lista de usuários ativos e inativos.
     */
    public List<DbUser> listarTodos() {
        return dbUserMapper.findAll();
    }

    /**
     * Busca um usuário pelo e-mail cadastrado.
     *
     * @param email e-mail de login.
     * @return entidade DbUser ou null.
     */
    public DbUser buscarPorEmail(String email) {
        return dbUserMapper.findByEmail(email);
    }

    /**
     * Cria um novo usuário no sistema.
     *
     * @param user entidade a ser salva.
     */
    @Transactional
    public void criar(DbUser user) {
        dbUserMapper.insert(user);
    }

    /**
     * Atualiza dados de um usuário existente.
     *
     * @param user entidade com dados atualizados.
     */
    @Transactional
    public void atualizar(DbUser user) {
        dbUserMapper.updatePerfil(user);
    }

    /**
     * Remove um usuário pelo ID.
     *
     * @param id identificador único.
     */
    @Transactional
    public void remover(Long id) {
        dbUserMapper.deleteById(id);
    }
}
