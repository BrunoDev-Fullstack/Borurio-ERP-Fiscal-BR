package br.com.borurio.web.dto;

import br.com.borurio.fiscal.entity.NfeEmissao;

/**
 * Resultado de {@code NfeEmissaoService.marcarTransporteNaoEntregue()} — recovery administrativo
 * (02-09-2026) de um ciclo em TRANSMITIDO/PENDENTE_CONFIRMACAO cuja transmissão foi
 * comprovadamente rejeitada no transporte/gateway antes de chegar ao autorizador da SEFAZ.
 *
 * Modelo "gap" (revisão de 02-09-2026 pós-incidente): igual ao abandono, LIBERA o gate e AVANÇA
 * {@code nfe_sequencia.ultimo_numero} até {@code numeroNfe} (a linha de {@code nfe_emissao} ocupa
 * o slot {@code uk_nfe_emissao_numero} para sempre). {@code ultimoNumeroResultante} é o contador
 * após a operação; {@code sequenciaAvancada} indica se esta chamada moveu o contador.
 * {@code pedidoParaErro} indica que o pedido de origem voltou a {@code ERRO} (estado emissível)
 * com a {@code chaveNfe} espúria limpa.
 */
public record TransporteNaoEntregueResultado(
        Long emissaoId,
        Long pedidoId,
        String serie,
        int numeroNfe,
        Integer cstatSintetico,
        String xmotivo,
        String chaveNfePreservada,
        String estadoAnterior,
        boolean gateLiberado,
        int ultimoNumeroResultante,
        boolean sequenciaAvancada,
        boolean pedidoParaErro,
        boolean estoqueDesfeito,
        boolean idempotente) {

    public String estadoAtual() {
        return NfeEmissao.Estados.TRANSPORTE_NAO_ENTREGUE;
    }

    public static TransporteNaoEntregueResultado aplicado(NfeEmissao e, boolean gateLiberado,
                                                           int ultimoNumeroResultante, boolean sequenciaAvancada,
                                                           boolean estoqueDesfeito) {
        return new TransporteNaoEntregueResultado(e.getId(), e.getPedidoId(), e.getSerie(), e.getNumeroNfe(),
                e.getCstat(), e.getXmotivo(), e.getChaveNfe(), e.getEstado(), gateLiberado,
                ultimoNumeroResultante, sequenciaAvancada, true, estoqueDesfeito, false);
    }

    public static TransporteNaoEntregueResultado idempotente(NfeEmissao e, boolean gateJaLivre,
                                                              int ultimoNumeroResultante, boolean sequenciaAvancada) {
        return new TransporteNaoEntregueResultado(e.getId(), e.getPedidoId(), e.getSerie(), e.getNumeroNfe(),
                e.getCstat(), e.getXmotivo(), e.getChaveNfe(), NfeEmissao.Estados.TRANSPORTE_NAO_ENTREGUE,
                gateJaLivre, ultimoNumeroResultante, sequenciaAvancada, false, false, true);
    }
}
