package br.com.borurio.fiscal.controller.configuracoes;

import br.com.borurio.core.mvc.api.Result;
import br.com.borurio.core.mvc.api.ResultUtil;
import br.com.borurio.fiscal.dto.FiscalConfigDTO;
import br.com.borurio.fiscal.service.config.FiscalConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/fiscal/configuracoes")
@Tag(
        name = "Configurações Fiscais",
        description = "Retorna listas fiscais pré-definidas usadas para configuração de CFOP, CST, CSOSN, IPI, PIS/COFINS e Tipos de Operação"
)
public class FiscalConfigController {

    private final FiscalConfigService service;

    @GetMapping
    @Operation(summary = "Listar todas as configurações fiscais", description = "Retorna todas as pré-listas fiscais para uso no ERP")
    public Result<FiscalConfigDTO> listar() {
        return ResultUtil.success(service.carregarConfiguracoes());
    }
}
