package br.com.borurio.web.controller.fiscal;

import br.com.borurio.core.mvc.api.Result;
import br.com.borurio.core.mvc.api.ResultUtil;
import br.com.borurio.fiscal.service.NfeTransmitService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * =============================================================================
 * CONTROLADOR: NfeEnvioController
 * =============================================================================
 * Responsável por receber o XML assinado da NF-e (modelo 55) e enviar à SEFAZ-SP.
 * Ambiente padrão: Homologação (tpAmb=2)
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

            // CORREÇÃO: método atualizado conforme nova interface
            String resposta = nfeTransmitService.transmitirXml(
                    xmlNfeAssinado,
                    "00000000000000", // CNPJ mock (ajustar depois)
                    "SP",              // UF
                    2                  // ambiente homologação
            );

            return ResultUtil.success(resposta);

        } catch (Exception e) {

            return ResultUtil.error("Falha ao transmitir NF-e: " + e.getMessage());
        }
    }
}