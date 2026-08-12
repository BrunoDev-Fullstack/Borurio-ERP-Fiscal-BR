package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.dto.NfeConsultaSituacaoRetorno;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate 3 (10-08-2026) — parser dedicado de retConsSitNFe, separado de NfeSefazRetornoParser
 * porque o vocabulário de cStat de consulta (217/635/etc.) não existe no retorno de emissão.
 */
class NfeConsultaSituacaoParserTest {

    private final NfeConsultaSituacaoParser parser = new NfeConsultaSituacaoParser();

    private String envelope(String retConsSitNFeInterno) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\">"
                + "<soap:Body><nfeResultMsg xmlns=\"http://www.portalfiscal.inf.br/nfe/wsdl/NFeConsultaProtocolo4\">"
                + "<retConsSitNFe xmlns=\"http://www.portalfiscal.inf.br/nfe\" versao=\"4.00\">"
                + retConsSitNFeInterno
                + "</retConsSitNFe>"
                + "</nfeResultMsg></soap:Body></soap:Envelope>";
    }

    @Test
    @DisplayName("Autorizada (100) com protNFe/infProt — extrai cStat/chNFe/nProt/digVal do infProt")
    void autorizadaComProtocolo_extraiDadosDoInfProt() {
        String xml = envelope(
                "<tpAmb>2</tpAmb><verAplic>SP2026</verAplic><cStat>100</cStat><xMotivo>Autorizado</xMotivo>"
                        + "<cUF>35</cUF>"
                        + "<protNFe versao=\"4.00\"><infProt>"
                        + "<tpAmb>2</tpAmb><verAplic>SP2026</verAplic>"
                        + "<chNFe>35260500000000000191550010000000011000000013</chNFe>"
                        + "<dhRecbto>2026-08-10T10:00:00-03:00</dhRecbto>"
                        + "<nProt>135260000000001</nProt>"
                        + "<digVal>abc123==</digVal>"
                        + "<cStat>100</cStat><xMotivo>Autorizado o uso da NF-e</xMotivo>"
                        + "</infProt></protNFe>");

        NfeConsultaSituacaoRetorno r = parser.parse(xml);

        assertFalse(r.isFalhaParse());
        assertEquals(100, r.getCStat());
        assertEquals("Autorizado o uso da NF-e", r.getXMotivo());
        assertEquals("35260500000000000191550010000000011000000013", r.getChNFe());
        assertEquals("135260000000001", r.getNProt());
        assertEquals("abc123==", r.getDigVal());
        assertTrue(r.isProtNFePresente());
        assertTrue(r.isAutorizadaComProtocolo());
    }

    @Test
    @DisplayName("217 (não consta na base) sem protNFe — usa cStat/xMotivo de topo, protNFePresente=false")
    void naoConsta_semProtNFe_usaCStatDeTopo() {
        String xml = envelope("<tpAmb>2</tpAmb><verAplic>SP2026</verAplic><cStat>217</cStat>"
                + "<xMotivo>NF-e não consta na base de dados da SEFAZ</xMotivo><cUF>35</cUF>");

        NfeConsultaSituacaoRetorno r = parser.parse(xml);

        assertFalse(r.isFalhaParse());
        assertEquals(217, r.getCStat());
        assertEquals("NF-e não consta na base de dados da SEFAZ", r.getXMotivo());
        assertFalse(r.isProtNFePresente());
        assertNull(r.getChNFe());
        assertFalse(r.isAutorizadaComProtocolo());
    }

    @Test
    @DisplayName("635 (mesma série/número já transmitidos, aguardando processamento)")
    void processandoAguardando_usaCStatDeTopo() {
        String xml = envelope("<tpAmb>2</tpAmb><cStat>635</cStat>"
                + "<xMotivo>Já existe NF-e com mesma série/número transmitida aguardando processamento</xMotivo><cUF>35</cUF>");

        NfeConsultaSituacaoRetorno r = parser.parse(xml);

        assertEquals(635, r.getCStat());
        assertFalse(r.isProtNFePresente());
    }

    @Test
    @DisplayName("Resposta vazia -- falha de parse, nunca cStat 0 disfarçado de resultado real")
    void respostaVazia_falhaParse() {
        NfeConsultaSituacaoRetorno r = parser.parse("");

        assertTrue(r.isFalhaParse());
        assertNotNull(r.getDetalheFalhaParse());
    }

    @Test
    @DisplayName("Resposta nula -- falha de parse")
    void respostaNula_falhaParse() {
        NfeConsultaSituacaoRetorno r = parser.parse(null);

        assertTrue(r.isFalhaParse());
    }

    @Test
    @DisplayName("XML sem retConsSitNFe (formato inesperado) -- falha de parse, nunca confundida com resposta fiscal")
    void xmlSemRetConsSitNFe_falhaParse() {
        NfeConsultaSituacaoRetorno r = parser.parse("<algumaCoisaTotalmenteDiferente/>");

        assertTrue(r.isFalhaParse());
    }

    @Test
    @DisplayName("XML malformado -- falha de parse, não lança exceção")
    void xmlMalformado_falhaParseNaoLancaExcecao() {
        NfeConsultaSituacaoRetorno r = assertDoesNotThrow(() -> parser.parse("<retConsSitNFe><cStat>100</retConsSitNFe>"));

        assertTrue(r.isFalhaParse());
    }

    @Test
    @DisplayName("205 (já denegada na base) sem protNFe")
    void jaDenegada_semProtNFe() {
        String xml = envelope("<cStat>205</cStat><xMotivo>NF-e já denegada na base de dados da SEFAZ</xMotivo>");

        NfeConsultaSituacaoRetorno r = parser.parse(xml);

        assertEquals(205, r.getCStat());
        assertFalse(r.isAutorizadaComProtocolo());
    }

    // -------------------------------------------------------------------------
    // Gate de cancelamento (12-08-2026) — procEventoNFe (reconciliação via Consulta Situação)
    // -------------------------------------------------------------------------

    private String comProcEventoNFe(String tpEvento, String nSeqEvento, String cStatEvento, String nProt) {
        return "<procEventoNFe versao=\"1.00\"><retEvento versao=\"1.00\"><infEvento>"
                + "<tpAmb>2</tpAmb><cStat>" + cStatEvento + "</cStat><xMotivo>Evento processado</xMotivo>"
                + "<chNFe>35260500000000000191550010000000011000000013</chNFe>"
                + "<tpEvento>" + tpEvento + "</tpEvento><nSeqEvento>" + nSeqEvento + "</nSeqEvento>"
                + (nProt != null ? "<nProt>" + nProt + "</nProt>" : "")
                + "<dhRegEvento>2026-08-12T10:00:00-03:00</dhRegEvento>"
                + "</infEvento></retEvento></procEventoNFe>";
    }

    @Test
    @DisplayName("cStat=101 (cancelada) + procEventoNFe correspondente (110111/1) — extrai cStat/nProt do EVENTO, não inventa nada")
    void canceladaComProcEventoNFeCorrespondente_extraiDadosDoEvento() {
        String xml = envelope(
                "<cStat>101</cStat><xMotivo>Cancelamento de NF-e homologado</xMotivo>"
                        + "<protNFe versao=\"4.00\"><infProt>"
                        + "<chNFe>35260500000000000191550010000000011000000013</chNFe>"
                        + "<nProt>135260000000001</nProt><cStat>101</cStat>"
                        + "<xMotivo>Cancelamento de NF-e homologado</xMotivo>"
                        + "</infProt></protNFe>"
                        + comProcEventoNFe("110111", "1", "135", "135260000009999"));

        NfeConsultaSituacaoRetorno r = parser.parse(xml, "110111", "1");

        assertTrue(r.isEventoEncontrado());
        assertEquals(135, r.getCStatEvento());
        assertEquals("135260000009999", r.getNProtEvento());
        assertNotNull(r.getDhRegEvento());
        assertFalse(r.isCanceladaSemEventoDetalhado(), "evento foi encontrado — não é o caso 'sem evento detalhado'");
    }

    @Test
    @DisplayName("cStat=101 sem procEventoNFe correspondente — isCanceladaSemEventoDetalhado=true, nunca inventa nProtEvento")
    void canceladaSemProcEventoNFe_naoInventaProtocolo() {
        String xml = envelope(
                "<cStat>101</cStat><xMotivo>Cancelamento de NF-e homologado</xMotivo>"
                        + "<protNFe versao=\"4.00\"><infProt>"
                        + "<chNFe>35260500000000000191550010000000011000000013</chNFe>"
                        + "<nProt>135260000000001</nProt><cStat>101</cStat>"
                        + "<xMotivo>Cancelamento de NF-e homologado</xMotivo>"
                        + "</infProt></protNFe>");

        NfeConsultaSituacaoRetorno r = parser.parse(xml, "110111", "1");

        assertFalse(r.isEventoEncontrado());
        assertNull(r.getNProtEvento(), "sem procEventoNFe detalhado, nProtEvento nunca pode ser inventado");
        assertTrue(r.isCanceladaSemEventoDetalhado());
    }

    @Test
    @DisplayName("procEventoNFe de tipo/sequência diferente (ex.: CC-e) — nunca confundido com o cancelamento buscado")
    void procEventoNFeDeOutroTipo_naoConfundeComCancelamento() {
        String xml = envelope(
                "<cStat>100</cStat><xMotivo>Autorizado</xMotivo>"
                        + comProcEventoNFe("110110", "1", "135", "135260000001111")); // CC-e, não cancelamento

        NfeConsultaSituacaoRetorno r = parser.parse(xml, "110111", "1");

        assertFalse(r.isEventoEncontrado(), "procEventoNFe de outro tpEvento nunca deve ser aceito como o evento buscado");
    }

    @Test
    @DisplayName("nSeqEvento comparado numericamente — procEventoNFe com \"1\" corresponde a busca por \"01\" (achado de banca, 12-08-2026)")
    void procEventoNFeNSeqSemZeroAEsquerda_correspondeABuscaComZeroAEsquerda() {
        String xml = envelope(
                "<cStat>101</cStat><xMotivo>Cancelamento de NF-e homologado</xMotivo>"
                        + comProcEventoNFe("110111", "1", "135", "135260000009999")); // SEFAZ ecoa "1", sem padding

        NfeConsultaSituacaoRetorno r = parser.parse(xml, "110111", "01"); // Borurio busca por "01"

        assertTrue(r.isEventoEncontrado(), "\"1\" e \"01\" são a mesma sequência numérica — comparação nunca pode ser textual");
        assertEquals(135, r.getCStatEvento());
    }

    @Test
    @DisplayName("nSeqEvento numericamente diferente (\"2\" vs \"1\") — nunca corresponde")
    void procEventoNFeNSeqNumericamenteDiferente_naoCorresponde() {
        String xml = envelope(
                "<cStat>100</cStat><xMotivo>Autorizado</xMotivo>"
                        + comProcEventoNFe("110111", "2", "135", "135260000009999"));

        NfeConsultaSituacaoRetorno r = parser.parse(xml, "110111", "01");

        assertFalse(r.isEventoEncontrado());
    }

    @Test
    @DisplayName("parse(xml) de 1 argumento (Gate 3, emissão) nunca preenche campos de evento — comportamento inalterado")
    void parseUmArgumento_nuncaPreencheCamposDeEvento() {
        String xml = envelope(
                "<cStat>101</cStat><xMotivo>Cancelamento de NF-e homologado</xMotivo>"
                        + comProcEventoNFe("110111", "1", "135", "135260000009999"));

        NfeConsultaSituacaoRetorno r = parser.parse(xml);

        assertFalse(r.isEventoEncontrado());
        assertNull(r.getCStatEvento());
        assertNull(r.getNProtEvento());
    }
}
