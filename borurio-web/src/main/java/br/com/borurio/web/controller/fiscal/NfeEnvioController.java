package br.com.borurio.web.controller.fiscal;

import br.com.borurio.fiscal.service.NfeTransmitService;
import br.com.borurio.core.mvc.api.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * =============================================================================
 * CONTROLADOR: NfeEnvioController
 * -----------------------------------------------------------------------------
 * Responsável por receber o XML assinado da NF-e (modelo 55) e enviar à SEFAZ-SP.
 * -----------------------------------------------------------------------------
 * Ambiente padrão: Homologação (tpAmb=2)
 * =============================================================================
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Data: 03/11/2025
 * =============================================================================
 */
@RestController
@RequestMapping("/api/fiscal/nfe")
public class NfeEnvioController {

    private final NfeTransmitService nfeTransmitService;

    @Autowired
    public NfeEnvioController(NfeTransmitService nfeTransmitService) {
        this.nfeTransmitService = nfeTransmitService;
    }

    /**
     * Transmite uma NF-e real (tpAmb=2) para a SEFAZ-SP.
     *
     * @param xmlNfeAssinado XML completo e assinado digitalmente (modelo 55)
     * @return Resposta da SEFAZ (XML SOAP de retorno)
     */
    @PostMapping("/enviar")
    public Result<String> enviarNfe(@RequestBody String xmlNfeAssinado) {
        try {
            String resposta = nfeTransmitService.transmitir(xmlNfeAssinado);
            return Result.ok("NF-e transmitida com sucesso (Homologação).", resposta);
        } catch (Exception e) {
            return Result.error(500, "Falha ao transmitir NF-e: " + e.getMessage());
        }
    }
}
