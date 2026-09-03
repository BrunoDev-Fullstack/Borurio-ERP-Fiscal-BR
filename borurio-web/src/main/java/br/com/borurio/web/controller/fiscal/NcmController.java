package br.com.borurio.web.controller.fiscal;

import br.com.borurio.core.mvc.api.Result;
import br.com.borurio.core.mvc.api.ResultUtil;
import br.com.borurio.fiscal.entity.Ncm;
import br.com.borurio.fiscal.service.NcmService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/fiscal/ncm")
@Tag(name = "NCM", description = "Endpoints para manutenção e sincronização da Tabela NCM")
public class NcmController {

    private final NcmService ncmService;

    public NcmController(NcmService ncmService) {
        this.ncmService = ncmService;
    }

    // ============================================================
    // LISTAR TODOS OS NCMs
    // ============================================================
    @GetMapping("/listar")
    @Operation(summary = "Listar todos os NCMs ativos")
    public Result<List<Ncm>> listarTodos() {
        return ResultUtil.success(ncmService.listarTodos());
    }

    // ============================================================
    // BUSCAR NCM POR CÓDIGO
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
    // SINCRONIZAR TABELA NCM
    // ============================================================
    @PostMapping("/sincronizar")
    @Operation(summary = "Sincronizar tabela NCM")
    public Result<?> sincronizarTabela() {
        return ncmService.sincronizarTabela();
    }
}
