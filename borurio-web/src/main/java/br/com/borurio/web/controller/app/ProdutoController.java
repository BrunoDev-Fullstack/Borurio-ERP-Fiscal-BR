package br.com.borurio.web.controller.app;

import br.com.borurio.app.context.EmpresaContextHolder;
import br.com.borurio.app.entity.EstoqueMovimento;
import br.com.borurio.app.entity.EstoqueSaldo;
import br.com.borurio.app.entity.Produto;
import br.com.borurio.app.service.EstoqueService;
import br.com.borurio.app.service.ProdutoService;
import br.com.borurio.core.mvc.api.PageResponse;
import br.com.borurio.core.mvc.api.Result;
import br.com.borurio.core.mvc.api.ResultUtil;
import br.com.borurio.web.dto.EstoqueEntradaRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/app/produtos")
@Tag(name = "Produtos", description = "CRUD de produtos para emissão de NF-e")
public class ProdutoController {

    private final ProdutoService produtoService;
    private final EstoqueService estoqueService;

    public ProdutoController(ProdutoService produtoService, EstoqueService estoqueService) {
        this.produtoService = produtoService;
        this.estoqueService = estoqueService;
    }

    @GetMapping
    @Operation(summary = "Lista produtos da empresa autenticada (paginado)")
    public Result<PageResponse<Produto>> listar(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        size = Math.min(size, 100);
        Long empresaId = EmpresaContextHolder.get();
        return ResultUtil.success(produtoService.listarPaginado(empresaId, page, size));
    }

    @GetMapping("/ativos")
    @Operation(summary = "Lista produtos ativos da empresa autenticada")
    public Result<List<Produto>> listarAtivos() {
        Long empresaId = EmpresaContextHolder.get();
        return ResultUtil.success(empresaId != null
                ? produtoService.listarAtivosPorEmpresa(empresaId)
                : produtoService.listarAtivos());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Busca produto por ID — valida pertencimento à empresa")
    public Result<Produto> buscarPorId(@PathVariable Long id) {
        Long empresaId = EmpresaContextHolder.get();
        return ResultUtil.success(empresaId != null
                ? produtoService.buscarPorIdEEmpresa(id, empresaId)
                : produtoService.buscarPorId(id));
    }

    @GetMapping("/codigo/{codigo}")
    @Operation(summary = "Busca produto por código interno")
    public Result<Produto> buscarPorCodigo(@PathVariable String codigo) {
        return ResultUtil.success(produtoService.buscarPorCodigo(codigo));
    }

    @PostMapping
    @Operation(summary = "Cadastra novo produto")
    public Result<Produto> salvar(@Valid @RequestBody Produto produto) {
        produto.setEmpresaId(EmpresaContextHolder.get());
        return ResultUtil.success(produtoService.salvar(produto));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Atualiza produto existente")
    public Result<Produto> atualizar(@PathVariable Long id, @Valid @RequestBody Produto produto) {
        return ResultUtil.success(produtoService.atualizar(id, produto));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Desativa produto (soft-delete)")
    public Result<String> desativar(@PathVariable Long id) {
        produtoService.desativar(id);
        return ResultUtil.success("Produto desativado com sucesso.");
    }

    @GetMapping("/{id}/estoque")
    @Operation(summary = "Consulta saldo de estoque do produto (total, reservado, disponível)")
    public Result<EstoqueSaldo> consultarEstoque(@PathVariable Long id) {
        Long empresaId = EmpresaContextHolder.get();
        return ResultUtil.success(estoqueService.consultarSaldo(id, empresaId));
    }

    @PostMapping("/{id}/estoque/entrada")
    @Operation(summary = "Registra entrada manual de estoque [ADMIN]")
    public Result<EstoqueMovimento> entrada(@PathVariable Long id,
                                             @Valid @RequestBody EstoqueEntradaRequest req) {
        Long empresaId = EmpresaContextHolder.get();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String criadoPor = auth != null ? auth.getName() : "sistema";
        return ResultUtil.success(
                estoqueService.entrada(id, empresaId, req.getQuantidade(), criadoPor, req.getObservacao()));
    }
}
