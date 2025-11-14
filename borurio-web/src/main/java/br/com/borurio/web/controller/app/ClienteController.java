package br.com.borurio.web.controller.app;

import br.com.borurio.app.entity.Cliente;
import br.com.borurio.app.service.ClienteService;
import br.com.borurio.core.mvc.api.Result;
import br.com.borurio.core.mvc.api.ResultUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * =============================================================================
 * CONTROLLER REST — CLIENTE (MÓDULO APP)
 * =============================================================================
 */
@RestController
@RequestMapping("/api/app/clientes")
@Tag(name = "Clientes", description = "Operações do módulo de Clientes")
public class ClienteController {

    private final ClienteService clienteService;

    public ClienteController(ClienteService clienteService) {
        this.clienteService = clienteService;
    }

    // =========================================================================
    // LISTAR CLIENTES
    // =========================================================================
    @GetMapping
    @Operation(summary = "Listar clientes")
    public Result<List<Cliente>> listar() {
        List<Cliente> clientes = clienteService.listarTodos();
        return ResultUtil.success(clientes);
    }

    // =========================================================================
    // BUSCAR CLIENTE POR ID
    // =========================================================================
    @GetMapping("/{id}")
    @Operation(summary = "Buscar cliente por ID")
    public Result<Cliente> buscar(@PathVariable Long id) {
        Cliente cliente = clienteService.buscarPorId(id);

        if (cliente == null) {
            return ResultUtil.error("Cliente não encontrado.");
        }

        return ResultUtil.success(cliente);
    }

    // =========================================================================
    // CRIAR CLIENTE
    // =========================================================================
    @PostMapping
    @Operation(summary = "Criar novo cliente")
    public Result<?> salvar(@RequestBody Cliente cliente) {
        Cliente criado = clienteService.salvar(cliente);
        return ResultUtil.success(criado);
    }

    // =========================================================================
    // ATUALIZAR CLIENTE
    // =========================================================================
    @PutMapping("/{id}")
    @Operation(summary = "Atualizar cliente")
    public Result<?> atualizar(@PathVariable Long id, @RequestBody Cliente cliente) {
        Cliente atualizado = clienteService.atualizar(id, cliente);

        if (atualizado == null) {
            return ResultUtil.error("Falha ao atualizar cliente.");
        }

        return ResultUtil.success(atualizado);
    }

    // =========================================================================
    // DESATIVAR CLIENTE
    // =========================================================================
    @DeleteMapping("/{id}")
    @Operation(summary = "Desativar cliente")
    public Result<?> desativar(@PathVariable Long id) {
        boolean ok = clienteService.desativar(id);

        if (!ok) {
            return ResultUtil.error("Não foi possível desativar o cliente.");
        }

        return ResultUtil.success("Cliente desativado com sucesso.");
    }
}
