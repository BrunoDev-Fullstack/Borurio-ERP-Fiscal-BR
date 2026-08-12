package br.com.borurio.fiscal.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate de cancelamento (12-08-2026) — matriz de classificação do cStat de infEvento, revisada
 * contra o catálogo oficial de cStat de evento (135/155/136/573) antes da implementação.
 */
class NfeEventoClassificadorTest {

    private final NfeEventoClassificador classificador = new NfeEventoClassificador();

    @Test
    @DisplayName("135 — Evento registrado e vinculado a NF-e — REGISTRADO, sem foraDoPrazo")
    void cStat135_registrado() {
        var r = classificador.classificar(135);
        assertEquals(br.com.borurio.fiscal.entity.NfeEvento.Estados.REGISTRADO, r.estado());
        assertFalse(r.foraDoPrazo());
    }

    @Test
    @DisplayName("155 — Cancelamento homologado fora do prazo — REGISTRADO com foraDoPrazo=true")
    void cStat155_registradoForaDoPrazo() {
        var r = classificador.classificar(155);
        assertEquals(br.com.borurio.fiscal.entity.NfeEvento.Estados.REGISTRADO, r.estado());
        assertTrue(r.foraDoPrazo());
    }

    @Test
    @DisplayName("136 — Evento registrado mas NAO vinculado — PENDENTE_CONFIRMACAO, nunca rejeitado")
    void cStat136_pendenteConfirmacao() {
        var r = classificador.classificar(136);
        assertEquals(br.com.borurio.fiscal.entity.NfeEvento.Estados.PENDENTE_CONFIRMACAO, r.estado());
    }

    @Test
    @DisplayName("573 — Duplicidade de Evento — PENDENTE_CONFIRMACAO (nunca prova sozinho o desfecho)")
    void cStat573_pendenteConfirmacao() {
        var r = classificador.classificar(573);
        assertEquals(br.com.borurio.fiscal.entity.NfeEvento.Estados.PENDENTE_CONFIRMACAO, r.estado());
    }

    @Test
    @DisplayName("Rejeição fiscal comum (ex.: 280, protocolo divergente) — REJEITADO, terminal")
    void rejeicaoFiscalComum_rejeitado() {
        var r = classificador.classificar(280);
        assertEquals(br.com.borurio.fiscal.entity.NfeEvento.Estados.REJEITADO, r.estado());
        assertFalse(r.foraDoPrazo());
    }

    @Test
    @DisplayName("580 — Evento fora de sequência — REJEITADO (nSeqEvento incorreto, nunca deveria acontecer para 110111 se o codigo estiver correto)")
    void cStat580_rejeitado() {
        var r = classificador.classificar(580);
        assertEquals(br.com.borurio.fiscal.entity.NfeEvento.Estados.REJEITADO, r.estado());
    }
}
