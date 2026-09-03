package br.com.borurio.web.service;

import br.com.borurio.app.entity.DbUser;
import br.com.borurio.app.mapper.DbUserMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;

@Service
public class UsuarioService {

    private final DbUserMapper dbUserMapper;
    private final PasswordEncoder passwordEncoder;

    public UsuarioService(DbUserMapper dbUserMapper, PasswordEncoder passwordEncoder) {
        this.dbUserMapper = dbUserMapper;
        this.passwordEncoder = passwordEncoder;
    }

    public List<DbUser> listarPorEmpresa(Long empresaId) {
        return dbUserMapper.findByEmpresaId(empresaId);
    }

    public List<DbUser> listarTodos() {
        return dbUserMapper.findAll();
    }

    public DbUser buscarPorId(Long id) {
        return dbUserMapper.findById(id);
    }

    public DbUser criar(DbUser user) {
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            throw new IllegalArgumentException("E-mail é obrigatório.");
        }
        if (user.getSenha() == null || user.getSenha().isBlank()) {
            throw new IllegalArgumentException("Senha é obrigatória.");
        }
        if (dbUserMapper.findByEmail(user.getEmail()) != null) {
            throw new IllegalArgumentException("E-mail já cadastrado: " + user.getEmail());
        }
        user.setSenha(passwordEncoder.encode(user.getSenha()));
        if (user.getRole() == null || user.getRole().isBlank()) {
            user.setRole("OPERADOR");
        }
        if (user.getAtivo() == null) {
            user.setAtivo(true);
        }
        dbUserMapper.insert(user);
        return user;
    }

    public DbUser atualizar(Long id, DbUser dados) {
        DbUser existente = dbUserMapper.findById(id);
        if (existente == null) {
            throw new NoSuchElementException("Usuário não encontrado: " + id);
        }
        existente.setNome(dados.getNome() != null ? dados.getNome() : existente.getNome());
        existente.setRole(dados.getRole() != null ? dados.getRole() : existente.getRole());
        existente.setAtivo(dados.getAtivo() != null ? dados.getAtivo() : existente.getAtivo());
        existente.setEmpresaId(dados.getEmpresaId() != null ? dados.getEmpresaId() : existente.getEmpresaId());
        dbUserMapper.updatePerfil(existente);
        return existente;
    }

    public void alterarSenha(Long id, String novaSenha) {
        if (novaSenha == null || novaSenha.length() < 6) {
            throw new IllegalArgumentException("Senha deve ter no mínimo 6 caracteres.");
        }
        if (dbUserMapper.findById(id) == null) {
            throw new NoSuchElementException("Usuário não encontrado: " + id);
        }
        dbUserMapper.updateSenha(id, passwordEncoder.encode(novaSenha));
    }

    public void desativar(Long id) {
        if (dbUserMapper.findById(id) == null) {
            throw new NoSuchElementException("Usuário não encontrado: " + id);
        }
        dbUserMapper.deactivate(id);
    }
}
