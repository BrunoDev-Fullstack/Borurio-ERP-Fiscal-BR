package br.com.borurio.web.dto;

/**
 * Resposta de {@code POST /api/admin/nfe-emissoes/{emissaoId}/abandonar}.
 *
 * Modelo "gap": o abandono libera o gate da série E avança
 * {@code nfe_sequencia.ultimo_numero} até {@code numeroNfe} (a linha de {@code nfe_emissao} ocupa
 * o slot {@code uk_nfe_emissao_numero} para sempre, então o nNF não volta a ser alocável).
 * {@code ultimoNumeroResultante} é o contador após a operação; {@code sequenciaAvancada=true}
 * quando esta chamada moveu o contador. {@code idempotente=true} quando o ciclo já estava
 * ABANDONADO — mesmo aí o contador é reparado se estava atrás.
 */
public record AbandonoCicloResponse(
        Long emissaoId,
        Long pedidoId,
        String serie,
        int numeroNfe,
        Integer cstat,
        String xmotivo,
        String estadoAnterior,
        String estadoAtual,
        boolean gateLiberado,
        int ultimoNumeroResultante,
        boolean sequenciaAvancada,
        boolean idempotente) {

    public static AbandonoCicloResponse from(AbandonoCicloResultado r) {
        return new AbandonoCicloResponse(
                r.emissaoId(), r.pedidoId(), r.serie(), r.numeroNfe(), r.cstat(), r.xmotivo(),
                r.estadoAnterior(), r.estadoAtual(), r.gateLiberado(), r.ultimoNumeroResultante(),
                r.sequenciaAvancada(), r.idempotente());
    }
}
