package br.com.borurio.web.service;

import br.com.borurio.app.context.EmpresaContextHolder;
import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.entity.Pedido;
import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.fiscal.service.CertificadoContexto;
import org.springframework.stereotype.Service;

/**
 * Resolve, a partir de um pedido, a empresa emitente e o certificado corretos pra qualquer
 * operação fiscal pós-emissão (cancelamento, CC-e, consulta) — nunca usa emitente/certificado
 * global quando o pedido identifica um CNPJ. `pedido.cnpjEmitente` é NOT NULL desde a criação
 * da tabela (V014) e é sempre preenchido em PedidoController.criar() — pelo CNPJ enviado
 * explicitamente pela OMS (fluxo multi-CNPJ) ou pelo CNPJ da própria empresa resolvida
 * internamente. Por isso a empresa é sempre resolvida por esse CNPJ, nunca por empresaId —
 * empresaId identifica a empresa "âncora" do cliente OMS (dona do estoque/catálogo), que pode
 * ser diferente do CNPJ que efetivamente emitiu a NF-e no fluxo multi-CNPJ.
 *
 * O tipo de autenticação (token OMS com jti vs. usuário interno) decide só QUAL MECANISMO
 * resolve o certificado — nunca decide qual é a empresa emitente. São responsabilidades
 * diferentes: o CNPJ do pedido diz quem emitiu; o jti diz como autorizar o certificado.
 *
 * Toda falha de resolução (empresa não encontrada, certificado ausente, contexto de segurança
 * inconsistente) lança exceção — nunca devolve contexto parcial nem cai silenciosamente em
 * configuração global. Ver FiscalContexto, que reforça isso recusando ser construído com
 * campo nulo.
 */
@Service
public class FiscalContextoResolver {

    private final EmpresaMapper empresaMapper;
    private final EmpresaCertificadoService empresaCertificadoService;
    private final OmsCertificadoService omsCertificadoService;

    public FiscalContextoResolver(EmpresaMapper empresaMapper,
                                   EmpresaCertificadoService empresaCertificadoService,
                                   OmsCertificadoService omsCertificadoService) {
        this.empresaMapper = empresaMapper;
        this.empresaCertificadoService = empresaCertificadoService;
        this.omsCertificadoService = omsCertificadoService;
    }

    public FiscalContexto resolver(Pedido pedido) {
        String cnpjEmitente = pedido.getCnpjEmitente();
        if (cnpjEmitente == null || cnpjEmitente.isBlank()) {
            throw new IllegalStateException(
                    "Pedido " + pedido.getId() + " não possui cnpjEmitente — impossível resolver "
                            + "contexto fiscal. Dado deveria ser NOT NULL desde a criação do pedido.");
        }

        Empresa empresa = empresaMapper.buscarPorCnpj(cnpjEmitente);
        if (empresa == null) {
            throw new IllegalStateException(
                    "Nenhuma empresa cadastrada para o CNPJ " + cnpjEmitente + " do pedido "
                            + pedido.getId() + " — inconsistência entre pedido e cadastro de empresas.");
        }

        String jtiOms = EmpresaContextHolder.getJtiAuth();

        CertificadoContexto certificado;
        if (jtiOms != null) {
            // Fluxo OMS multi-CNPJ: o certificado é sempre resolvido pelo jti+CNPJ do pedido,
            // nunca pelo EmpresaContextHolder — nesse fluxo ele guarda o id da empresa "âncora"
            // do cliente OMS (ex.: JCHO), que pode ser diferente do CNPJ que emitiu a NF-e
            // (ex.: J.ZHENG). Comparar os dois aqui reintroduziria o bug que este resolver existe
            // pra evitar.
            certificado = omsCertificadoService.resolverPorJtiECnpj(jtiOms, cnpjEmitente); // já lança se ausente/revogado
        } else {
            // Fluxo interno (sem token OMS): aqui sim o EmpresaContextHolder identifica a
            // empresa da sessão logada — precisa ser a mesma do CNPJ resolvido, senão a sessão
            // de uma empresa estaria operando o pedido de outra.
            validarCoerenciaComContextoDeSeguranca(pedido, empresa);
            certificado = empresaCertificadoService.resolverPorEmpresa(empresa)
                    .orElseThrow(() -> new IllegalStateException(
                            "Certificado não configurado para a empresa CNPJ=" + cnpjEmitente
                                    + " (id=" + empresa.getId() + "). Operação fiscal não pode "
                                    + "prosseguir sem um certificado explicitamente resolvido."));
        }

        return new FiscalContexto(empresa, certificado);
    }

    private void validarCoerenciaComContextoDeSeguranca(Pedido pedido, Empresa empresaResolvida) {
        Long empresaIdContexto = EmpresaContextHolder.get();
        if (empresaIdContexto != null && !empresaIdContexto.equals(empresaResolvida.getId())) {
            throw new IllegalStateException(
                    "Pedido " + pedido.getId() + " pertence à empresa id=" + empresaResolvida.getId()
                            + ", mas o contexto de segurança atual é da empresa id=" + empresaIdContexto + ".");
        }
    }
}
