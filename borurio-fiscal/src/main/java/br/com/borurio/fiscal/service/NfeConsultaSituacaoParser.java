package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.dto.NfeConsultaSituacaoRetorno;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Extrai dados fiscais do retorno SOAP da Consulta Situacao NF-e (consSitNFe/retConsSitNFe) —
 * Gate 3 (reconciliacao, 10-08-2026). Deliberadamente separado de {@link NfeSefazRetornoParser}:
 * o schema de retConsSitNFe tem um cStat de topo proprio (ex.: 217="NF-e nao consta na base",
 * 635="mesma serie/numero ja transmitidos, aguardando processamento"), que nao corresponde ao
 * conceito de "cStat do lote" (retEnviNFe) — reaproveitar o parser de emissao misturaria dois
 * vocabularios de cStat diferentes so por coincidencia de nomes de tag.
 *
 * Estrutura esperada:
 *   soap:Envelope / soap:Body / nfeResultMsg / retConsSitNFe
 *     cStat      → resultado da CONSULTA em si (100=localizada e autorizada, 217=nao consta, ...)
 *     xMotivo    → descricao do resultado da consulta
 *     protNFe / infProt (presente apenas quando a SEFAZ localizou um documento)
 *       cStat    → resultado do documento localizado (100/150/205/206/218/...)
 *       chNFe    → chave de acesso do documento localizado — comparar sempre contra a chave
 *                  congelada antes de aceitar qualquer autorizacao
 *       nProt    → numero do protocolo
 *       digVal   → digest do documento localizado, quando disponivel
 *
 * Nunca lanca excecao — falha de parse vira {@link NfeConsultaSituacaoRetorno#falhaParse}, nunca
 * um throw, para o chamador nao confundir "SOAP malformado" com "falha de transporte" (essa
 * distincao e decidida antes deste parser ser chamado, na fronteira de rede).
 */
@Component
public class NfeConsultaSituacaoParser {

    private static final Logger log = LoggerFactory.getLogger(NfeConsultaSituacaoParser.class);

    public NfeConsultaSituacaoRetorno parse(String soapXml) {
        return parse(soapXml, null, null);
    }

    /**
     * Sobrecarga aditiva (12-08-2026, gate de cancelamento) -- alem do que {@link #parse(String)}
     * ja faz, procura o procEventoNFe correspondente a tpEventoAlvo+nSeqEventoAlvo (ex.:
     * cancelamento 110111/1) entre os eventos que a Consulta Situacao pode trazer junto do
     * protNFe. Nunca usada pela reconciliacao de emissao (Gate 3, que so chama parse(String)) --
     * comportamento dela e identico ao de antes desta mudanca.
     */
    public NfeConsultaSituacaoRetorno parse(String soapXml, String tpEventoAlvo, String nSeqEventoAlvo) {
        if (soapXml == null || soapXml.isBlank()) {
            return NfeConsultaSituacaoRetorno.falhaParse("Resposta da Consulta Situacao vazia");
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

            Document doc = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(soapXml.getBytes(StandardCharsets.UTF_8)));

            NodeList retConsSitNFeList = doc.getElementsByTagNameNS("*", "retConsSitNFe");
            if (retConsSitNFeList.getLength() == 0) {
                return NfeConsultaSituacaoRetorno.falhaParse(
                        "Resposta sem elemento retConsSitNFe — formato inesperado da Consulta Situacao");
            }
            Element retorno = (Element) retConsSitNFeList.item(0);

            NfeConsultaSituacaoRetorno result = new NfeConsultaSituacaoRetorno();
            result.setCStat(parseIntElement(retorno, "cStat", -1));
            result.setXMotivo(firstChildText(retorno, "xMotivo"));

            NodeList infProtList = retorno.getElementsByTagNameNS("*", "infProt");
            if (infProtList.getLength() > 0) {
                Element infProt = (Element) infProtList.item(0);
                result.setProtNFePresente(true);
                // infProt.cStat e o resultado do documento localizado — mais especifico que o
                // cStat de topo da consulta quando os dois divergem, mesma precedencia usada no
                // Gate 2 para o retorno de emissao (infProt individual > cStat de nivel superior).
                result.setCStat(parseIntElement(infProt, "cStat", result.getCStat()));
                result.setXMotivo(firstChildText(infProt, "xMotivo"));
                result.setChNFe(firstChildText(infProt, "chNFe"));
                result.setNProt(firstChildText(infProt, "nProt"));
                result.setDhRecbto(firstChildText(infProt, "dhRecbto"));
                result.setDigVal(firstChildText(infProt, "digVal"));
            }

            if (tpEventoAlvo != null) {
                buscarEventoCorrespondente(doc, tpEventoAlvo, nSeqEventoAlvo, result);
            }

            return result;
        } catch (Exception e) {
            log.warn("[NfeConsultaSituacaoParser] Falha ao parsear retorno da Consulta Situacao: {}", e.getMessage());
            return NfeConsultaSituacaoRetorno.falhaParse("Erro ao interpretar resposta: " + e.getMessage());
        }
    }

    /**
     * procEventoNFe (0..N) envolve retEvento/infEvento com o MESMO vocabulario de tags do retorno
     * direto de evento (cStat, xMotivo, chNFe, tpEvento, nSeqEvento, dhRegEvento, nProt) -- so a
     * ocorrencia dentro da Consulta Situacao muda. Filtra explicitamente por tpEvento+nSeqEvento
     * (nunca so o cStat=101 do documento) -- a NF-e pode ter outros eventos associados (CC-e,
     * manifestacao) e aceitar o primeiro procEventoNFe sem checar o tipo/sequencia arriscaria
     * atribuir o resultado errado ao cancelamento.
     */
    private void buscarEventoCorrespondente(Document doc, String tpEventoAlvo, String nSeqEventoAlvo,
                                             NfeConsultaSituacaoRetorno result) {
        NodeList procEventoList = doc.getElementsByTagNameNS("*", "procEventoNFe");
        for (int i = 0; i < procEventoList.getLength(); i++) {
            Element procEvento = (Element) procEventoList.item(i);
            NodeList infEventoList = procEvento.getElementsByTagNameNS("*", "infEvento");
            if (infEventoList.getLength() == 0) continue;
            Element infEvento = (Element) infEventoList.item(0);

            String tpEvento = firstChildText(infEvento, "tpEvento");
            String nSeqEvento = firstChildText(infEvento, "nSeqEvento");
            boolean tipoBate = tpEventoAlvo.equals(tpEvento);
            // Comparacao NUMERICA, nunca textual (achado de banca, 12-08-2026): a SEFAZ pode
            // ecoar nSeqEvento sem zero a esquerda ("1") mesmo quando o Borurio envia com
            // zero-padding ("01") -- igualdade de String quebraria a reconciliacao exatamente no
            // mecanismo usado para sair de 136/573/PENDENTE_CONFIRMACAO. tpEvento continua textual
            // (nunca e numerico, e "110111" tem zero a esquerda significativo).
            boolean seqBate = nSeqEventoAlvo == null || seqNumericamenteIgual(nSeqEventoAlvo, nSeqEvento);
            if (!tipoBate || !seqBate) continue;

            result.setEventoEncontrado(true);
            result.setCStatEvento(parseIntElement(infEvento, "cStat", -1));
            result.setXMotivoEvento(firstChildText(infEvento, "xMotivo"));
            result.setNProtEvento(firstChildText(infEvento, "nProt"));
            result.setDhRegEvento(firstChildText(infEvento, "dhRegEvento"));
            return; // primeiro match e suficiente -- identidade fiscal (chave+tipo+nSeq) e unica
        }
    }

    /** Compara nSeqEvento numericamente ("1" e "01" sao a mesma sequencia) -- nunca textual. */
    private boolean seqNumericamenteIgual(String alvo, String recebido) {
        try {
            return Integer.parseInt(alvo.trim()) == Integer.parseInt(recebido.trim());
        } catch (NumberFormatException | NullPointerException e) {
            return false;
        }
    }

    private int parseIntElement(Element parent, String tag, int defaultValue) {
        try {
            String text = firstChildText(parent, tag);
            return text != null ? Integer.parseInt(text.trim()) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String firstChildText(Element parent, String localName) {
        NodeList nl = parent.getElementsByTagNameNS("*", localName);
        if (nl.getLength() > 0 && nl.item(0).getFirstChild() != null) {
            return nl.item(0).getFirstChild().getNodeValue();
        }
        return null;
    }
}
