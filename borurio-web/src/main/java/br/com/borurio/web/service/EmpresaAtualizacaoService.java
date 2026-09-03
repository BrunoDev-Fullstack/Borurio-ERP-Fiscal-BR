package br.com.borurio.web.service;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.app.service.EmpresaService;
import br.com.borurio.web.dto.EmpresaAtualizacaoRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rota B (13-08-2026) — aplica {@link EmpresaAtualizacaoRequest} (atualização PARCIAL explícita)
 * sobre a {@link Empresa} persistida, campo a campo, e reaproveita {@link EmpresaService#atualizar}
 * (e por baixo dele, {@code EmpresaMapper.atualizar} inalterado) para persistir a entidade já
 * consolidada. Merge deliberadamente explícito por campo — nunca reflection/BeanUtils, que
 * reintroduziria a ambiguidade "omitido vs. null" que este desenho existe pra eliminar.
 *
 * Semântica por grupo de campo (ver auditoria de 13-08-2026):
 *   obrigatórios do estado persistido (cnpj, razaoSocial, crt, uf, serieNfePadrao,
 *   indFinalPadrao, ativo, controleEstoqueAtivo):
 *     ausente -> preserva; valor válido -> atualiza; null explícito -> 422; vazio/inválido -> 422
 *     (mesma semântica de validação já usada em POST/criação).
 *   nullable (nomeFantasia, ie, endereço, certPath, certSenha, certTipo):
 *     ausente -> preserva; presente (mesmo null) -> aplica exatamente o que veio, null limpa.
 *   cnpj é o único obrigatório com regra adicional: só aceita o MESMO valor já persistido (após
 *   normalização) ou omissão — nunca uma troca real (ver {@link BusinessException#cnpjImutavelNaAtualizacao}).
 *
 * Fronteira transacional (14-08-2026, correção do lost update comprovado pela banca): leitura
 * bloqueante ({@link EmpresaMapper#buscarPorIdParaAtualizar}) + merge + validação + UPDATE rodam
 * dentro de um único {@link TransactionTemplate} explícito, nunca via self-invocation de método
 * {@code @Transactional} (mesmo padrão de {@code OmsAuthorizationAdminService}/
 * {@code NfeCceOrquestradorService}, documentado ali: anotação não tem efeito fora do proxy do
 * Spring) — garante que a leitura bloqueada e o UPDATE participam da MESMA transação/conexão,
 * fechando a janela em que duas requisições concorrentes liam o mesmo estado antes de qualquer
 * gravação. A transação adquire só o lock da própria linha de empresa — nenhum lock fiscal
 * adicional (nfe_sequencia/nfe_emissao), então não participa da ordem canônica de lock do motor
 * fiscal. A criptografia de {@code certSenha} (única operação não trivial do fluxo) é preparada
 * ANTES de abrir a transação — nunca segura o lock de linha por causa dela.
 */
@Service
public class EmpresaAtualizacaoService {

    private final EmpresaService empresaService;
    private final EmpresaMapper empresaMapper;
    private final CertSenhaEncryptor encryptor;
    private final TransactionTemplate transactionTemplate;

    public EmpresaAtualizacaoService(EmpresaService empresaService, EmpresaMapper empresaMapper,
                                      CertSenhaEncryptor encryptor, PlatformTransactionManager transactionManager) {
        this.empresaService = empresaService;
        this.empresaMapper = empresaMapper;
        this.encryptor = encryptor;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public Empresa aplicar(Long id, EmpresaAtualizacaoRequest req) {
        // certSenha: mesma regra de criptografia já usada hoje no controller (nunca reencripta um
        // valor preservado, que já está em ENC(...) no banco — só criptografa quando um valor NOVO
        // e não-vazio chega neste request). Preparada FORA da transação: AES-256-GCM não depende do
        // estado bloqueado, e não deve segurar o lock de linha por causa dela.
        boolean certSenhaPresente = req.presente("certSenha");
        String certSenhaPreparada = null;
        if (certSenhaPresente) {
            String novaSenha = req.getCertSenha();
            certSenhaPreparada = novaSenha != null && !novaSenha.isBlank() ? encryptor.encrypt(novaSenha) : novaSenha;
        }
        final String certSenhaFinal = certSenhaPreparada;

        return transactionTemplate.execute(status -> {
            Empresa atual = empresaMapper.buscarPorIdParaAtualizar(id);
            if (atual == null) {
                throw new IllegalArgumentException("Empresa não encontrada: id=" + id);
            }

            Empresa consolidada = new Empresa();
            consolidada.setId(atual.getId());

            // CNPJ nunca muda por este caminho — só valida que, se enviado, é o mesmo já persistido.
            consolidada.setCnpj(atual.getCnpj());
            if (req.presente("cnpj")) {
                if (req.getCnpj() == null) {
                    throw BusinessException.campoObrigatorioNaoPodeSerRemovido("cnpj");
                }
                String cnpjNormalizado = req.getCnpj().replaceAll("\\D", "");
                if (!cnpjNormalizado.equals(atual.getCnpj())) {
                    throw BusinessException.cnpjImutavelNaAtualizacao(atual.getCnpj(), cnpjNormalizado);
                }
                // Mesmo valor — aceito, sem efeito (idempotente).
            }

            consolidada.setRazaoSocial(aplicarObrigatorioTexto(req, "razaoSocial", req.getRazaoSocial(),
                    atual.getRazaoSocial(), "Razão social é obrigatória"));
            consolidada.setCrt(aplicarObrigatorioTexto(req, "crt", req.getCrt(),
                    atual.getCrt(), "CRT é obrigatório"));
            consolidada.setUf(aplicarObrigatorioTexto(req, "uf", req.getUf(),
                    atual.getUf(), "UF é obrigatória"));
            consolidada.setSerieNfePadrao(aplicarObrigatorioTexto(req, "serieNfePadrao", req.getSerieNfePadrao(),
                    atual.getSerieNfePadrao(), "Série NF-e padrão é obrigatória"));
            consolidada.setIndFinalPadrao(aplicarObrigatorioTexto(req, "indFinalPadrao", req.getIndFinalPadrao(),
                    atual.getIndFinalPadrao(), "indFinalPadrao é obrigatório"));

            consolidada.setAtivo(aplicarObrigatorioBooleano(req, "ativo", req.getAtivo(), atual.getAtivo()));
            consolidada.setControleEstoqueAtivo(aplicarObrigatorioBooleano(req, "controleEstoqueAtivo",
                    req.getControleEstoqueAtivo(), atual.getControleEstoqueAtivo()));

            consolidada.setNomeFantasia(aplicarNullable(req, "nomeFantasia", req.getNomeFantasia(), atual.getNomeFantasia()));
            consolidada.setIe(aplicarNullable(req, "ie", req.getIe(), atual.getIe()));
            consolidada.setLogradouro(aplicarNullable(req, "logradouro", req.getLogradouro(), atual.getLogradouro()));
            consolidada.setNumero(aplicarNullable(req, "numero", req.getNumero(), atual.getNumero()));
            consolidada.setBairro(aplicarNullable(req, "bairro", req.getBairro(), atual.getBairro()));
            consolidada.setMunicipio(aplicarNullable(req, "municipio", req.getMunicipio(), atual.getMunicipio()));
            consolidada.setCodigoMunicipio(aplicarNullable(req, "codigoMunicipio", req.getCodigoMunicipio(), atual.getCodigoMunicipio()));
            consolidada.setCep(aplicarNullable(req, "cep", req.getCep(), atual.getCep()));
            consolidada.setCertPath(aplicarNullable(req, "certPath", req.getCertPath(), atual.getCertPath()));
            consolidada.setCertTipo(aplicarNullable(req, "certTipo", req.getCertTipo(), atual.getCertTipo()));

            consolidada.setCertSenha(certSenhaPresente ? certSenhaFinal : atual.getCertSenha());

            return empresaService.atualizar(id, consolidada);
        });
    }

    private String aplicarObrigatorioTexto(EmpresaAtualizacaoRequest req, String campo, String novoValor,
                                            String valorAtual, String mensagemInvalido) {
        if (!req.presente(campo)) {
            return valorAtual;
        }
        if (novoValor == null) {
            throw BusinessException.campoObrigatorioNaoPodeSerRemovido(campo);
        }
        if (novoValor.isBlank()) {
            throw BusinessException.campoObrigatorioInvalido(campo, mensagemInvalido);
        }
        return novoValor;
    }

    private Boolean aplicarObrigatorioBooleano(EmpresaAtualizacaoRequest req, String campo,
                                                Boolean novoValor, Boolean valorAtual) {
        if (!req.presente(campo)) {
            return valorAtual;
        }
        if (novoValor == null) {
            throw BusinessException.campoObrigatorioNaoPodeSerRemovido(campo);
        }
        return novoValor;
    }

    /** Ausente preserva; presente aplica o valor exatamente como veio (null limpa de propósito). */
    private String aplicarNullable(EmpresaAtualizacaoRequest req, String campo, String novoValor, String valorAtual) {
        return req.presente(campo) ? novoValor : valorAtual;
    }
}
