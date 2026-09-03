package br.com.borurio.fiscal.danfe;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Gera danfe_visual_test.pdf em target/ para inspeção visual. Não é CI. */
public class DanfePdfVisualTest {

    @Test
    void gerarPdfParaInspecaoVisual() throws Exception {
        DanfePdfGenerator gen = new DanfePdfGenerator();
        DanfeData d = new DanfeData();

        // IDE
        d.tpAmb  = "2";
        d.natOp  = "VENDA DE MERCADORIA";
        d.dhEmi  = "2026-05-22T09:15:00-03:00";
        d.nNF    = "42";
        d.serie  = "1";
        d.cStat  = "225";
        d.chaveNfe = "35260500000000000191550010000000421000000017";

        // Emitente
        d.emitXNome   = "BORURIO INDUSTRIA E COMERCIO LTDA";
        d.emitXFant   = "BORURIO";
        d.emitCnpj    = "00000000000191";
        d.emitIe      = "111222333444";
        d.emitCrt     = "1";
        d.emitXLgr    = "AV. PAULISTA";
        d.emitNro     = "1500";
        d.emitXBairro = "BELA VISTA";
        d.emitXMun    = "SAO PAULO";
        d.emitUf      = "SP";
        d.emitCep     = "01310100";

        // Destinatário
        d.destXNome   = "CLIENTE IMPORTADOR LTDA";
        d.destCpfCnpj = "11222333000181";
        d.destIe      = "987654321";
        d.destXLgr    = "RUA DO COMERCIO";
        d.destNro     = "200";
        d.destXBairro = "CENTRO";
        d.destXMun    = "CAMPINAS";
        d.destUf      = "SP";
        d.destCep     = "13010001";

        // Itens
        DanfeData.Item i1 = new DanfeData.Item();
        i1.nItem = 1; i1.cProd = "P001"; i1.xProd = "NOTEBOOK PROFISSIONAL 15 POL";
        i1.ncm = "84713012"; i1.cfop = "5102"; i1.uCom = "UN";
        i1.qCom = "3.0000"; i1.vUnCom = "2500.00"; i1.vProd = "7500.00";
        i1.csosn = "400"; i1.orig = "0";

        DanfeData.Item i2 = new DanfeData.Item();
        i2.nItem = 2; i2.cProd = "P002"; i2.xProd = "MOUSE WIRELESS ERGONOMICO";
        i2.ncm = "84716060"; i2.cfop = "5102"; i2.uCom = "UN";
        i2.qCom = "5.0000"; i2.vUnCom = "89.90"; i2.vProd = "449.50";
        i2.csosn = "400"; i2.orig = "0";

        d.itens = List.of(i1, i2);

        // Bloco E — Cálculo do Imposto
        d.vProd  = "7949.50";
        d.vNF    = "8099.50";
        d.vBC    = "7949.50";
        d.vICMS  = "0.00";
        d.vBCST  = "0.00";
        d.vST    = "0.00";
        d.vIPI   = "0.00";
        d.vFrete = "120.00";
        d.vSeg   = "30.00";
        d.vDesc  = "0.00";
        d.vOutro = "0.00";

        // Bloco F — Transportador
        d.transpModFrete = "0";
        d.transpXNome    = "TRANSPORTADORA RAPIDA LTDA";
        d.transpCnpjCpf  = "55666777000188";
        d.transpIe       = "321321321";
        d.transpXEnder   = "RUA DAS ENTREGAS, 300";
        d.transpXMun     = "SAO PAULO";
        d.transpUf       = "SP";
        d.volQVol        = "2";
        d.volEsp         = "CAIXA";
        d.volPesoL       = "4.500";
        d.volPesoB       = "5.200";

        // Adicionais
        d.infCpl = "NF-e gerada em ambiente de homologacao. Sem valor fiscal.";

        byte[] pdf = gen.gerar(d);
        Path out = Path.of("target/danfe_blocos_ef_visual.pdf");
        Files.write(out, pdf);
        System.out.println("PDF gerado em: " + out.toAbsolutePath());
    }
}
