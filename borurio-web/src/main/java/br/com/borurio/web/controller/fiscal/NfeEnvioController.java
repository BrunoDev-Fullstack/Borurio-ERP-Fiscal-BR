package br.com.borurio.web.controller.fiscal;

import br.com.borurio.core.mvc.api.Result;
import br.com.borurio.core.mvc.api.ResultUtil;
import br.com.borurio.fiscal.service.NfeOrquestradorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * =============================================================================
 * CONTROLADOR: NfeEnvioController
 * =============================================================================
 * Endpoint oficial de envio de NF-e
 * =============================================================================
 */
@RestController
@RequestMapping("/api/fiscal/nfe")
public class NfeEnvioController {

    private final NfeOrquestradorService nfeOrquestradorService;

    @Autowired
    public NfeEnvioController(NfeOrquestradorService nfeOrquestradorService) {
        this.nfeOrquestradorService = nfeOrquestradorService;
    }

    @PostMapping("/enviar")
    public Result<String> enviarNfe(@RequestBody String xmlNfeAssinado) {

        try {

            String resposta = nfeOrquestradorService.processar(xmlNfeAssinado);

            return ResultUtil.success(resposta);

        } catch (Exception e) {

            e.printStackTrace();

            return ResultUtil.error("Erro ao processar NF-e: " + e.getMessage());
        }
    }
}