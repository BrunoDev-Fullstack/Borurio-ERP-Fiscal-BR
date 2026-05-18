package br.com.borurio.fiscal.danfe;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Extrai campos do XML NF-e assinado (namespace http://www.portalfiscal.inf.br/nfe). */
@Component
public class DanfeXmlParser {

    private static final String NS_NFE = "http://www.portalfiscal.inf.br/nfe";

    public DanfeData parse(String xmlNfe, String nProt, String dhRecbto, String cStat, String chaveNfe) {
        try {
            Document doc = parseDoc(xmlNfe);
            XPath xp = buildXPath();

            DanfeData d = new DanfeData();

            // IDE
            d.natOp  = val(xp, doc, "//nfe:ide/nfe:natOp");
            d.dhEmi  = val(xp, doc, "//nfe:ide/nfe:dhEmi");
            d.nNF    = val(xp, doc, "//nfe:ide/nfe:nNF");
            d.serie  = val(xp, doc, "//nfe:ide/nfe:serie");
            d.tpAmb  = val(xp, doc, "//nfe:ide/nfe:tpAmb");

            // EMITENTE
            d.emitXNome   = val(xp, doc, "//nfe:emit/nfe:xNome");
            d.emitXFant   = val(xp, doc, "//nfe:emit/nfe:xFant");
            d.emitCnpj    = val(xp, doc, "//nfe:emit/nfe:CNPJ");
            d.emitIe      = val(xp, doc, "//nfe:emit/nfe:IE");
            d.emitCrt     = val(xp, doc, "//nfe:emit/nfe:CRT");
            d.emitXLgr    = val(xp, doc, "//nfe:emit/nfe:enderEmit/nfe:xLgr");
            d.emitNro     = val(xp, doc, "//nfe:emit/nfe:enderEmit/nfe:nro");
            d.emitXBairro = val(xp, doc, "//nfe:emit/nfe:enderEmit/nfe:xBairro");
            d.emitXMun    = val(xp, doc, "//nfe:emit/nfe:enderEmit/nfe:xMun");
            d.emitUf      = val(xp, doc, "//nfe:emit/nfe:enderEmit/nfe:UF");
            d.emitCep     = val(xp, doc, "//nfe:emit/nfe:enderEmit/nfe:CEP");

            // DESTINATÁRIO
            d.destXNome   = val(xp, doc, "//nfe:dest/nfe:xNome");
            String cnpj   = val(xp, doc, "//nfe:dest/nfe:CNPJ");
            String cpf    = val(xp, doc, "//nfe:dest/nfe:CPF");
            d.destCpfCnpj = cnpj != null && !cnpj.isBlank() ? cnpj : cpf;
            d.destIe      = val(xp, doc, "//nfe:dest/nfe:IE");
            d.destXLgr    = val(xp, doc, "//nfe:dest/nfe:enderDest/nfe:xLgr");
            d.destNro     = val(xp, doc, "//nfe:dest/nfe:enderDest/nfe:nro");
            d.destXBairro = val(xp, doc, "//nfe:dest/nfe:enderDest/nfe:xBairro");
            d.destXMun    = val(xp, doc, "//nfe:dest/nfe:enderDest/nfe:xMun");
            d.destUf      = val(xp, doc, "//nfe:dest/nfe:enderDest/nfe:UF");
            d.destCep     = val(xp, doc, "//nfe:dest/nfe:enderDest/nfe:CEP");

            // ITENS
            NodeList detList = (NodeList) xp.compile("//nfe:det")
                    .evaluate(doc, XPathConstants.NODESET);
            List<DanfeData.Item> itens = new ArrayList<>();
            for (int i = 0; i < detList.getLength(); i++) {
                org.w3c.dom.Node detNode = detList.item(i);
                DanfeData.Item item = new DanfeData.Item();
                item.nItem   = parseInt(detNode.getAttributes().getNamedItem("nItem"));
                item.cProd   = valNode(xp, detNode, "nfe:prod/nfe:cProd");
                item.xProd   = valNode(xp, detNode, "nfe:prod/nfe:xProd");
                item.ncm     = valNode(xp, detNode, "nfe:prod/nfe:NCM");
                item.cfop    = valNode(xp, detNode, "nfe:prod/nfe:CFOP");
                item.uCom    = valNode(xp, detNode, "nfe:prod/nfe:uCom");
                item.qCom    = valNode(xp, detNode, "nfe:prod/nfe:qCom");
                item.vUnCom  = valNode(xp, detNode, "nfe:prod/nfe:vUnCom");
                item.vProd   = valNode(xp, detNode, "nfe:prod/nfe:vProd");
                item.orig    = valNode(xp, detNode, ".//nfe:orig");
                item.csosn   = valNode(xp, detNode, ".//nfe:CSOSN");
                itens.add(item);
            }
            d.itens = itens;

            // TOTAIS
            d.vProd = val(xp, doc, "//nfe:ICMSTot/nfe:vProd");
            d.vNF   = val(xp, doc, "//nfe:ICMSTot/nfe:vNF");

            // ADICIONAIS
            d.infCpl = val(xp, doc, "//nfe:infAdic/nfe:infCpl");

            // PROTOCOLO
            d.chaveNfe = chaveNfe;
            d.nProt    = nProt;
            d.dhRecbto = dhRecbto;
            d.cStat    = cStat;

            return d;

        } catch (Exception e) {
            throw new IllegalStateException("Falha ao parsear XML NF-e para DANFE: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------

    private Document parseDoc(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private XPath buildXPath() {
        XPath xp = XPathFactory.newInstance().newXPath();
        xp.setNamespaceContext(new NamespaceContext() {
            @Override public String getNamespaceURI(String prefix) {
                return "nfe".equals(prefix) ? NS_NFE : "";
            }
            @Override public String getPrefix(String ns) { return null; }
            @Override public Iterator<String> getPrefixes(String ns) { return null; }
        });
        return xp;
    }

    private String val(XPath xp, Object ctx, String expr) {
        try {
            String v = (String) xp.compile(expr).evaluate(ctx, XPathConstants.STRING);
            return v != null && !v.isBlank() ? v.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String valNode(XPath xp, org.w3c.dom.Node node, String expr) {
        try {
            XPathExpression compiled = xp.compile(expr);
            String v = (String) compiled.evaluate(node, XPathConstants.STRING);
            return v != null && !v.isBlank() ? v.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private int parseInt(org.w3c.dom.Node attr) {
        if (attr == null) return 0;
        try { return Integer.parseInt(attr.getNodeValue()); } catch (Exception e) { return 0; }
    }
}
