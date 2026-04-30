package br.com.borurio.web.controller.fiscal;

import br.com.borurio.core.mvc.api.Result;
import br.com.borurio.core.mvc.api.ResultUtil;
import br.com.borurio.fiscal.dto.NfeCceRequest;
import br.com.borurio.fiscal.service.NfeCceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/fiscal/nfe")
@Tag(name = "NF-e", description = "Transmissão e consulta de NF-e junto à SEFAZ")
public class NfeCceController {

    private final NfeCceService nfeCceService;

    public NfeCceController(NfeCceService nfeCceService) {
        this.nfeCceService = nfeCceService;
    }

    @PostMapping("/cce")
    @Operation(summary = "Emite Carta de Correção Eletrônica (CC-e / Evento 110110) para NF-e autorizada")
    public Result<String> corrigir(@RequestBody NfeCceRequest request) {
        log.info("[CC-e] Solicitação | chave={} | seq={}",
                request.getChaveNfe(), request.getSequencia());
        try {
            String resposta = nfeCceService.corrigir(request);
            return ResultUtil.success(resposta);
        } catch (IllegalArgumentException e) {
            log.warn("[CC-e] Dados inválidos | erro={}", e.getMessage());
            return ResultUtil.error(e.getMessage());
        } catch (Exception e) {
            log.error("[CC-e] Falha na transmissão | erro={}", e.getMessage(), e);
            return ResultUtil.error("Falha ao emitir CC-e: " + e.getMessage());
        }
    }
}
