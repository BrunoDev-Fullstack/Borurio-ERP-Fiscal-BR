package br.com.borurio.web.controller.fiscal;

import br.com.borurio.core.mvc.api.Result;
import br.com.borurio.core.mvc.api.ResultUtil;
import br.com.borurio.fiscal.service.NfeTransmitService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * =============================================================================
 * CONTROLADOR: NfeEnvioController
 * -----------------------------------------------------------------------------
 * Responsável por iniciar o envio da NF-e (modelo 55).
 *
 * Fluxo:
 *  - Carrega o XML base via classpath
 *  - Delegar ao módulo fiscal a validação, assinatura e transmissão
 *
 * IMPORTANTE:
 * - Não recebe XML por request
 * - Não assina XML
 * - Não conhece certificado, XSD ou SOAP
 * =============================================================================
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * =============================================================================
 */
@RestController
@RequestMapping("/api/fiscal/nfe")
public class NfeEnvioController {

    private final NfeTransmitService nfeTransmitService;

    public NfeEnvioController(NfeTransmitService nfeTransmitService) {
        this.nfeTransmitService = nfeTransmitService;
    }

    @PostMapping("/enviar")
    public Result<String> enviarNfe() {
        try {
            // XML base carregado via classpath (ambiente controlado pelo profile)
            Resource resource = new ClassPathResource("xml/hom/enviNFe-hom.xml");

            if (!resource.exists()) {
                return ResultUtil.error("XML de envio da NF-e não encontrado no classpath.");
            }

            try (InputStream inputStream = resource.getInputStream()) {
                String xmlBase = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);

                // O módulo fiscal decide se valida, assina e transmite
                String resposta = nfeTransmitService.transmitirXml(xmlBase);

                return ResultUtil.success(resposta);
            }

        } catch (Exception e) {
            return ResultUtil.error("Falha ao enviar NF-e: " + e.getMessage());
        }
    }
}
