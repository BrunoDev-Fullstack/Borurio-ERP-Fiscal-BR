package br.com.borurio.web.controller.app;

import br.com.borurio.app.context.EmpresaContextHolder;
import br.com.borurio.app.entity.Produto;
import br.com.borurio.app.service.ProdutoService;
import br.com.borurio.core.mvc.api.Result;
import br.com.borurio.core.mvc.api.ResultUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/app/produtos")
@Tag(name = "Produtos", description = "CRUD de produtos para emissão de NF-e")
public class ProdutoController {

    private final ProdutoService produtoService;

    public ProdutoController(ProdutoService produtoService) {
        this.produtoService = produtoService;
    }

    @GetMapping
    @Operation(summary = "Lista todos os produtos")
    public Result<List<Produto>> listar() {
        return ResultUtil.success(produtoService.listarTodos());
    }

    @GetMapping("/ativos")
    @Operation(summary = "Lista apenas produtos ativos")
    public Result<List<Produto>> listarAtivos() {
        return ResultUtil.success(produtoService.listarAtivos());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Busca produto por ID")
    public Result<Produto> buscarPorId(@PathVariable Long id) {
        try {
            return ResultUtil.success(produtoService.buscarPorId(id));
        } catch (IllegalArgumentException e) {
            return ResultUtil.error(e.getMessage());
        }
    }

    @GetMapping("/codigo/{codigo}")
    @Operation(summary = "Busca produto por código interno")
    public Result<Produto> buscarPorCodigo(@PathVariable String codigo) {
        try {
            return ResultUtil.success(produtoService.buscarPorCodigo(codigo));
        } catch (IllegalArgumentException e) {
            return ResultUtil.error(e.getMessage());
        }
    }

    @PostMapping
    @Operation(summary = "Cadastra novo produto")
    public Result<?> salvar(@RequestBody Produto produto) {
        try {
            produto.setEmpresaId(EmpresaContextHolder.get());
            return ResultUtil.success(produtoService.salvar(produto));
        } catch (IllegalArgumentException e) {
            return ResultUtil.error(e.getMessage());
        }
    }

    @PutMapping("/{id}")
    @Operation(summary = "Atualiza produto existente")
    public Result<?> atualizar(@PathVariable Long id, @RequestBody Produto produto) {
        try {
            return ResultUtil.success(produtoService.atualizar(id, produto));
        } catch (IllegalArgumentException e) {
            return ResultUtil.error(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Desativa produto (soft-delete)")
    public Result<?> desativar(@PathVariable Long id) {
        try {
            produtoService.desativar(id);
            return ResultUtil.success("Produto desativado com sucesso.");
        } catch (IllegalArgumentException e) {
            return ResultUtil.error(e.getMessage());
        }
    }
}
