package br.com.borurio.web.controller.fiscal;

import br.com.borurio.core.mvc.api.Result;
import br.com.borurio.core.mvc.api.ResultUtil;
import br.com.borurio.fiscal.service.NfeOrquestradorService;
import br.com.borurio.fiscal.service.NfeTransmitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/fiscal/nfe")
@Tag(name = "NF-e", description = "Transmissão e consulta de NF-e junto à SEFAZ")
public class NfeEnvioController {

    private final NfeOrquestradorService nfeOrquestradorService;
    private final NfeTransmitService nfeTransmitService;

    public NfeEnvioController(NfeOrquestradorService nfeOrquestradorService,
                               NfeTransmitService nfeTransmitService) {
        this.nfeOrquestradorService = nfeOrquestradorService;
        this.nfeTransmitService = nfeTransmitService;
    }

    // =========================================================================
    // ENVIO NF-e
    // =========================================================================

    @PostMapping("/enviar")
    @Operation(summary = "Transmitir NF-e para a SEFAZ")
    public Result<String> enviarNfe(
            @RequestBody String xmlNfe,
            @RequestHeader(value = "CNPJ-Emitente", required = true) String cnpjEmitente) {

        if (xmlNfe == null || xmlNfe.isBlank()) {
            return ResultUtil.error("O XML da NF-e não pode estar vazio.");
        }

        log.info("[NF-e] Envio solicitado | CNPJ={}", cnpjEmitente);

        try {
            String resposta = nfeOrquestradorService.processar(xmlNfe, cnpjEmitente);
            return ResultUtil.success(resposta);
        } catch (Exception e) {
            log.error("[NF-e] Erro ao processar | CNPJ={} | erro={}", cnpjEmitente, e.getMessage(), e);
            return ResultUtil.error("Erro ao processar NF-e: " + e.getMessage());
        }
    }

    // =========================================================================
    // STATUS SEFAZ
    // =========================================================================

    @GetMapping("/status")
    @Operation(summary = "Consultar status do serviço NF-e na SEFAZ-SP")
    public Result<String> status() {
        log.info("[NF-e] Consulta status SEFAZ");
        try {
            String resposta = nfeTransmitService.consultarStatus("SP", 2);
            return ResultUtil.success(resposta);
        } catch (Exception e) {
            log.error("[NF-e] Falha ao consultar status SEFAZ | erro={}", e.getMessage(), e);
            return ResultUtil.error("Serviço SEFAZ indisponível.");
        }
    }
}
