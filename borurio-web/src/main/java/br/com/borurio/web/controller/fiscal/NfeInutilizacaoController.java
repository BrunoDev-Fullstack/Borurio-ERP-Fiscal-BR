package br.com.borurio.web.controller.fiscal;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.core.mvc.api.Result;
import br.com.borurio.core.mvc.api.ResultUtil;
import br.com.borurio.fiscal.dto.NfeInutilizacaoRequest;
import br.com.borurio.fiscal.service.CertificadoContexto;
import br.com.borurio.fiscal.service.NfeInutilizacaoService;
import br.com.borurio.web.service.EmpresaCertificadoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/fiscal/nfe")
@Tag(name = "NF-e", description = "Transmissão e consulta de NF-e junto à SEFAZ")
public class NfeInutilizacaoController {

    private final NfeInutilizacaoService nfeInutilizacaoService;
    private final EmpresaMapper empresaMapper;
    private final EmpresaCertificadoService empresaCertificadoService;

    public NfeInutilizacaoController(NfeInutilizacaoService nfeInutilizacaoService,
                                      EmpresaMapper empresaMapper,
                                      EmpresaCertificadoService empresaCertificadoService) {
        this.nfeInutilizacaoService = nfeInutilizacaoService;
        this.empresaMapper = empresaMapper;
        this.empresaCertificadoService = empresaCertificadoService;
    }

    @PostMapping("/inutilizar")
    @Operation(
            summary = "Inutiliza faixa de numeração de NF-e na SEFAZ (inutNFe 4.00)",
            description = "Endpoint interno/ADMIN — não faz parte do contrato OMS. " +
                          "Campo opcional `cnpjEmitente`: se informado, a empresa e o certificado dessa " +
                          "empresa são obrigatórios (falha explícita se não encontrados) — nunca cai " +
                          "silenciosamente no emitente/certificado global de outra empresa."
    )
    public Result<String> inutilizar(@RequestBody NfeInutilizacaoRequest request) {
        log.info("[Inutilizacao] Solicitação | serie={} | nNFIni={} | nNFFin={} | cnpj={}",
                request.getSerie(), request.getNNFIni(), request.getNNFFin(), request.getCnpjEmitente());
        try {
            String cnpj = request.getCnpjEmitente();
            String resposta = (cnpj != null && !cnpj.isBlank())
                    ? inutilizarComCnpjExplicito(request, cnpj)
                    : nfeInutilizacaoService.inutilizar(request);
            return ResultUtil.success(resposta);
        } catch (IllegalArgumentException e) {
            log.warn("[Inutilizacao] Dados inválidos | erro={}", e.getMessage());
            return ResultUtil.error(e.getMessage());
        } catch (Exception e) {
            log.error("[Inutilizacao] Falha na transmissão | erro={}", e.getMessage(), e);
            return ResultUtil.error("Falha ao inutilizar numeração: " + e.getMessage());
        }
    }

    /**
     * CNPJ explicitamente informado pelo ADMIN — empresa e certificado passam a ser
     * obrigatórios. Se qualquer um dos dois não puder ser resolvido, falha explicitamente em
     * vez de cair no comportamento legado (emitente/certificado global), que pertence a outra
     * empresa.
     */
    private String inutilizarComCnpjExplicito(NfeInutilizacaoRequest request, String cnpj) throws Exception {
        String cnpjDigits = cnpj.replaceAll("\\D", "");
        Empresa empresa = empresaMapper.buscarPorCnpj(cnpjDigits);
        if (empresa == null) {
            throw new IllegalStateException("Nenhuma empresa cadastrada para o CNPJ " + cnpjDigits + ".");
        }
        CertificadoContexto ctx = empresaCertificadoService.resolverPorEmpresa(empresa)
                .orElseThrow(() -> new IllegalStateException(
                        "Certificado não configurado para a empresa CNPJ=" + cnpjDigits + "."));
        return nfeInutilizacaoService.inutilizar(request, cnpjDigits, empresa.getUf(), ctx);
    }
}
