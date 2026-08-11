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
}
