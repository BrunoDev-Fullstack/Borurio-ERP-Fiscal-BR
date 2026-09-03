package br.com.borurio.web.controller.fiscal;

import br.com.borurio.core.mvc.api.Result;
import br.com.borurio.core.mvc.api.ResultUtil;
import br.com.borurio.fiscal.dto.NfeCancelamentoRequest;
import br.com.borurio.fiscal.service.NfeCancelamentoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/fiscal/nfe")
@Tag(name = "NF-e", description = "Transmissão e consulta de NF-e junto à SEFAZ")
public class NfeCancelamentoController {

    private final NfeCancelamentoService nfeCancelamentoService;

    public NfeCancelamentoController(NfeCancelamentoService nfeCancelamentoService) {
        this.nfeCancelamentoService = nfeCancelamentoService;
    }

    @PostMapping("/cancelar")
    @Operation(summary = "Cancela NF-e autorizada junto à SEFAZ (Evento 110111)")
    public Result<String> cancelar(@RequestBody NfeCancelamentoRequest request) {
        log.info("[Cancelamento] Solicitação recebida | chave={}", request.getChaveNfe());
        try {
            String resposta = nfeCancelamentoService.cancelar(request);
            return ResultUtil.success(resposta);
        } catch (IllegalArgumentException e) {
            log.warn("[Cancelamento] Dados inválidos | erro={}", e.getMessage());
            return ResultUtil.error(e.getMessage());
        } catch (Exception e) {
            log.error("[Cancelamento] Falha na transmissão | erro={}", e.getMessage(), e);
            return ResultUtil.error("Falha ao cancelar NF-e: " + e.getMessage());
        }
    }
}
