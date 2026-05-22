package br.com.borurio.fiscal.danfe;

import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Gera o PDF do DANFE a partir de DanfeData. Layout simplificado conforme NT 2019.001. */
@Component
public class DanfePdfGenerator {

    private static final Color CINZA_HEADER  = new Color(220, 220, 220);
    private static final Color PRETO         = Color.BLACK;

    private static final Font FONTE_LABEL  = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 5.5f, PRETO);
    private static final Font FONTE_VALOR  = FontFactory.getFont(FontFactory.HELVETICA, 7f, PRETO);
    private static final Font FONTE_TITULO = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8f, PRETO);
    private static final Font FONTE_DANFE  = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11f, PRETO);
    private static final Font FONTE_CHAVE  = FontFactory.getFont(FontFactory.HELVETICA, 6.5f, PRETO);

    // DecimalFormat não é thread-safe — criar instância por chamada (bean é singleton)
    private static DecimalFormat dfMoeda() {
        return new DecimalFormat("#,##0.00", new DecimalFormatSymbols(new Locale("pt", "BR")));
    }

    private static DecimalFormat dfQtde() {
        return new DecimalFormat("#,##0.####", new DecimalFormatSymbols(new Locale("pt", "BR")));
    }

    public byte[] gerar(DanfeData d) {
        try {
            Document doc = new Document(PageSize.A4, 15, 15, 15, 15);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfWriter writer = PdfWriter.getInstance(doc, out);

            // Watermark obrigatória para tpAmb=2 (homologação)
            if ("2".equals(d.tpAmb)) {
                writer.setPageEvent(new WatermarkEvent());
            }

            doc.open();
            PdfContentByte cb = writer.getDirectContent();

            addHeader(doc, d);
            addNatOpEDhEmi(doc, d);
            addChaveAcesso(doc, d, cb);
            addEmitenteDestinatario(doc, d);
            addItens(doc, d);
            addBlocoE(doc, d);
            addBlocoF(doc, d);
            addDadosAdicionais(doc, d);

            doc.close();
            return out.toByteArray();

        } catch (Exception e) {
            throw new IllegalStateException("Falha ao gerar PDF DANFE: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------

    private void addHeader(Document doc, DanfeData d) throws Exception {
        PdfPTable t = new PdfPTable(3);
        t.setWidthPercentage(100);
        t.setWidths(new float[]{35, 30, 35});
        t.setSpacingAfter(2);

        // Emitente
        PdfPCell cEmit = new PdfPCell();
        cEmit.setBorder(Rectangle.BOX);
        cEmit.setPadding(4);
        String nomeExibicao = d.emitXFant != null ? d.emitXFant : d.emitXNome;
        addLabeled(cEmit, "EMITENTE", nomeExibicao, FONTE_TITULO);
        if (d.emitXNome != null && d.emitXFant != null) {
            cEmit.addElement(new Phrase(d.emitXNome, FONTE_VALOR));
        }
        addLine(cEmit, "CNPJ: ", formatCnpj(d.emitCnpj));
        addLine(cEmit, "IE: ", d.emitIe);
        addLine(cEmit, "CRT: ", d.emitCrt);
        addEnderecoCell(cEmit, d.emitXLgr, d.emitNro, d.emitXBairro, d.emitXMun, d.emitUf, d.emitCep);
        t.addCell(cEmit);

        // DANFE (centro)
        PdfPCell cDanfe = new PdfPCell();
        cDanfe.setBorder(Rectangle.BOX);
        cDanfe.setPadding(4);
        cDanfe.setHorizontalAlignment(Element.ALIGN_CENTER);
        Paragraph pDanfe = new Paragraph("DANFE", FONTE_DANFE);
        pDanfe.setAlignment(Element.ALIGN_CENTER);
        cDanfe.addElement(pDanfe);
        Paragraph pSub = new Paragraph("Documento Auxiliar da\nNota Fiscal Eletrônica", FONTE_VALOR);
        pSub.setAlignment(Element.ALIGN_CENTER);
        cDanfe.addElement(pSub);
        cDanfe.addElement(new Phrase(" ", FONTE_VALOR));
        Phrase nfInfo = new Phrase();
        nfInfo.add(new Chunk("NF-e Nº ", FONTE_LABEL));
        nfInfo.add(new Chunk(safe(d.nNF), FONTE_TITULO));
        nfInfo.add(new Chunk("   Série ", FONTE_LABEL));
        nfInfo.add(new Chunk(safe(d.serie), FONTE_TITULO));
        cDanfe.addElement(nfInfo);
        cDanfe.addElement(new Phrase("Folha 1/1", FONTE_VALOR));
        t.addCell(cDanfe);

        // Protocolo — label condicional: só usa "AUTORIZAÇÃO" se realmente autorizado
        PdfPCell cProt = new PdfPCell();
        cProt.setBorder(Rectangle.BOX);
        cProt.setPadding(4);
        if ("100".equals(d.cStat) && d.nProt != null && !d.nProt.isBlank()) {
            addLabeled(cProt, "PROTOCOLO DE AUTORIZAÇÃO DE USO", null, null);
            cProt.addElement(new Phrase(safe(d.nProt), FONTE_VALOR));
            cProt.addElement(new Phrase(safe(d.dhRecbto), FONTE_VALOR));
        } else {
            String labelProt = "2".equals(d.tpAmb)
                    ? "RETORNO SEFAZ — HOMOLOGAÇÃO"
                    : "PROTOCOLO NÃO DISPONÍVEL";
            addLabeled(cProt, labelProt, null, null);
            if (d.cStat != null) {
                cProt.addElement(new Phrase("cStat: " + d.cStat, FONTE_VALOR));
            }
            if ("2".equals(d.tpAmb)) {
                cProt.addElement(new Phrase("Sem valor fiscal", FONTE_VALOR));
            }
        }
        t.addCell(cProt);

        doc.add(t);
    }

    private void addNatOpEDhEmi(Document doc, DanfeData d) throws Exception {
        PdfPTable t = new PdfPTable(2);
        t.setWidthPercentage(100);
        t.setWidths(new float[]{70, 30});
        t.setSpacingAfter(2);

        PdfPCell cNat = new PdfPCell();
        cNat.setBorder(Rectangle.BOX);
        cNat.setPadding(3);
        addLabeled(cNat, "NATUREZA DA OPERAÇÃO", d.natOp, FONTE_VALOR);
        t.addCell(cNat);

        PdfPCell cDt = new PdfPCell();
        cDt.setBorder(Rectangle.BOX);
        cDt.setPadding(3);
        addLabeled(cDt, "DATA DE EMISSÃO", formatDhEmi(d.dhEmi), FONTE_VALOR);
        t.addCell(cDt);

        doc.add(t);
    }

    private void addChaveAcesso(Document doc, DanfeData d, PdfContentByte cb) throws Exception {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        t.setSpacingAfter(2);

        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.BOX);
        c.setPadding(3);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        addLabeled(c, "CHAVE DE ACESSO", null, null);
        Paragraph pChave = new Paragraph(formatChave(d.chaveNfe), FONTE_CHAVE);
        pChave.setAlignment(Element.ALIGN_CENTER);
        c.addElement(pChave);

        if (d.chaveNfe != null && d.chaveNfe.length() == 44) {
            try {
                Barcode128 barcode = new Barcode128();
                barcode.setCode(d.chaveNfe);
                barcode.setBarHeight(22f);
                barcode.setX(1.0f);
                barcode.setFont(null); // sem texto abaixo do código de barras (já temos a chave em texto)
                Image img = barcode.createImageWithBarcode(cb, PRETO, PRETO);
                img.setAlignment(Image.ALIGN_CENTER);
                img.scaleToFit(500, 35);
                c.addElement(img);
            } catch (Exception ignored) {
                // barcode é best-effort; a chave em texto já cumpre o requisito legal
            }
        }

        t.addCell(c);
        doc.add(t);
    }

    private void addEmitenteDestinatario(Document doc, DanfeData d) throws Exception {
        PdfPTable t = new PdfPTable(2);
        t.setWidthPercentage(100);
        t.setWidths(new float[]{50, 50});
        t.setSpacingAfter(2);

        PdfPCell cE = new PdfPCell();
        cE.setBorder(Rectangle.BOX);
        cE.setPadding(3);
        addLabeled(cE, "EMITENTE", d.emitXNome, FONTE_TITULO);
        addLine(cE, "CNPJ: ", formatCnpj(d.emitCnpj));
        addLine(cE, "IE: ", d.emitIe);
        addEnderecoCell(cE, d.emitXLgr, d.emitNro, d.emitXBairro, d.emitXMun, d.emitUf, d.emitCep);
        t.addCell(cE);

        PdfPCell cD = new PdfPCell();
        cD.setBorder(Rectangle.BOX);
        cD.setPadding(3);
        addLabeled(cD, "DESTINATÁRIO / REMETENTE", d.destXNome, FONTE_TITULO);
        if (d.destCpfCnpj != null) {
            String lbl = d.destCpfCnpj.length() == 14 ? "CNPJ: " : "CPF: ";
            addLine(cD, lbl, formatDoc(d.destCpfCnpj));
        }
        addLine(cD, "IE: ", d.destIe);
        addEnderecoCell(cD, d.destXLgr, d.destNro, d.destXBairro, d.destXMun, d.destUf, d.destCep);
        t.addCell(cD);

        doc.add(t);
    }

    private void addItens(Document doc, DanfeData d) throws Exception {
        if (d.itens == null || d.itens.isEmpty()) return;

        PdfPTable t = new PdfPTable(10);
        t.setWidthPercentage(100);
        t.setWidths(new float[]{4, 8, 28, 9, 6, 5, 8, 9, 9, 8});
        t.setSpacingAfter(2);

        String[] colunas = {"N", "CÓDIGO", "DESCRIÇÃO", "NCM", "CFOP", "UN", "QTDE", "V.UNIT.", "V.TOTAL", "CSOSN"};
        for (String col : colunas) {
            PdfPCell h = new PdfPCell(new Phrase(col, FONTE_LABEL));
            h.setBackgroundColor(CINZA_HEADER);
            h.setPadding(2);
            h.setHorizontalAlignment(Element.ALIGN_CENTER);
            t.addCell(h);
        }

        for (DanfeData.Item item : d.itens) {
            t.addCell(cellAlinhado(String.valueOf(item.nItem), Element.ALIGN_CENTER));
            t.addCell(cellAlinhado(safe(item.cProd), Element.ALIGN_LEFT));
            t.addCell(cellAlinhado(safe(item.xProd), Element.ALIGN_LEFT));
            t.addCell(cellAlinhado(safe(item.ncm), Element.ALIGN_CENTER));
            t.addCell(cellAlinhado(safe(item.cfop), Element.ALIGN_CENTER));
            t.addCell(cellAlinhado(safe(item.uCom), Element.ALIGN_CENTER));
            t.addCell(cellAlinhado(formatDecimal(item.qCom,   dfQtde()),  Element.ALIGN_RIGHT));
            t.addCell(cellAlinhado(formatDecimal(item.vUnCom, dfMoeda()), Element.ALIGN_RIGHT));
            t.addCell(cellAlinhado(formatDecimal(item.vProd,  dfMoeda()), Element.ALIGN_RIGHT));
            t.addCell(cellAlinhado(safe(item.csosn), Element.ALIGN_CENTER));
        }

        doc.add(t);
    }

    private void addBlocoE(Document doc, DanfeData d) throws Exception {
        DecimalFormat df = dfMoeda();
        PdfPTable t = new PdfPTable(6);
        t.setWidthPercentage(100);
        t.setWidths(new float[]{17, 17, 17, 17, 17, 15});
        t.setSpacingAfter(2);

        PdfPCell hdr = new PdfPCell(new Phrase("CÁLCULO DO IMPOSTO", FONTE_LABEL));
        hdr.setColspan(6);
        hdr.setBackgroundColor(CINZA_HEADER);
        hdr.setPadding(2);
        t.addCell(hdr);

        addLabeledCell(t, "BC DO ICMS",        mz(d.vBC,    df));
        addLabeledCell(t, "VL. ICMS",          mz(d.vICMS,  df));
        addLabeledCell(t, "BC DO ST",          mz(d.vBCST,  df));
        addLabeledCell(t, "VL. ST",            mz(d.vST,    df));
        addLabeledCell(t, "VL. IPI",           mz(d.vIPI,   df));
        addLabeledCell(t, "VL. PRODUTOS",      mz(d.vProd,  df));

        addLabeledCell(t, "VL. FRETE",         mz(d.vFrete, df));
        addLabeledCell(t, "VL. SEGURO",        mz(d.vSeg,   df));
        addLabeledCell(t, "DESCONTO",          mz(d.vDesc,  df));
        addLabeledCell(t, "OUTRAS DESPESAS",   mz(d.vOutro, df));
        addLabeledCell(t, "VALOR TOTAL NF-e",  mz(d.vNF,    df));
        addLabeledCell(t, "AMBIENTE", "2".equals(d.tpAmb) ? "HOMOLOGAÇÃO" : "PRODUÇÃO");

        doc.add(t);
    }

    private void addBlocoF(Document doc, DanfeData d) throws Exception {
        DecimalFormat df = dfMoeda();
        PdfPTable t = new PdfPTable(4);
        t.setWidthPercentage(100);
        t.setWidths(new float[]{25, 35, 20, 20});
        t.setSpacingAfter(2);

        PdfPCell hdr = new PdfPCell(new Phrase("TRANSPORTADOR / VOLUMES TRANSPORTADOS", FONTE_LABEL));
        hdr.setColspan(4);
        hdr.setBackgroundColor(CINZA_HEADER);
        hdr.setPadding(2);
        t.addCell(hdr);

        String docLbl = d.transpCnpjCpf != null && d.transpCnpjCpf.length() == 14 ? "CNPJ" : "CPF/CNPJ";
        addLabeledCell(t, "FRETE POR CONTA",             modFreteLabel(d.transpModFrete));
        addLabeledCell(t, "TRANSPORTADOR / RAZ. SOCIAL", safe(d.transpXNome));
        addLabeledCell(t, docLbl,                        formatDoc(d.transpCnpjCpf));
        addLabeledCell(t, "INSCRIÇÃO ESTADUAL",          safe(d.transpIe));

        if (d.transpXEnder != null || d.transpXMun != null || d.transpUf != null) {
            addLabeledCell(t, "ENDEREÇO",  safe(d.transpXEnder));
            addLabeledCell(t, "MUNICÍPIO", safe(d.transpXMun));
            addLabeledCell(t, "UF",        safe(d.transpUf));
            addLabeledCell(t, "",          "");
        }

        if (d.volQVol != null || d.volEsp != null || d.volPesoL != null || d.volPesoB != null) {
            addLabeledCell(t, "QTDE. VOLUMES",     safe(d.volQVol));
            addLabeledCell(t, "ESPÉCIE",           safe(d.volEsp));
            addLabeledCell(t, "PESO LÍQUIDO (kg)", formatDecimal(d.volPesoL, df));
            addLabeledCell(t, "PESO BRUTO (kg)",   formatDecimal(d.volPesoB, df));
        }

        doc.add(t);
    }

    private void addDadosAdicionais(Document doc, DanfeData d) throws Exception {
        if (d.infCpl == null || d.infCpl.isBlank()) return;

        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);

        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.BOX);
        c.setPadding(3);
        addLabeled(c, "INFORMAÇÕES COMPLEMENTARES", d.infCpl, FONTE_VALOR);
        t.addCell(c);

        doc.add(t);
    }

    // -------------------------------------------------------------------------

    private void addLabeled(PdfPCell cell, String label, String value, Font valueFont) {
        cell.addElement(new Phrase(label, FONTE_LABEL));
        if (value != null && !value.isBlank()) {
            cell.addElement(new Phrase(value, valueFont != null ? valueFont : FONTE_VALOR));
        }
    }

    private void addLine(PdfPCell cell, String label, String value) {
        if (value == null || value.isBlank()) return;
        Phrase p = new Phrase();
        p.add(new Chunk(label, FONTE_LABEL));
        p.add(new Chunk(value, FONTE_VALOR));
        cell.addElement(p);
    }

    private void addEnderecoCell(PdfPCell cell, String xLgr, String nro, String xBairro,
                                  String xMun, String uf, String cep) {
        if (xLgr != null) {
            String end = xLgr + (nro != null ? ", " + nro : "");
            cell.addElement(new Phrase(end, FONTE_VALOR));
        }
        if (xBairro != null) cell.addElement(new Phrase(xBairro, FONTE_VALOR));
        if (xMun != null) {
            String mun = xMun + (uf != null ? " - " + uf : "");
            cell.addElement(new Phrase(mun, FONTE_VALOR));
        }
        if (cep != null) {
            Phrase p = new Phrase();
            p.add(new Chunk("CEP: ", FONTE_LABEL));
            p.add(new Chunk(formatCep(cep), FONTE_VALOR));
            cell.addElement(p);
        }
    }

    private void addLabeledCell(PdfPTable table, String label, String value) {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.BOX);
        c.setPadding(3);
        addLabeled(c, label, value, FONTE_TITULO);
        table.addCell(c);
    }

    private PdfPCell cellAlinhado(String text, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, FONTE_VALOR));
        c.setPadding(2);
        c.setHorizontalAlignment(align);
        return c;
    }

    // -------------------------------------------------------------------------

    private String mz(String val, DecimalFormat df) {
        if (val == null || val.isBlank()) return df.format(0.0);
        try {
            return df.format(Double.parseDouble(val.replace(",", ".")));
        } catch (Exception ignored) {
            return df.format(0.0);
        }
    }

    private String modFreteLabel(String mf) {
        if (mf == null) return "";
        return switch (mf) {
            case "0" -> "0 - Emitente (CIF)";
            case "1" -> "1 - Destinatário (FOB)";
            case "2" -> "2 - Terceiro";
            case "3" -> "3 - Próprio/Emit.";
            case "4" -> "4 - Próprio/Dest.";
            case "9" -> "9 - Sem Frete";
            default  -> mf;
        };
    }

    private String safe(String s) {
        return s != null ? s : "";
    }

    private String formatCnpj(String cnpj) {
        if (cnpj == null || cnpj.length() != 14) return safe(cnpj);
        return cnpj.substring(0, 2) + "." + cnpj.substring(2, 5) + "."
                + cnpj.substring(5, 8) + "/" + cnpj.substring(8, 12) + "-" + cnpj.substring(12);
    }

    private String formatDoc(String doc) {
        if (doc == null) return "";
        if (doc.length() == 14) return formatCnpj(doc);
        if (doc.length() == 11) {
            return doc.substring(0, 3) + "." + doc.substring(3, 6) + "."
                    + doc.substring(6, 9) + "-" + doc.substring(9);
        }
        return doc;
    }

    private String formatCep(String cep) {
        if (cep == null || cep.length() != 8) return safe(cep);
        return cep.substring(0, 5) + "-" + cep.substring(5);
    }

    private String formatChave(String chave) {
        if (chave == null || chave.length() != 44) return safe(chave);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 44; i += 4) {
            if (i > 0) sb.append(' ');
            sb.append(chave, i, Math.min(i + 4, 44));
        }
        return sb.toString();
    }

    private String formatDhEmi(String dhEmi) {
        if (dhEmi == null || dhEmi.length() < 16) return safe(dhEmi);
        try {
            String date = dhEmi.substring(0, 10);
            String time = dhEmi.substring(11, 16);
            String[] p = date.split("-");
            return p[2] + "/" + p[1] + "/" + p[0] + " " + time;
        } catch (Exception ignored) {
            return dhEmi;
        }
    }

    private String formatDecimal(String val, DecimalFormat df) {
        if (val == null || val.isBlank()) return "";
        try {
            return df.format(Double.parseDouble(val.replace(",", ".")));
        } catch (Exception ignored) {
            return val;
        }
    }

    // -------------------------------------------------------------------------

    private static class WatermarkEvent extends PdfPageEventHelper {
        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            try {
                PdfContentByte cb = writer.getDirectContentUnder();
                cb.saveState();
                BaseFont bf = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.CP1252, false);
                cb.setColorFill(new Color(210, 210, 210));
                cb.beginText();
                cb.setFontAndSize(bf, 52);
                cb.showTextAligned(
                        PdfContentByte.ALIGN_CENTER,
                        "SEM VALOR FISCAL",
                        document.getPageSize().getWidth() / 2f,
                        document.getPageSize().getHeight() / 2f,
                        45
                );
                cb.endText();
                cb.restoreState();
            } catch (Exception ignored) {}
        }
    }
}
