package br.com.borurio.fiscal.builder;

import br.com.borurio.fiscal.domain.nfe.*;
import org.springframework.stereotype.Component;
import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.util.List;

@Component
public class NfeXmlBuilder {

    private static final String NS = "http://www.portalfiscal.inf.br/nfe";

    public String build(NFe nfe) {

        try {

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.newDocument();

            Element nfeEl = doc.createElementNS(NS, "NFe");
            doc.appendChild(nfeEl);

            InfNFe inf = nfe.getInfNFe();

            Element infEl = doc.createElementNS(NS, "infNFe");
            infEl.setAttribute("Id", inf.getId());
            infEl.setAttribute("versao", inf.getVersao());
            nfeEl.appendChild(infEl);

            // ================= IDE =================
            Ide ide = inf.getIde();
            Element ideEl = doc.createElementNS(NS, "ide");

            append(doc, ideEl, "cUF",      ide.getCUF());
            append(doc, ideEl, "cNF",      ide.getCNF());
            append(doc, ideEl, "natOp",    ide.getNatOp());
            append(doc, ideEl, "mod",      ide.getMod());
            append(doc, ideEl, "serie",    ide.getSerie());
            append(doc, ideEl, "nNF",      ide.getNNF());
            append(doc, ideEl, "dhEmi",    ide.getDhEmi());
            append(doc, ideEl, "tpNF",     ide.getTpNF());
            append(doc, ideEl, "idDest",   ide.getIdDest());
            append(doc, ideEl, "cMunFG",   ide.getCMunFG());
            append(doc, ideEl, "tpImp",    ide.getTpImp());
            append(doc, ideEl, "tpEmis",   ide.getTpEmis());
            append(doc, ideEl, "cDV",      ide.getCDV());
            append(doc, ideEl, "tpAmb",    ide.getTpAmb());
            append(doc, ideEl, "finNFe",   ide.getFinNFe());
            append(doc, ideEl, "indFinal", ide.getIndFinal());
            append(doc, ideEl, "indPres",  ide.getIndPres());
            append(doc, ideEl, "procEmi",  ide.getProcEmi());
            append(doc, ideEl, "verProc",  ide.getVerProc());

            infEl.appendChild(ideEl);

            // ================= EMIT =================
            Emit emit = inf.getEmit();
            Element emitEl = doc.createElementNS(NS, "emit");

            append(doc, emitEl, "CNPJ",  emit.getCnpj());
            append(doc, emitEl, "xNome", emit.getXNome());
            append(doc, emitEl, "xFant", emit.getXFant());

            EnderEmit end = emit.getEnderEmit();
            if (end != null) {
                Element enderEmitEl = doc.createElementNS(NS, "enderEmit");

                append(doc, enderEmitEl, "xLgr",    end.getXLgr());
                append(doc, enderEmitEl, "nro",     end.getNro());
                append(doc, enderEmitEl, "xBairro", end.getXBairro());
                append(doc, enderEmitEl, "cMun",    end.getCMun());
                append(doc, enderEmitEl, "xMun",    end.getXMun());
                append(doc, enderEmitEl, "UF",      end.getUF());
                append(doc, enderEmitEl, "CEP",     end.getCEP());
                append(doc, enderEmitEl, "cPais",   end.getCPais());
                append(doc, enderEmitEl, "xPais",   end.getXPais());

                emitEl.appendChild(enderEmitEl);
            }

            append(doc, emitEl, "IE",  emit.getIe());
            append(doc, emitEl, "CRT", emit.getCrt());

            infEl.appendChild(emitEl);

            // ================= DEST =================
            infEl.appendChild(buildDest(doc, inf.getDest()));

            // ================= DET =================
            List<Det> itens = inf.getDet();
            if (itens != null) {
                for (Det det : itens) {
                    infEl.appendChild(buildDet(doc, det));
                }
            }

            // ================= TOTAL =================
            infEl.appendChild(buildTotal(doc, inf.getTotal()));

            // ================= TRANSP =================
            infEl.appendChild(buildTransp(doc));

            // ================= PAG =================
            infEl.appendChild(buildPag(doc, inf.getTotal()));

            return XmlUtil.toString(doc);

        } catch (Exception e) {
            throw new RuntimeException("Erro ao gerar XML NF-e", e);
        }
    }

    // -----------------------------------------------------------------
    // DEST
    // -----------------------------------------------------------------
    private Element buildDest(Document doc, Dest dest) {
        Element destEl = doc.createElementNS(NS, "dest");

        String cpfCnpj = dest.getCpfCnpj();
        if (cpfCnpj != null && cpfCnpj.length() == 14) {
            append(doc, destEl, "CNPJ", cpfCnpj);
        } else {
            append(doc, destEl, "CPF", cpfCnpj);
        }

        append(doc, destEl, "xNome",     dest.getXNome());
        append(doc, destEl, "indIEDest", dest.getIndIEDest());
        append(doc, destEl, "IE",        dest.getIe());

        return destEl;
    }

