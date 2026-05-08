package br.com.borurio.web.controller.fiscal;

import br.com.borurio.core.mvc.api.Result;
import br.com.borurio.core.mvc.api.ResultUtil;
import br.com.borurio.fiscal.config.EmitenteProperties;
import br.com.borurio.fiscal.dto.NfeEmissaoRequest;
import br.com.borurio.fiscal.dto.NfeGeracaoResult;
import br.com.borurio.fiscal.service.NfeOrquestradorService;
import br.com.borurio.fiscal.service.NfeTransmitService;
import br.com.borurio.web.service.NfeGeracaoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/fiscal/nfe")
@Tag(name = "NF-e", description = "Transmissão e consulta de NF-e junto à SEFAZ")
public class NfeEnvioController {

    private final NfeOrquestradorService nfeOrquestradorService;
    private final NfeTransmitService nfeTransmitService;
    private final NfeGeracaoService nfeGeracaoService;
    private final EmitenteProperties emitente;

    @Value("${sefaz.tpAmb:2}")
    private int tpAmb;

    public NfeEnvioController(NfeOrquestradorService nfeOrquestradorService,
                               NfeTransmitService nfeTransmitService,
                               NfeGeracaoService nfeGeracaoService,
                               EmitenteProperties emitente) {
        this.nfeOrquestradorService = nfeOrquestradorService;
        this.nfeTransmitService = nfeTransmitService;
        this.nfeGeracaoService = nfeGeracaoService;
        this.emitente = emitente;
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
    // GERAÇÃO DE NF-e A PARTIR DE DADOS DE NEGÓCIO
    // =========================================================================

    @PostMapping("/gerar")
    @Operation(summary = "Gerar e transmitir NF-e a partir de dados estruturados de negócio")
    public Result<String> gerarNfe(@RequestBody NfeEmissaoRequest request) {
        log.info("[NF-e] Geração solicitada | dest={} | itens={}",
                request.getDestCnpjCpf(), request.getItens() != null ? request.getItens().size() : 0);
        try {
            NfeGeracaoResult result = nfeGeracaoService.gerar(request);
            return ResultUtil.success(result.getSoapRetorno());
        } catch (IllegalArgumentException e) {
            log.warn("[NF-e] Dados inválidos para geração | erro={}", e.getMessage());
            return ResultUtil.error(e.getMessage());
        } catch (Exception e) {
            log.error("[NF-e] Erro ao gerar NF-e | erro={}", e.getMessage(), e);
            return ResultUtil.error("Erro ao gerar NF-e: " + e.getMessage());
        }
    }

    // =========================================================================
    // STATUS SEFAZ
    // =========================================================================

    @GetMapping("/status")
    @Operation(summary = "Consultar status do serviço NF-e na SEFAZ-SP")
    public Result<String> status() {
        log.info("[NF-e] Consulta status SEFAZ | tpAmb={}", tpAmb);
        try {
            String uf = (emitente.getUf() != null && !emitente.getUf().isBlank())
                    ? emitente.getUf() : "SP";
            String resposta = nfeTransmitService.consultarStatus(uf, tpAmb);
            return ResultUtil.success(resposta);
        } catch (Exception e) {
            log.error("[NF-e] Falha ao consultar status SEFAZ | erro={}", e.getMessage(), e);
            return ResultUtil.error("Serviço SEFAZ indisponível.");
        }
    }

    // =========================================================================
    // CONSULTA NF-e POR CHAVE DE ACESSO
    // =========================================================================

    @GetMapping("/{chave}")
    @Operation(summary = "Consultar situação de NF-e pela chave de acesso (44 dígitos)")
    public Result<String> consultarNfe(@PathVariable String chave) {
        String chaveNormalizada = chave != null ? chave.replaceAll("\\D", "") : "";
        if (chaveNormalizada.length() != 44) {
            return ResultUtil.error("Chave de acesso inválida: deve conter exatamente 44 dígitos numéricos.");
        }
        log.info("[NF-e] Consulta por chave | chave={} | tpAmb={}", chaveNormalizada, tpAmb);
        try {
            String uf = (emitente.getUf() != null && !emitente.getUf().isBlank())
                    ? emitente.getUf() : "SP";
            String resposta = nfeTransmitService.consultarNfe(chaveNormalizada, uf, tpAmb);
            return ResultUtil.success(resposta);
        } catch (IllegalArgumentException e) {
            log.warn("[NF-e] Chave inválida na consulta | erro={}", e.getMessage());
            return ResultUtil.error(e.getMessage());
        } catch (Exception e) {
            log.error("[NF-e] Falha ao consultar NF-e | chave={} | erro={}", chaveNormalizada, e.getMessage(), e);
            return ResultUtil.error("Falha ao consultar NF-e na SEFAZ.");
        }
    }
}
