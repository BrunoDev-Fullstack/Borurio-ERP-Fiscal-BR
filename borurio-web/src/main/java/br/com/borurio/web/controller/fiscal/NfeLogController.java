package br.com.borurio.web.controller.fiscal;

import br.com.borurio.app.context.EmpresaContextHolder;
import br.com.borurio.core.mvc.api.PageResponse;
import br.com.borurio.core.mvc.api.Result;
import br.com.borurio.core.mvc.api.ResultUtil;
import br.com.borurio.fiscal.entity.NfeLog;
import br.com.borurio.fiscal.service.NfeLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/fiscal/nfe/logs")
@Tag(name = "NF-e Logs", description = "Auditoria de eventos fiscais NF-e")
public class NfeLogController {

    private final NfeLogService nfeLogService;

    public NfeLogController(NfeLogService nfeLogService) {
        this.nfeLogService = nfeLogService;
    }

    @GetMapping
    @Operation(summary = "Lista registros de auditoria fiscal (paginado)")
    public Result<PageResponse<NfeLog>> listarTodos(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        size = Math.min(size, 100);
        Long empresaId = EmpresaContextHolder.get();
        return ResultUtil.success(nfeLogService.listarPaginado(empresaId, page, size));
    }

    @GetMapping("/{chave}")
    @Operation(summary = "Busca registros de auditoria por chave NF-e (44 dígitos)")
    public Result<List<NfeLog>> buscarPorChave(@PathVariable String chave) {
        List<NfeLog> logs = nfeLogService.buscarPorChave(chave);
        if (logs.isEmpty()) throw new NoSuchElementException("Nenhum log encontrado para a chave: " + chave);
        return ResultUtil.success(logs);
    }
}
