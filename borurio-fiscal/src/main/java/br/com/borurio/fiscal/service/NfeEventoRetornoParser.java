package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.dto.NfeEventoRetorno;
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
 * Extrai dados do retorno SOAP de um evento (retEnvEvento/retEvento/infEvento) -- cancelamento
 * 110111 hoje. Deliberadamente burro: so extrai o que esta no XML, nunca decide se o resultado
 * conta como sucesso/rejeicao/incerto -- essa decisao e do orquestrador (NfeEventoService), que
 * combina cStatLote + presenca de infEvento + falhaParse antes de consultar
 * {@link NfeEventoClassificador}.
 *
 * Estrutura esperada:
 *   soap:Envelope / soap:Body / nfeResultMsg / retEnvEvento
 *     cStat / xMotivo   -> resultado do LOTE (128 = processado; outro valor = lote rejeitado)
 *     retEvento / infEvento (pode estar ausente mesmo com lote=128)
 *       cStat / xMotivo / chNFe / tpEvento / nSeqEvento / dhRegEvento / nProt
 */
@Component
public class NfeEventoRetornoParser {

    private static final Logger log = LoggerFactory.getLogger(NfeEventoRetornoParser.class);

    public NfeEventoRetorno parse(String soapXml) {
        if (soapXml == null || soapXml.isBlank()) {
            return NfeEventoRetorno.falhaParse("Resposta do evento vazia");
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

            Document doc = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(soapXml.getBytes(StandardCharsets.UTF_8)));

            NodeList retEnvEventoList = doc.getElementsByTagNameNS("*", "retEnvEvento");
            if (retEnvEventoList.getLength() == 0) {
                return NfeEventoRetorno.falhaParse(
                        "Resposta sem elemento retEnvEvento -- formato inesperado do retorno de evento");
            }
            Element retEnvEvento = (Element) retEnvEventoList.item(0);

            NfeEventoRetorno result = new NfeEventoRetorno();
            result.setCStatLote(parseIntElement(retEnvEvento, "cStat"));
            result.setXMotivoLote(firstChildText(retEnvEvento, "xMotivo"));

            NodeList infEventoList = doc.getElementsByTagNameNS("*", "infEvento");
            if (infEventoList.getLength() > 0) {
                Element infEvento = (Element) infEventoList.item(0);
                result.setInfEventoPresente(true);
                result.setCStatEvento(parseIntElement(infEvento, "cStat"));
                result.setXMotivoEvento(firstChildText(infEvento, "xMotivo"));
                result.setNProtEvento(firstChildText(infEvento, "nProt"));
                result.setChNFeEvento(firstChildText(infEvento, "chNFe"));
                result.setTpEventoRetornado(firstChildText(infEvento, "tpEvento"));
                result.setNSeqEventoRetornado(firstChildText(infEvento, "nSeqEvento"));
                result.setDhRegEvento(firstChildText(infEvento, "dhRegEvento"));
            }

            return result;
        } catch (Exception e) {
            log.warn("[NfeEventoRetornoParser] Falha ao parsear retorno de evento: {}", e.getMessage());
            return NfeEventoRetorno.falhaParse("Erro ao interpretar resposta: " + e.getMessage());
        }
    }

    private Integer parseIntElement(Element parent, String tag) {
        String text = firstChildText(parent, tag);
        if (text == null) return null;
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return null;
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
