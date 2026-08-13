package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.dto.ListaEventosRetorno;
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
import java.util.ArrayList;
import java.util.List;

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
     * procEventoNFe (0..N) contem DOIS blocos irmaos: {@code evento} (o pedido original ecoado --
     * infEvento SEM cStat/nProt/dhRegEvento) e {@code retEvento} (a resposta da SEFAZ -- infEvento
     * COM cStat/nProt/dhRegEvento). CORRECAO (12-08-2026, achado ao implementar o gate de CC-e):
     * a versao anterior deste metodo fazia {@code procEvento.getElementsByTagNameNS(infEvento)}
     * sem escopar por retEvento primeiro -- como getElementsByTagNameNS busca em TODA a subarvore,
     * se `evento` aparece antes de `retEvento` no documento (ordem natural: pedido, depois
     * resposta), o codigo pegava o infEvento ERRADO. tpEvento/nSeqEvento existem nos dois blocos e
     * batiam do mesmo jeito, entao o match "funcionava", mas cStat/nProt/dhRegEvento nunca existem
     * no infEvento do pedido -- resultava em cStatEvento=-1 (default), que NfeEventoClassificador
     * trata como REJEITADO por omissao. Ou seja: uma reconciliacao de evento genuinamente
     * bem-sucedido podia ser classificada como rejeitado, silenciosamente, sem excecao nenhuma.
     * O defeito ja existia no codigo de cancelamento commitado em 59b92d5 (nunca havia sido
     * corrigido ate agora) -- afeta 136/573 de cancelamento tambem. Corrigido escopando retEvento
     * explicitamente antes de procurar infEvento.
     */
    private void buscarEventoCorrespondente(Document doc, String tpEventoAlvo, String nSeqEventoAlvo,
                                             NfeConsultaSituacaoRetorno result) {
        NodeList procEventoList = doc.getElementsByTagNameNS("*", "procEventoNFe");
        for (int i = 0; i < procEventoList.getLength(); i++) {
            Element procEvento = (Element) procEventoList.item(i);

            Element retEvento = firstChildElement(procEvento, "retEvento");
            if (retEvento == null) continue;
            NodeList infEventoRetList = retEvento.getElementsByTagNameNS("*", "infEvento");
            if (infEventoRetList.getLength() == 0) continue;
            Element infEventoRet = (Element) infEventoRetList.item(0);

            String tpEvento = firstChildText(infEventoRet, "tpEvento");
            String nSeqEvento = firstChildText(infEventoRet, "nSeqEvento");
            boolean tipoBate = tpEventoAlvo.equals(tpEvento);
            // Comparacao NUMERICA, nunca textual (achado de banca, 12-08-2026): a SEFAZ pode
            // ecoar nSeqEvento sem zero a esquerda ("1") mesmo quando o Borurio envia com
            // zero-padding ("01") -- igualdade de String quebraria a reconciliacao exatamente no
            // mecanismo usado para sair de 136/573/PENDENTE_CONFIRMACAO. tpEvento continua textual
            // (nunca e numerico, e "110111" tem zero a esquerda significativo).
            boolean seqBate = nSeqEventoAlvo == null || seqNumericamenteIgual(nSeqEventoAlvo, nSeqEvento);
            if (!tipoBate || !seqBate) continue;

            result.setEventoEncontrado(true);
            result.setCStatEvento(parseIntElement(infEventoRet, "cStat", -1));
            result.setXMotivoEvento(firstChildText(infEventoRet, "xMotivo"));
            result.setNProtEvento(firstChildText(infEventoRet, "nProt"));
            result.setDhRegEvento(firstChildText(infEventoRet, "dhRegEvento"));
            extrairConteudoEventoOriginal(procEvento, result);
            return; // primeiro match e suficiente -- identidade fiscal (chave+tipo+nSeq) e unica
        }
    }

    /** Primeiro filho DIRETO (nao subarvore inteira) com o localName informado -- escopo estrito. */
    private Element firstChildElement(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node node = children.item(i);
            if (node.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE && localName.equals(node.getLocalName())) {
                return (Element) node;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Gate CC-e (12-08-2026)
    // -------------------------------------------------------------------------

    // evento/infEvento/detEvento = conteudo ORIGINAL submetido (ex.: xCorrecao da CC-e) --
    // escopado separadamente de retEvento, usado pela reconciliacao de CC-e pra comparar se a
    // sequencia encontrada pertence ao MESMO conteudo desta operacao (achado de banca,
    // 12-08-2026: cStat=573/identidade batendo nao prova que o conteudo bate). Extraido como
    // metodo proprio (nao inline em buscarEventoCorrespondente) para manter o bugfix de
    // retEvento/infEvento (cancelamento, ja commitado em 59b92d5) isolado do Gate CC-e no diff.
    private void extrairConteudoEventoOriginal(Element procEvento, NfeConsultaSituacaoRetorno result) {
        Element eventoOriginal = firstChildElement(procEvento, "evento");
        if (eventoOriginal != null) {
            NodeList detEventoList = eventoOriginal.getElementsByTagNameNS("*", "detEvento");
            if (detEventoList.getLength() > 0) {
                result.setConteudoEventoEncontrado(firstChildText((Element) detEventoList.item(0), "xCorrecao"));
            }
        }
    }

    /**
     * Lista TODOS os procEventoNFe de um tipo (ex.: "110110") presentes na Consulta Situacao --
     * bootstrap de sequencia historica (gate de CC-e, 12-08-2026): antes da primeira reserva pra
     * uma chave sem nfe_evento_sequencia local, o Borurio precisa saber se ja existe CC-e
     * registrada por um caminho anterior (codigo legado), porque NfeLog nao e fonte confiavel
     * (SUCCESS = round-trip SOAP recebido, nao cStat homologado). Nunca decide sozinho qual e
     * "a maior sequencia registrada" -- so devolve os pares (nSeq, cStat) encontrados; o chamador
     * aplica sua propria classificacao (ex.: NfeCceClassificador) pra decidir quais contam como
     * efetivamente registrados. falhaParse=true significa "nao foi possivel determinar" -- o
     * chamador NUNCA deve assumir ultimo_nseq_registrado=0 nesse caso (falha fechada).
     */
    public ListaEventosRetorno listarEventosPorTipo(String soapXml, String tpEventoAlvo) {
        if (soapXml == null || soapXml.isBlank()) {
            return ListaEventosRetorno.falha("Resposta da Consulta Situacao vazia");
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
                return ListaEventosRetorno.falha("Resposta sem elemento retConsSitNFe");
            }

            List<ListaEventosRetorno.EventoEncontrado> eventos = new ArrayList<>();
            NodeList procEventoList = doc.getElementsByTagNameNS("*", "procEventoNFe");
            for (int i = 0; i < procEventoList.getLength(); i++) {
                Element procEvento = (Element) procEventoList.item(i);
                Element retEvento = firstChildElement(procEvento, "retEvento");
                if (retEvento == null) continue;
                NodeList infEventoRetList = retEvento.getElementsByTagNameNS("*", "infEvento");
                if (infEventoRetList.getLength() == 0) continue;
                Element infEventoRet = (Element) infEventoRetList.item(0);

                if (!tpEventoAlvo.equals(firstChildText(infEventoRet, "tpEvento"))) continue;

                String nSeqTexto = firstChildText(infEventoRet, "nSeqEvento");
                Integer nSeq = parseIntSeguro(nSeqTexto);
                Integer cStat = parseIntElement(infEventoRet, "cStat", -1);
                if (nSeq == null) continue; // sem sequencia legivel, nao entra na lista
                eventos.add(new ListaEventosRetorno.EventoEncontrado(nSeq, cStat));
            }
            return ListaEventosRetorno.ok(eventos);
        } catch (Exception e) {
            log.warn("[NfeConsultaSituacaoParser] Falha ao listar eventos por tipo: {}", e.getMessage());
            return ListaEventosRetorno.falha("Erro ao interpretar resposta: " + e.getMessage());
        }
    }

    private Integer parseIntSeguro(String texto) {
        if (texto == null) return null;
        try {
            return Integer.parseInt(texto.trim());
        } catch (NumberFormatException e) {
            return null;
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
