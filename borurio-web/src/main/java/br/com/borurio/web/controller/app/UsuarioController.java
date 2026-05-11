package br.com.borurio.web.controller.app;

import br.com.borurio.app.context.EmpresaContextHolder;
import br.com.borurio.app.entity.DbUser;
import br.com.borurio.core.mvc.api.Result;
import br.com.borurio.core.mvc.api.ResultUtil;
import br.com.borurio.web.dto.UsuarioResponse;
import br.com.borurio.web.service.UsuarioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/app/usuarios")
@Tag(name = "Usuários", description = "Gestão de usuários do sistema (ADMIN only)")
public class UsuarioController {

    private final UsuarioService usuarioService;

    public UsuarioController(UsuarioService usuarioService) {
        this.usuarioService = usuarioService;
    }

    @GetMapping
    @Operation(summary = "Lista usuários da empresa autenticada")
    public Result<List<UsuarioResponse>> listar() {
        Long empresaId = EmpresaContextHolder.get();
        List<DbUser> users = empresaId != null
                ? usuarioService.listarPorEmpresa(empresaId)
                : usuarioService.listarTodos();
        return ResultUtil.success(users.stream().map(UsuarioResponse::from).toList());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Busca usuário por ID")
    public Result<UsuarioResponse> buscarPorId(@PathVariable Long id) {
        DbUser user = usuarioService.buscarPorId(id);
        if (user == null) throw new NoSuchElementException("Usuário não encontrado.");
        Long empresaId = EmpresaContextHolder.get();
        if (empresaId != null && !empresaId.equals(user.getEmpresaId()))
            throw new NoSuchElementException("Usuário não encontrado.");
        return ResultUtil.success(UsuarioResponse.from(user));
    }

    @PostMapping
    @Operation(summary = "Cria novo usuário vinculado à empresa autenticada")
    public Result<UsuarioResponse> criar(@Valid @RequestBody DbUser user) {
        Long empresaId = EmpresaContextHolder.get();
        if (user.getEmpresaId() == null) {
            user.setEmpresaId(empresaId);
        }
        return ResultUtil.success(UsuarioResponse.from(usuarioService.criar(user)));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Atualiza perfil do usuário — nome, role, ativo, empresaId")
    public Result<UsuarioResponse> atualizar(@PathVariable Long id, @RequestBody DbUser dados) {
        Long empresaId = EmpresaContextHolder.get();
        if (empresaId != null) {
            DbUser existente = usuarioService.buscarPorId(id);
            if (existente == null || !empresaId.equals(existente.getEmpresaId()))
                throw new NoSuchElementException("Usuário não encontrado.");
        }
        return ResultUtil.success(UsuarioResponse.from(usuarioService.atualizar(id, dados)));
    }

    @PutMapping("/{id}/senha")
    @Operation(summary = "Altera senha do usuário")
    public Result<String> alterarSenha(@PathVariable Long id,
                                       @RequestBody Map<String, String> body) {
        usuarioService.alterarSenha(id, body.get("novaSenha"));
        return ResultUtil.success("Senha alterada com sucesso.");
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Desativa usuário (soft delete)")
    public Result<String> desativar(@PathVariable Long id) {
        Long empresaId = EmpresaContextHolder.get();
        if (empresaId != null) {
            DbUser existente = usuarioService.buscarPorId(id);
            if (existente == null || !empresaId.equals(existente.getEmpresaId()))
                throw new NoSuchElementException("Usuário não encontrado.");
        }
        usuarioService.desativar(id);
        return ResultUtil.success("Usuário desativado.");
    }
}
