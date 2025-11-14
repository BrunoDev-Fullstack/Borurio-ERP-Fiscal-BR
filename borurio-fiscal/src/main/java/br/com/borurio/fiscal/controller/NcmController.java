package br.com.borurio.fiscal.controller;

import br.com.borurio.core.mvc.api.Result;
import br.com.borurio.core.mvc.api.ResultUtil;
import br.com.borurio.fiscal.entity.Ncm;
import br.com.borurio.fiscal.service.NcmService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/fiscal/ncm")
@Tag(name = "NCM Controller", description = "Endpoints para manutenção e sincronização da Tabela NCM")
public class NcmController {

    private final NcmService ncmService;

    public NcmController(NcmService ncmService) {
        this.ncmService = ncmService;
    }

    // ============================================================
    // ENDPOINT: Listar todos os NCMs
    // ============================================================
    @GetMapping("/listar")
    @Operation(summary = "Listar todos os NCMs ativos")
    public Result<List<Ncm>> listarTodos() {
        List<Ncm> lista = ncmService.listarTodos();
        return ResultUtil.success(lista);
    }

    // ============================================================
    // ENDPOINT: Buscar NCM por código
    // ============================================================
    @GetMapping("/{codigo}")
    @Operation(summary = "Buscar NCM por código")
    public Result<Ncm> buscarPorCodigo(@PathVariable String codigo) {

        Ncm ncm = ncmService.buscarPorCodigo(codigo);

        if (ncm == null) {
            return ResultUtil.error("NCM não encontrado para o código: " + codigo);
        }

        return ResultUtil.success(ncm);
    }

    // ============================================================
    // ENDPOINT: Sincronizar Tabela NCM
    // ============================================================
    @PostMapping("/sincronizar")
    @Operation(summary = "Sincronizar Tabela NCM")
    public Result<?> sincronizarTabela() {
        return ncmService.sincronizarTabela();
    }
}