    // -----------------------------------------------------------------
    // DET
    // -----------------------------------------------------------------
    private Element buildDet(Document doc, Det det) {
        Element detEl = doc.createElementNS(NS, "det");
        detEl.setAttribute("nItem", String.valueOf(det.getNItem()));

        Produto p = det.getProd();
        Element prodEl = doc.createElementNS(NS, "prod");

        append(doc, prodEl, "cProd",    p.getCProd());
        append(doc, prodEl, "cEAN",     "SEM GTIN");
        append(doc, prodEl, "xProd",    p.getXProd());
        append(doc, prodEl, "NCM",      p.getNCM());
        append(doc, prodEl, "CFOP",     p.getCFOP());
        append(doc, prodEl, "uCom",     p.getUCom());
        append(doc, prodEl, "qCom",     p.getQCom());
        append(doc, prodEl, "vUnCom",   p.getVUnCom());
        append(doc, prodEl, "vProd",    p.getVProd());
        append(doc, prodEl, "cEANTrib", "SEM GTIN");
        append(doc, prodEl, "uTrib",    p.getUCom());
        append(doc, prodEl, "qTrib",    p.getQCom());
        append(doc, prodEl, "vUnTrib",  p.getVUnCom());
        append(doc, prodEl, "indTot",   "1");

        detEl.appendChild(prodEl);
        detEl.appendChild(buildImposto(doc));

        return detEl;
    }

    // -----------------------------------------------------------------
    // IMPOSTO — ICMS40 (Isento) + PISNt + COFINSNt
    // Regime tributário mínimo válido para homologação SEFAZ
    // -----------------------------------------------------------------
    private Element buildImposto(Document doc) {
        Element impostoEl = doc.createElementNS(NS, "imposto");

        // ICMS — CST 40 (Isento): orig + CST são os únicos campos obrigatórios
        Element icmsEl   = doc.createElementNS(NS, "ICMS");
        Element icms40El = doc.createElementNS(NS, "ICMS40");
        append(doc, icms40El, "orig", "0");
        append(doc, icms40El, "CST",  "40");
        icmsEl.appendChild(icms40El);
        impostoEl.appendChild(icmsEl);

        // PIS — CST 07 (Operação isenta): apenas CST é obrigatório em PISNt
        Element pisEl   = doc.createElementNS(NS, "PIS");
        Element pisNtEl = doc.createElementNS(NS, "PISNt");
        append(doc, pisNtEl, "CST", "07");
        pisEl.appendChild(pisNtEl);
        impostoEl.appendChild(pisEl);

        // COFINS — CST 07 (Operação isenta): apenas CST é obrigatório em COFINSNt
        Element cofinsEl   = doc.createElementNS(NS, "COFINS");
        Element cofinsNtEl = doc.createElementNS(NS, "COFINSNt");
        append(doc, cofinsNtEl, "CST", "07");
        cofinsEl.appendChild(cofinsNtEl);
        impostoEl.appendChild(cofinsEl);

        return impostoEl;
    }

    // -----------------------------------------------------------------
    // TOTAL — ICMSTot com campos obrigatórios
    // vProd e vNF vêm do domínio; demais são 0.00 para homologação
    // -----------------------------------------------------------------
    private Element buildTotal(Document doc, Total total) {
        Element totalEl   = doc.createElementNS(NS, "total");
        Element icmsTotEl = doc.createElementNS(NS, "ICMSTot");

        append(doc, icmsTotEl, "vBC",        "0.00");
        append(doc, icmsTotEl, "vICMS",      "0.00");
        append(doc, icmsTotEl, "vICMSDeson", "0.00");
        append(doc, icmsTotEl, "vFCP",       "0.00");
        append(doc, icmsTotEl, "vBCST",      "0.00");
        append(doc, icmsTotEl, "vST",        "0.00");
        append(doc, icmsTotEl, "vFCPST",     "0.00");
        append(doc, icmsTotEl, "vFCPSTRet",  "0.00");
        append(doc, icmsTotEl, "vProd",      total.getVProd());
        append(doc, icmsTotEl, "vFrete",     "0.00");
        append(doc, icmsTotEl, "vSeg",       "0.00");
        append(doc, icmsTotEl, "vDesc",      "0.00");
        append(doc, icmsTotEl, "vII",        "0.00");
        append(doc, icmsTotEl, "vIPI",       "0.00");
        append(doc, icmsTotEl, "vIPIDevol",  "0.00");
        append(doc, icmsTotEl, "vPIS",       "0.00");
        append(doc, icmsTotEl, "vCOFINS",    "0.00");
        append(doc, icmsTotEl, "vOutro",     "0.00");
        append(doc, icmsTotEl, "vNF",        total.getVNF());
        append(doc, icmsTotEl, "vTotTrib",   "0.00");

        totalEl.appendChild(icmsTotEl);
        return totalEl;
    }

    // -----------------------------------------------------------------
    // TRANSP — modFrete=9 (sem frete): único campo obrigatório
    // -----------------------------------------------------------------
    private Element buildTransp(Document doc) {
        Element transpEl = doc.createElementNS(NS, "transp");
        append(doc, transpEl, "modFrete", "9");
        return transpEl;
    }

    // -----------------------------------------------------------------
    // PAG — pagamento à vista, tPag=01 (dinheiro)
    // vPag derivado do vNF do total
    // -----------------------------------------------------------------
    private Element buildPag(Document doc, Total total) {
        Element pagEl    = doc.createElementNS(NS, "pag");
        Element detPagEl = doc.createElementNS(NS, "detPag");

        append(doc, detPagEl, "tPag", "01");
        append(doc, detPagEl, "vPag", total.getVNF());

        pagEl.appendChild(detPagEl);
        return pagEl;
    }

    // -----------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------
    private void append(Document doc, Element parent, String tag, String value) {
        if (value == null || value.isBlank()) return;

        Element el = doc.createElementNS(NS, tag);
        el.setTextContent(value);
        parent.appendChild(el);
    }
}
