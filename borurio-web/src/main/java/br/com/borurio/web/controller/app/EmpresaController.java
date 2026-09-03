package br.com.borurio.web.controller.app;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.service.EmpresaService;
import br.com.borurio.core.mvc.api.Result;
import br.com.borurio.core.mvc.api.ResultUtil;
import br.com.borurio.web.dto.EmpresaAtualizacaoRequest;
import br.com.borurio.web.dto.EmpresaResponse;
import br.com.borurio.web.service.CertSenhaEncryptor;
import br.com.borurio.web.service.EmpresaAtualizacaoService;
import br.com.borurio.web.service.EmpresaCertificadoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/app/empresas")
@Tag(name = "Empresas", description = "Gestão de empresas emitentes (multi-tenancy)")
public class EmpresaController {

    private final EmpresaService empresaService;
    private final CertSenhaEncryptor encryptor;
    private final EmpresaCertificadoService empresaCertificadoService;
    private final EmpresaAtualizacaoService empresaAtualizacaoService;

    public EmpresaController(EmpresaService empresaService,
                             CertSenhaEncryptor encryptor,
                             EmpresaCertificadoService empresaCertificadoService,
                             EmpresaAtualizacaoService empresaAtualizacaoService) {
        this.empresaService = empresaService;
        this.encryptor = encryptor;
        this.empresaCertificadoService = empresaCertificadoService;
        this.empresaAtualizacaoService = empresaAtualizacaoService;
    }

    @GetMapping
    @Operation(summary = "Lista todas as empresas cadastradas")
    public Result<List<EmpresaResponse>> listar() {
        return ResultUtil.success(
                empresaService.listarTodas().stream().map(EmpresaResponse::from).toList());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Busca empresa por ID")
    public Result<EmpresaResponse> buscarPorId(@PathVariable Long id) {
        return ResultUtil.success(EmpresaResponse.from(empresaService.buscarPorId(id)));
    }

    @GetMapping("/cnpj/{cnpj}")
    @Operation(summary = "Busca empresa por CNPJ")
    public Result<EmpresaResponse> buscarPorCnpj(@PathVariable String cnpj) {
        return ResultUtil.success(EmpresaResponse.from(empresaService.buscarPorCnpj(cnpj)));
    }

    @PostMapping
    @Operation(summary = "Cadastra nova empresa emitente")
    public Result<EmpresaResponse> salvar(@Valid @RequestBody Empresa empresa) {
        if (empresa.getCertSenha() != null && !empresa.getCertSenha().isBlank()) {
            empresa.setCertSenha(encryptor.encrypt(empresa.getCertSenha()));
        }
        return ResultUtil.success(EmpresaResponse.from(empresaService.salvar(empresa)));
    }

    /**
     * Atualização PARCIAL explícita (Rota B, 13-08-2026) — campo ausente no JSON preserva o valor
     * persistido; campo presente aplica o valor enviado (null limpa campos opcionais, é rejeitado
     * com 422 para campos obrigatórios). CNPJ nunca é alterado por este endpoint — ver
     * {@link EmpresaAtualizacaoService}. Nunca usa {@link Empresa} como corpo da requisição
     * (evita mass-assignment de id/criadoEm/atualizadoEm e a ambiguidade omitido-vs-null).
     */
    @PutMapping("/{id}")
    @Operation(summary = "Atualiza parcialmente os dados da empresa",
            description = "Campos ausentes no corpo preservam o valor atual; campos presentes são aplicados "
                    + "(null explícito limpa campos opcionais, é rejeitado com 422 para campos obrigatórios). "
                    + "CNPJ não pode ser alterado por este endpoint.")
    public Result<EmpresaResponse> atualizar(@PathVariable Long id,
                                             @RequestBody EmpresaAtualizacaoRequest request) {
        Empresa atualizada = empresaAtualizacaoService.aplicar(id, request);
        empresaCertificadoService.invalidar(id);
        return ResultUtil.success(EmpresaResponse.from(atualizada));
    }
}
