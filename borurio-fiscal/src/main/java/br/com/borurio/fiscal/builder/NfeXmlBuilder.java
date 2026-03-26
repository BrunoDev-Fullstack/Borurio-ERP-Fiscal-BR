package br.com.borurio.fiscal.builder;

import br.com.borurio.fiscal.domain.nfe.*;
import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

public class NfeXmlBuilder {

    public String build(NFe nfe) {

        try {

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.newDocument();

            // ROOT
            Element nfeEl = doc.createElement("NFe");
            nfeEl.setAttribute("xmlns", "http://www.portalfiscal.inf.br/nfe");
            doc.appendChild(nfeEl);

            InfNFe inf = nfe.getInfNFe();

            // infNFe
            Element infEl = doc.createElement("infNFe");
            infEl.setAttribute("Id", inf.getId());
            infEl.setAttribute("versao", inf.getVersao());
            nfeEl.appendChild(infEl);

            // IDE
            Ide ide = inf.getIde();
            Element ideEl = doc.createElement("ide");
            append(doc, ideEl, "cUF", ide.getCUF());
            append(doc, ideEl, "natOp", ide.getNatOp());
            append(doc, ideEl, "mod", ide.getMod());
            append(doc, ideEl, "serie", ide.getSerie());
            append(doc, ideEl, "nNF", ide.getNNF());
            append(doc, ideEl, "dhEmi", ide.getDhEmi());
            append(doc, ideEl, "tpNF", ide.getTpNF());
            append(doc, ideEl, "idDest", ide.getIdDest());
            append(doc, ideEl, "tpAmb", ide.getTpAmb());
            append(doc, ideEl, "finNFe", ide.getFinNFe());
            infEl.appendChild(ideEl);

            // EMIT
            Emit emit = inf.getEmit();
            Element emitEl = doc.createElement("emit");
            append(doc, emitEl, "CNPJ", emit.getCnpj());
            append(doc, emitEl, "xNome", emit.getXNome());
            append(doc, emitEl, "IE", emit.getIe());
            infEl.appendChild(emitEl);

            // DEST
            Dest dest = inf.getDest();
            Element destEl = doc.createElement("dest");

            if (dest.getCpfCnpj().length() == 11) {
                append(doc, destEl, "CPF", dest.getCpfCnpj());
            } else {
                append(doc, destEl, "CNPJ", dest.getCpfCnpj());
            }

            append(doc, destEl, "xNome", dest.getXNome());
            infEl.appendChild(destEl);

            // ITENS
            for (Det det : inf.getDet()) {

                Element detEl = doc.createElement("det");
                detEl.setAttribute("nItem", String.valueOf(det.getNItem()));

                Produto prod = det.getProd();

                Element prodEl = doc.createElement("prod");
                append(doc, prodEl, "cProd", prod.getCProd());
                append(doc, prodEl, "xProd", prod.getXProd());
                append(doc, prodEl, "NCM", prod.getNCM());
                append(doc, prodEl, "CFOP", prod.getCFOP());
                append(doc, prodEl, "uCom", prod.getUCom());
                append(doc, prodEl, "qCom", prod.getQCom());
                append(doc, prodEl, "vUnCom", prod.getVUnCom());
                append(doc, prodEl, "vProd", prod.getVProd());

                detEl.appendChild(prodEl);
                infEl.appendChild(detEl);
            }

            // TOTAL
            Total total = inf.getTotal();

            Element totalEl = doc.createElement("total");
            Element icmsTot = doc.createElement("ICMSTot");

            append(doc, icmsTot, "vProd", total.getVProd());
            append(doc, icmsTot, "vNF", total.getVNF());

            totalEl.appendChild(icmsTot);
            infEl.appendChild(totalEl);

            return XmlUtil.toString(doc);

        } catch (Exception e) {
            throw new RuntimeException("Erro ao gerar XML NF-e", e);
        }
    }

    private void append(Document doc, Element parent, String tag, String value) {
        if (value == null) return;

        Element el = doc.createElement(tag);
        el.setTextContent(value);
        parent.appendChild(el);
    }
}