package br.com.borurio.web.dto;

/**
 * Resposta de {@code POST /api/admin/nfe-emissoes/{emissaoId}/marcar-transporte-nao-entregue}.
 *
 * Modelo "gap": libera o gate E avança {@code nfe_sequencia.ultimo_numero} até {@code numeroNfe}.
 * {@code ultimoNumeroResultante} é o contador após a operação; {@code sequenciaAvancada=true}
 * quando esta chamada moveu o contador. {@code pedidoParaErro=true} quando o pedido de origem
 * voltou a {@code ERRO} (emissível) e a {@code chaveNfe} espúria foi limpa. {@code idempotente=true}
 * quando o ciclo já estava {@code TRANSPORTE_NAO_ENTREGUE} — mesmo aí o contador é reparado se
 * estava atrás.
 */
public record TransporteNaoEntregueResponse(
        Long emissaoId,
        Long pedidoId,
        String serie,
        int numeroNfe,
        Integer cstatSintetico,
        String xmotivo,
        String chaveNfePreservada,
        String estadoAnterior,
        String estadoAtual,
        boolean gateLiberado,
        int ultimoNumeroResultante,
        boolean sequenciaAvancada,
        boolean pedidoParaErro,
        boolean estoqueDesfeito,
        boolean idempotente) {

    public static TransporteNaoEntregueResponse from(TransporteNaoEntregueResultado r) {
        return new TransporteNaoEntregueResponse(
                r.emissaoId(), r.pedidoId(), r.serie(), r.numeroNfe(), r.cstatSintetico(), r.xmotivo(),
                r.chaveNfePreservada(), r.estadoAnterior(), r.estadoAtual(), r.gateLiberado(),
                r.ultimoNumeroResultante(), r.sequenciaAvancada(), r.pedidoParaErro(),
                r.estoqueDesfeito(), r.idempotente());
    }
}
