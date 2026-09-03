package br.com.borurio.web.dto;

import br.com.borurio.fiscal.entity.NfeEmissao;

/**
 * Resultado de {@code NfeEmissaoService.abandonarCiclo()} — recovery administrativo (02-09-2026)
 * de um ciclo em AGUARDANDO_CORRECAO cujo dado de origem não pode mais ser corrigido.
 *
 * Modelo "gap" (revisão de 02-09-2026 pós-incidente): a linha de {@code nfe_emissao} não é
 * apagada e ocupa permanentemente o slot {@code (cnpj_emitente, modelo, serie, numero_nfe)} via a
 * UNIQUE {@code uk_nfe_emissao_numero}. Por isso o abandono LIBERA o gate e AVANÇA
 * {@code nfe_sequencia.ultimo_numero} até {@code numeroNfe} (nunca além, nunca regredindo) — o
 * mesmo nNF NÃO volta a ser alocável; o próximo ciclo pega {@code numeroNfe + 1}.
 *
 * {@code ultimoNumeroResultante} é o valor de {@code ultimo_numero} após a operação;
 * {@code sequenciaAvancada} indica se ESTA chamada moveu o contador (inclusive uma chamada
 * idempotente que encontrou o contador atrás e o reparou).
 */
public record AbandonoCicloResultado(
        Long emissaoId,
        Long pedidoId,
        String serie,
        int numeroNfe,
        Integer cstat,
        String xmotivo,
        String estadoAnterior,
        boolean gateLiberado,
        int ultimoNumeroResultante,
        boolean sequenciaAvancada,
        boolean idempotente) {

    public String estadoAtual() {
        return NfeEmissao.Estados.ABANDONADO;
    }

    public static AbandonoCicloResultado abandonado(NfeEmissao e, boolean gateLiberado,
                                                     int ultimoNumeroResultante, boolean sequenciaAvancada) {
        return new AbandonoCicloResultado(e.getId(), e.getPedidoId(), e.getSerie(), e.getNumeroNfe(),
                e.getCstat(), e.getXmotivo(), e.getEstado(), gateLiberado,
                ultimoNumeroResultante, sequenciaAvancada, false);
    }

    public static AbandonoCicloResultado idempotente(NfeEmissao e, boolean gateJaLivre,
                                                      int ultimoNumeroResultante, boolean sequenciaAvancada) {
        return new AbandonoCicloResultado(e.getId(), e.getPedidoId(), e.getSerie(), e.getNumeroNfe(),
                e.getCstat(), e.getXmotivo(), NfeEmissao.Estados.ABANDONADO, gateJaLivre,
                ultimoNumeroResultante, sequenciaAvancada, true);
    }
}
