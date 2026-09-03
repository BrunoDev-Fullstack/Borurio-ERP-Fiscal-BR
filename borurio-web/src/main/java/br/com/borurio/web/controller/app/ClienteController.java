package br.com.borurio.web.controller.app;

import br.com.borurio.app.context.EmpresaContextHolder;
import br.com.borurio.app.entity.Cliente;
import br.com.borurio.app.service.ClienteService;
import br.com.borurio.core.mvc.api.Result;
import br.com.borurio.core.mvc.api.ResultUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/app/clientes")
@Tag(name = "Clientes", description = "Operações do módulo de Clientes")
public class ClienteController {

    private final ClienteService clienteService;

    public ClienteController(ClienteService clienteService) {
        this.clienteService = clienteService;
    }

    @GetMapping
    @Operation(summary = "Lista clientes da empresa autenticada")
    public Result<List<Cliente>> listar() {
        Long empresaId = EmpresaContextHolder.get();
        return ResultUtil.success(empresaId != null
                ? clienteService.listarPorEmpresa(empresaId)
                : clienteService.listarTodos());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Busca cliente por ID — valida pertencimento à empresa")
    public Result<Cliente> buscar(@PathVariable Long id) {
        Cliente cliente = clienteService.buscarPorId(id);
        if (cliente == null) throw new NoSuchElementException("Cliente não encontrado.");
        Long empresaId = EmpresaContextHolder.get();
        if (empresaId != null && !empresaId.equals(cliente.getEmpresaId()))
            throw new NoSuchElementException("Cliente não encontrado.");
        return ResultUtil.success(cliente);
    }

    @PostMapping
    @Operation(summary = "Cria novo cliente vinculado à empresa autenticada")
    public Result<Cliente> salvar(@Valid @RequestBody Cliente cliente) {
        cliente.setEmpresaId(EmpresaContextHolder.get());
        return ResultUtil.success(clienteService.salvar(cliente));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Atualiza cliente — valida pertencimento à empresa")
    public Result<Cliente> atualizar(@PathVariable Long id, @Valid @RequestBody Cliente cliente) {
        Long empresaId = EmpresaContextHolder.get();
        if (empresaId != null) {
            Cliente existente = clienteService.buscarPorId(id);
            if (existente == null || !empresaId.equals(existente.getEmpresaId()))
                throw new NoSuchElementException("Cliente não encontrado.");
        }
        return ResultUtil.success(clienteService.atualizar(id, cliente));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Desativa cliente — valida pertencimento à empresa")
    public Result<String> desativar(@PathVariable Long id) {
        Long empresaId = EmpresaContextHolder.get();
        if (empresaId != null) {
            Cliente existente = clienteService.buscarPorId(id);
            if (existente == null || !empresaId.equals(existente.getEmpresaId()))
                throw new NoSuchElementException("Cliente não encontrado.");
        }
        clienteService.desativar(id);
        return ResultUtil.success("Cliente desativado com sucesso.");
    }
}
