package br.com.borurio.web.controller.fiscal;

import br.com.borurio.core.mvc.api.Result;
import br.com.borurio.core.mvc.api.ResultUtil;
import br.com.borurio.fiscal.service.NfeTransmitService;   // IMPORT CORRETO
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/**
 * =============================================================================
 * CONTROLADOR: NfeEnvioController
 * =============================================================================
 * Responsável por receber o XML assinado da NF-e (modelo 55) e enviá-lo ao
 * módulo Fiscal, que executa a transmissão REAL para a SEFAZ-SP através do
 * WebService NFeAutorizacao4 (SOAP 1.2 + mTLS com certificado A1).
 *
 * Padrão de resposta:
 *   {code, message, data}
 *
 * Autor: Bruno Ribeiro — Fullstack / DevSecOps
 * Revisão: 03/12/2025
 * =============================================================================
 */
@Slf4j
@RestController
@RequestMapping("/api/fiscal/nfe")
public class NfeEnvioController {

    private final NfeTransmitService nfeTransmitService;

    public NfeEnvioController(NfeTransmitService nfeTransmitService) {
        this.nfeTransmitService = nfeTransmitService;
    }

    // =========================================================================
    // ENDPOINT — ENVIO NF-e (XML Assinado v4.00)
    // =========================================================================

    @PostMapping(
            value = "/enviar",
            consumes = {
                    MediaType.APPLICATION_XML_VALUE,
                    MediaType.TEXT_XML_VALUE,
                    MediaType.TEXT_PLAIN_VALUE
            },
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public Result<String> enviarNfe(
            @RequestBody String xmlNfeAssinado,
            @RequestHeader("CNPJ-Emitente") String cnpjEmitente
    ) {
        try {

            log.info("""
                    ====================================================================
                    [NF-e ENVIO] Requisição recebida
                    - CNPJ Emitente: {}
                    - Tamanho do XML: {}
                    ====================================================================
                    """,
                    cnpjEmitente,
                    xmlNfeAssinado != null ? xmlNfeAssinado.length() : 0
            );

            // ------------------- Validação rápida ------------------- //
            if (xmlNfeAssinado == null || xmlNfeAssinado.isBlank()) {
                return ResultUtil.error("O XML assinado da NF-e está vazio.");
            }

            // ------------------- Transmissão REAL ------------------- //
            String respostaSefaz =
                    nfeTransmitService.transmitirXml(xmlNfeAssinado, cnpjEmitente);

            log.info("[NF-e ENVIO] Transmissão concluída | CNPJ={}", cnpjEmitente);

            return ResultUtil.success(respostaSefaz);

        } catch (IllegalArgumentException ex) {
            log.warn("[NF-e ENVIO] Erro de parâmetros | motivo={}", ex.getMessage());
            return ResultUtil.error("Parâmetros inválidos: " + ex.getMessage());

        } catch (Exception e) {
            log.error("[NF-e ENVIO] ERRO INTERNO | motivo={}", e.getMessage(), e);
            return ResultUtil.error("Erro ao transmitir NF-e: " + e.getMessage());
        }
    }
}
