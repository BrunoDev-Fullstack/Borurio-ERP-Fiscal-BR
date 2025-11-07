package br.com.borurio.fiscal.controller;

import br.com.borurio.core.mvc.api.Result;
import br.com.borurio.core.mvc.api.ResultUtil;
import br.com.borurio.fiscal.entity.Ncm;
import br.com.borurio.fiscal.service.NcmService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * =============================================================================
 * CONTROLADOR REST — TABELA NCM
 * =============================================================================
 * Responsável por expor endpoints REST para manipulação e sincronização da
 * Tabela NCM (Nomenclatura Comum do Mercosul).
 *
 * Funcionalidades:
 *  - Listar todos os NCMs ativos
 *  - Buscar NCM por código
 *  - Sincronizar tabela a partir do CSV oficial (docs/data/ncm_oficial_20251107.csv)
 *
 * Autor: Bruno Ribeiro — DevSecOps / Fullstack Java
 * Revisão: 07/11/2025
 * =============================================================================
 */
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
    @Operation(summary = "Listar todos os NCMs ativos", description = "Retorna todos os registros da tabela NCM.")
    public Result<List<Ncm>> listarTodos() {
        return ResultUtil.success("Consulta de NCMs realizada com sucesso.", ncmService.listarTodos());
    }

    // ============================================================
    // ENDPOINT: Buscar NCM por código
    // ============================================================
    @GetMapping("/{codigo}")
    @Operation(summary = "Buscar NCM por código", description = "Retorna um NCM específico com base no código informado.")
    public Result<Ncm> buscarPorCodigo(@PathVariable String codigo) {
        Ncm ncm = ncmService.buscarPorCodigo(codigo);
        if (ncm == null) {
            return ResultUtil.notFound("NCM não encontrado para o código: " + codigo);
        }
        return ResultUtil.success("NCM localizado com sucesso.", ncm);
    }

    // ============================================================
    // ENDPOINT: Sincronizar Tabela NCM (CSV oficial)
    // ============================================================
    @PostMapping("/sincronizar")
    @Operation(summary = "Sincronizar Tabela NCM", description = "Lê o arquivo CSV oficial e atualiza a tabela NCM.")
    public Result<?> sincronizarTabela() {
        return ncmService.sincronizarTabela();
    }
}
