package br.com.borurio.web.controller.fiscal;

import br.com.borurio.core.mvc.api.Result;
import br.com.borurio.core.mvc.api.ResultUtil;
import br.com.borurio.fiscal.service.NfeTransmitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/**
 * =============================================================================
 * CONTROLADOR: NfeEnvioController
 * =============================================================================
 * Responsável por receber XMLs assinados de NF-e (modelo 55) do ERP e repassar
 * ao módulo Fiscal, que realiza a transmissão real para a SEFAZ-SP.
 *
 * Ambientes:
 *   - DEV/HOM: tpAmb = 2
 *   - PRD:     tpAmb = 1
 *
 * Segurança:
 *   - Autenticação JWT via SecurityConfig
 *   - Header obrigatório: CNPJ-Emitente
 *
 * Padrão de resposta:
 *   {code, message, data}
 *
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Revisão: 26/11/2025
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

    /**
     * =========================================================================
     * ENDPOINT — Envio de NF-e (XML Assinado v4.00)
     * =========================================================================
     *
     * Header obrigatório:
     *   CNPJ-Emitente: 12345678000199
     *
     * Body:
     *   XML assinado (application/xml, text/xml ou text/plain)
     *
     * @param xmlNfeAssinado XML da NF-e modelo 55 já assinado digitalmente
     * @param cnpjEmitente  CNPJ do emitente (deve ser o mesmo do certificado)
     */
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
                    [NF-e ENVIO] Nova requisição recebida
                    - CNPJ Emitente: {}
                    - Tamanho XML: {}
                    ====================================================================
                    """,
                    cnpjEmitente,
                    xmlNfeAssinado != null ? xmlNfeAssinado.length() : 0
            );

            // Validação rápida (API Level)
            if (xmlNfeAssinado == null || xmlNfeAssinado.isBlank()) {
                return ResultUtil.error("O XML assinado da NF-e não pode estar vazio.");
            }

            // Chamada ao serviço Fiscal (transmissão SOAP real)
            String respostaSefaz = nfeTransmitService.transmitirXml(xmlNfeAssinado, cnpjEmitente);

            log.info("[NF-e ENVIO] Transmissão finalizada com sucesso | CNPJ={} ", cnpjEmitente);

            return ResultUtil.success(respostaSefaz);

        } catch (IllegalArgumentException ex) {

            log.warn("[NF-e ENVIO] Erro de parâmetros | motivo={}", ex.getMessage());
            return ResultUtil.error("Parâmetros inválidos: " + ex.getMessage());

        } catch (Exception e) {

            log.error("[NF-e ENVIO] ERRO INTERNO durante envio da NF-e | motivo={}", e.getMessage(), e);
            return ResultUtil.error("Falha ao transmitir NF-e: " + e.getMessage());
        }
    }
}
