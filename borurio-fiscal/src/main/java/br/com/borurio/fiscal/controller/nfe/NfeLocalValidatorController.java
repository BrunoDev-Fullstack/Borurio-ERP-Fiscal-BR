package br.com.borurio.fiscal.controller.nfe;

import br.com.borurio.fiscal.utils.validator.NfeLocalValidator;
import br.com.borurio.core.mvc.api.Result;
import br.com.borurio.core.mvc.api.ResultUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/fiscal/nfe")
@Tag(name = "NF-e Local Validator", description = "Validação local do XML da NF-e antes do envio à SEFAZ")
@RequiredArgsConstructor
public class NfeLocalValidatorController {

    private final NfeLocalValidator validator;

    @PostMapping("/validar-local")
    @Operation(summary = "Validação local do XML da NF-e",
            description = "Valida XML contra XSD oficial, estrutura e tag raiz obrigatória.")
    public Result<String> validarLocal(@RequestBody String xml) {
        validator.validarXmlNfe(xml);
        return ResultUtil.success("XML validado localmente com sucesso.");
    }
}

