package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.dto.NfeEventoRetorno;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate de cancelamento (12-08-2026) — parser de retEnvEvento/retEvento/infEvento. Cobre a
 * distinção revisada entre cStat de LOTE (128=processado) e cStat de EVENTO individual
 * (infEvento), e os casos em que o resultado individual não está disponível (nunca vira
 * rejeição inventada).
 */
class NfeEventoRetornoParserTest {

    private final NfeEventoRetornoParser parser = new NfeEventoRetornoParser();

    private String envelope(String retEnvEventoInterno) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\">"
                + "<soap:Body><nfeResultMsg xmlns=\"http://www.portalfiscal.inf.br/nfe/wsdl/NFeRecepcaoEvento4\">"
                + "<retEnvEvento xmlns=\"http://www.portalfiscal.inf.br/nfe\" versao=\"1.00\">"
                + retEnvEventoInterno
                + "</retEnvEvento>"
                + "</nfeResultMsg></soap:Body></soap:Envelope>";
    }

    private String comInfEvento(String cStatEvento, String xMotivo, String nProt) {
        return "<idLote>1</idLote><tpAmb>2</tpAmb><verAplic>SP2026</verAplic><cStat>128</cStat>"
                + "<xMotivo>Lote de Evento Processado</xMotivo>"
                + "<retEvento versao=\"1.00\"><infEvento Id=\"ID110111352605000000000001915500100000000110000000132\">"
                + "<tpAmb>2</tpAmb><verAplic>SP2026</verAplic><cOrgao>35</cOrgao>"
                + "<cStat>" + cStatEvento + "</cStat><xMotivo>" + xMotivo + "</xMotivo>"
                + "<chNFe>35260500000000000191550010000000011000000013</chNFe>"
                + "<tpEvento>110111</tpEvento><xEvento>Cancelamento</xEvento><nSeqEvento>1</nSeqEvento>"
                + (nProt != null ? "<nProt>" + nProt + "</nProt>" : "")
                + "<dhRegEvento>2026-08-12T10:00:00-03:00</dhRegEvento>"
                + "</infEvento></retEvento>";
    }

    @Test
    @DisplayName("128 + infEvento cStat=135 — resultado individual disponível, extrai todos os campos")
    void lote128ComEvento135_resultadoIndividualDisponivel() {
        NfeEventoRetorno r = parser.parse(envelope(comInfEvento("135", "Evento registrado e vinculado a NF-e", "135260000009999")));

        assertFalse(r.isFalhaParse());
        assertEquals(128, r.getCStatLote());
        assertTrue(r.isInfEventoPresente());
        assertEquals(135, r.getCStatEvento());
        assertEquals("Evento registrado e vinculado a NF-e", r.getXMotivoEvento());
        assertEquals("135260000009999", r.getNProtEvento());
        assertEquals("35260500000000000191550010000000011000000013", r.getChNFeEvento());
        assertEquals("110111", r.getTpEventoRetornado());
        assertEquals("1", r.getNSeqEventoRetornado());
        assertNotNull(r.getDhRegEvento());
        assertTrue(r.isResultadoIndividualDisponivel());
        assertFalse(r.isLoteRejeitado());
    }

    @Test
    @DisplayName("128 + infEvento cStat=136 — resultado individual disponível (136 não é ausência, é um cStat real)")
    void lote128ComEvento136_resultadoIndividualDisponivel() {
        NfeEventoRetorno r = parser.parse(envelope(
                comInfEvento("136", "Evento registrado, mas não vinculado a NF-e", null)));

        assertTrue(r.isResultadoIndividualDisponivel());
        assertEquals(136, r.getCStatEvento());
        assertNull(r.getNProtEvento());
    }

    @Test
    @DisplayName("128 sem infEvento — resultado individual DESCONHECIDO, nunca rejeição inventada")
    void lote128SemInfEvento_resultadoIndividualIndisponivel() {
        String xml = envelope("<idLote>1</idLote><cStat>128</cStat><xMotivo>Lote de Evento Processado</xMotivo>");

        NfeEventoRetorno r = parser.parse(xml);

        assertFalse(r.isFalhaParse());
        assertEquals(128, r.getCStatLote());
        assertFalse(r.isInfEventoPresente());
        assertNull(r.getCStatEvento());
        assertFalse(r.isResultadoIndividualDisponivel());
        assertFalse(r.isLoteRejeitado(), "128 nunca é lote rejeitado, mesmo sem infEvento");
    }

    @Test
    @DisplayName("Rejeição explícita de lote (cStat != 128, sem infEvento) — isLoteRejeitado=true")
    void loteRejeitadoExplicitamente() {
        String xml = envelope("<idLote>1</idLote><cStat>225</cStat><xMotivo>Rejeição: Falha no Schema XML</xMotivo>");

        NfeEventoRetorno r = parser.parse(xml);

        assertFalse(r.isFalhaParse());
        assertEquals(225, r.getCStatLote());
        assertTrue(r.isLoteRejeitado());
        assertFalse(r.isResultadoIndividualDisponivel());
    }

    @Test
    @DisplayName("Resposta vazia — falha de parse, nunca cStat sintético")
    void respostaVazia_falhaParse() {
        NfeEventoRetorno r = parser.parse("");
        assertTrue(r.isFalhaParse());
        assertNotNull(r.getDetalheFalhaParse());
    }

    @Test
    @DisplayName("Resposta nula — falha de parse")
    void respostaNula_falhaParse() {
        assertTrue(parser.parse(null).isFalhaParse());
    }

    @Test
    @DisplayName("XML sem retEnvEvento (formato inesperado) — falha de parse")
    void xmlSemRetEnvEvento_falhaParse() {
        assertTrue(parser.parse("<algumaCoisaTotalmenteDiferente/>").isFalhaParse());
    }

    @Test
    @DisplayName("XML malformado — falha de parse, nunca lança exceção")
    void xmlMalformado_falhaParseNaoLancaExcecao() {
        NfeEventoRetorno r = assertDoesNotThrow(() -> parser.parse("<retEnvEvento><cStat>128</retEnvEvento>"));
        assertTrue(r.isFalhaParse());
    }
}
