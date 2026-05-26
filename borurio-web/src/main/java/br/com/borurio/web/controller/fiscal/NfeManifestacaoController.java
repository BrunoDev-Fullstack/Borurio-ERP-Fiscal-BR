package br.com.borurio.web.controller.fiscal;

import br.com.borurio.core.mvc.api.Result;
import br.com.borurio.core.mvc.api.ResultUtil;
import br.com.borurio.fiscal.dto.NfeManifestacaoRequest;
import br.com.borurio.fiscal.service.NfeManifestacaoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/fiscal/nfe")
@Tag(name = "NF-e", description = "Transmissão e consulta de NF-e junto à SEFAZ")
public class NfeManifestacaoController {

    private final NfeManifestacaoService nfeManifestacaoService;

    public NfeManifestacaoController(NfeManifestacaoService nfeManifestacaoService) {
        this.nfeManifestacaoService = nfeManifestacaoService;
    }

    @PostMapping("/manifestar")
    @Operation(summary = "Registra Manifestação do Destinatário (210200 / 210210 / 210220 / 210240)")
    public Result<String> manifestar(@RequestBody NfeManifestacaoRequest request) {
        log.info("[MANIFESTACAO] Solicitação | chave={} | tipo={}",
                request.getChaveNfe(), request.getTipoEvento());
        try {
            String resposta = nfeManifestacaoService.manifestar(request);
            return ResultUtil.success(resposta);
        } catch (IllegalArgumentException e) {
            log.warn("[MANIFESTACAO] Dados inválidos | erro={}", e.getMessage());
            return ResultUtil.error(e.getMessage());
        } catch (Exception e) {
            log.error("[MANIFESTACAO] Falha na transmissão | erro={}", e.getMessage(), e);
            return ResultUtil.error("Falha ao registrar manifestação: " + e.getMessage());
        }
    }
}
