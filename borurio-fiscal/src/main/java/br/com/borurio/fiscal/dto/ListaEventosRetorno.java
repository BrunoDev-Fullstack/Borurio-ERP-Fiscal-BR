package br.com.borurio.fiscal.dto;

import java.util.List;

/**
 * Resultado de {@code NfeConsultaSituacaoParser.listarEventosPorTipo} -- usado no bootstrap de
 * sequência histórica (gate de CC-e, 12-08-2026). {@code falhaParse=true} significa "não foi
 * possível determinar os eventos existentes" -- o chamador nunca deve assumir lista vazia nesse
 * caso (falha fechada, nunca assume ultimo_nseq_registrado=0 sem certeza).
 */
public record ListaEventosRetorno(boolean falhaParse, String detalheFalhaParse,
                                   List<EventoEncontrado> eventos) {

    public record EventoEncontrado(int nSeqEvento, int cStat) {}

    public static ListaEventosRetorno falha(String detalhe) {
        return new ListaEventosRetorno(true, detalhe, List.of());
    }

    public static ListaEventosRetorno ok(List<EventoEncontrado> eventos) {
        return new ListaEventosRetorno(false, null, eventos);
    }
}
