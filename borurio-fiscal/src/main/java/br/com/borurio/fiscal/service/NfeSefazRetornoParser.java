package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.dto.NfeSefazRetorno;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Extrai dados fiscais do retorno SOAP da SEFAZ após transmissão de NF-e.
 *
 * Estrutura esperada do SOAP de retorno:
 *   soap:Envelope / soap:Body / nfeResultMsg / retEnviNFe
 *     cStat        → status do lote (104 = processado)
 *     xMotivo      → descrição do lote
 *     protNFe / infProt
 *       cStat      → status da NF-e individual (100=autorizada, 225=rejeitada…)
 *       xMotivo    → descrição individual
 *       nProt      → número do protocolo (presente apenas quando autorizada ou cancelada)
 *       dhRecbto   → data/hora recebimento
 *       chNFe      → chave de acesso confirmada
 */
@Component
public class NfeSefazRetornoParser {

    private static final Logger log = LoggerFactory.getLogger(NfeSefazRetornoParser.class);

    public NfeSefazRetorno parse(String soapXml) {
        NfeSefazRetorno retorno = new NfeSefazRetorno();

        if (soapXml == null || soapXml.isBlank()) {
            retorno.setCStat(-1);
            retorno.setXMotivo("Resposta SEFAZ vazia");
            return retorno;
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

            Document doc = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(soapXml.getBytes(StandardCharsets.UTF_8)));

            // cStat e xMotivo do lote (retEnviNFe)
            retorno.setCStatLote(parseIntTag(doc, "cStat", 0));
            retorno.setXMotivoLote(firstTagText(doc, "xMotivo"));
            retorno.setVerAplic(firstTagText(doc, "verAplic"));

            // infProt — dados da NF-e individual
            NodeList infProtList = doc.getElementsByTagNameNS("*", "infProt");
            if (infProtList.getLength() > 0) {
                org.w3c.dom.Element infProt = (org.w3c.dom.Element) infProtList.item(0);
                retorno.setCStat(parseIntElement(infProt, "cStat", retorno.getCStatLote()));
                retorno.setXMotivo(firstChildText(infProt, "xMotivo"));
                retorno.setNProt(firstChildText(infProt, "nProt"));
                retorno.setDhRecbto(firstChildText(infProt, "dhRecbto"));
                retorno.setChaveNfe(firstChildText(infProt, "chNFe"));
            } else {
                // Sem infProt = rejeição no nível do lote
                retorno.setCStat(retorno.getCStatLote());
                retorno.setXMotivo(retorno.getXMotivoLote());
            }

        } catch (Exception e) {
            log.warn("[NfeSefazRetornoParser] Falha ao parsear retorno SEFAZ: {}", e.getMessage());
            retorno.setCStat(-1);
            retorno.setXMotivo("Erro ao interpretar resposta: " + e.getMessage());
        }

        return retorno;
    }

    private int parseIntTag(Document doc, String tag, int defaultValue) {
        try {
            String text = firstTagText(doc, tag);
            return text != null ? Integer.parseInt(text.trim()) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private int parseIntElement(org.w3c.dom.Element parent, String tag, int defaultValue) {
        try {
            String text = firstChildText(parent, tag);
            return text != null ? Integer.parseInt(text.trim()) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String firstTagText(Document doc, String localName) {
        NodeList nl = doc.getElementsByTagNameNS("*", localName);
        if (nl.getLength() > 0 && nl.item(0).getFirstChild() != null) {
            return nl.item(0).getFirstChild().getNodeValue();
        }
        return null;
    }

    private String firstChildText(org.w3c.dom.Element parent, String localName) {
        NodeList nl = parent.getElementsByTagNameNS("*", localName);
        if (nl.getLength() > 0 && nl.item(0).getFirstChild() != null) {
            return nl.item(0).getFirstChild().getNodeValue();
        }
        return null;
    }
}
