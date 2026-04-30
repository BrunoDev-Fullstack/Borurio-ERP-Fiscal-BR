package br.com.borurio.web.controller.fiscal;

import br.com.borurio.core.mvc.api.Result;
import br.com.borurio.core.mvc.api.ResultUtil;
import br.com.borurio.fiscal.dto.NfeInutilizacaoRequest;
import br.com.borurio.fiscal.service.NfeInutilizacaoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/fiscal/nfe")
@Tag(name = "NF-e", description = "Transmissão e consulta de NF-e junto à SEFAZ")
public class NfeInutilizacaoController {

    private final NfeInutilizacaoService nfeInutilizacaoService;

    public NfeInutilizacaoController(NfeInutilizacaoService nfeInutilizacaoService) {
        this.nfeInutilizacaoService = nfeInutilizacaoService;
    }

    @PostMapping("/inutilizar")
    @Operation(summary = "Inutiliza faixa de numeração de NF-e na SEFAZ (inutNFe 4.00)")
    public Result<String> inutilizar(@RequestBody NfeInutilizacaoRequest request) {
        log.info("[Inutilizacao] Solicitação | serie={} | nNFIni={} | nNFFin={}",
                request.getSerie(), request.getNNFIni(), request.getNNFFin());
        try {
            String resposta = nfeInutilizacaoService.inutilizar(request);
            return ResultUtil.success(resposta);
        } catch (IllegalArgumentException e) {
            log.warn("[Inutilizacao] Dados inválidos | erro={}", e.getMessage());
            return ResultUtil.error(e.getMessage());
        } catch (Exception e) {
            log.error("[Inutilizacao] Falha na transmissão | erro={}", e.getMessage(), e);
            return ResultUtil.error("Falha ao inutilizar numeração: " + e.getMessage());
        }
    }
}
