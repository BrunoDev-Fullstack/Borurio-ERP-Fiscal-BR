package br.com.borurio.fiscal.controller.nfe;

import br.com.borurio.fiscal.utils.validator.NfeLocalValidator;
import br.com.borurio.core.mvc.api.Result;
import br.com.borurio.core.mvc.api.ResultUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/fiscal/nfe")
@Tag(name = "NF-e Local Validator", description = "Validação local do XML da NF-e antes do envio à SEFAZ")
@RequiredArgsConstructor
@Slf4j
public class NfeLocalValidatorController {

    private final NfeLocalValidator validator;

    @PostMapping(
            value = "/validar-local",
            consumes = { MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE },
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(summary = "Validação local do XML da NF-e",
            description = "Valida XML contra XSD oficial, estrutura, namespaces e regras da NF-e 4.00.")
    public Result<String> validarLocal(@RequestBody String xml) {

        log.info("[NF-e] Recebida requisição de validação local ({} bytes).", xml.length());

        if (xml == null || xml.isBlank()) {
            log.warn("[NF-e] XML vazio recebido no validarLocal.");
            return ResultUtil.error("XML não recebido ou vazio.");
        }

        validator.validarXmlNfe(xml);

        log.info("[NF-e] XML validado com sucesso.");
        return ResultUtil.success("XML validado localmente com sucesso.");
    }
}
