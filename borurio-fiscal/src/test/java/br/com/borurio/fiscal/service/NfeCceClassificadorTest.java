package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.entity.NfeEventoIdempotencia;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Gate CC-e (12-08-2026) — matriz própria, deliberadamente distinta de NfeEventoClassificador
 * (cancelamento): 155 (homologado fora do prazo) não existe pra CC-e; 594 (limite de sequência)
 * não existe pra cancelamento.
 */
class NfeCceClassificadorTest {

    private final NfeCceClassificador classificador = new NfeCceClassificador();

    @Test
    @DisplayName("135 — Evento registrado e vinculado a NF-e — REGISTRADO")
    void cStat135_registrado() {
        assertEquals(NfeEventoIdempotencia.Estados.REGISTRADO, classificador.classificar(135).estado());
    }

    @Test
    @DisplayName("136 — Evento registrado mas NAO vinculado — PENDENTE_CONFIRMACAO")
    void cStat136_pendenteConfirmacao() {
        assertEquals(NfeEventoIdempotencia.Estados.PENDENTE_CONFIRMACAO, classificador.classificar(136).estado());
    }

    @Test
    @DisplayName("573 — Duplicidade de Evento — PENDENTE_CONFIRMACAO (nunca decide sozinho)")
    void cStat573_pendenteConfirmacao() {
        assertEquals(NfeEventoIdempotencia.Estados.PENDENTE_CONFIRMACAO, classificador.classificar(573).estado());
    }

    @Test
    @DisplayName("594 — Sequência maior/inválida — REJEITADO, terminal")
    void cStat594_rejeitado() {
        assertEquals(NfeEventoIdempotencia.Estados.REJEITADO, classificador.classificar(594).estado());
    }

    @Test
    @DisplayName("Rejeição fiscal comum — REJEITADO")
    void rejeicaoComum_rejeitado() {
        assertEquals(NfeEventoIdempotencia.Estados.REJEITADO, classificador.classificar(280).estado());
    }

    @Test
    @DisplayName("155 (fora de prazo, semântica de cancelamento) nunca deve aparecer na matriz de CC-e — cai em REJEITADO por não ser um código conhecido de CC-e")
    void cStat155_naoEspecificoDeCce_caiEmRejeitado() {
        assertEquals(NfeEventoIdempotencia.Estados.REJEITADO, classificador.classificar(155).estado());
    }
}
