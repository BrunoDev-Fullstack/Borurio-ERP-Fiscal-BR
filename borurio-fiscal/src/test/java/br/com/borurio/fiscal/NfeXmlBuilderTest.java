package br.com.borurio.fiscal;

import br.com.borurio.fiscal.builder.NfeXmlBuilder;
import br.com.borurio.fiscal.domain.nfe.*;

import org.junit.jupiter.api.Test;

import java.util.List;

public class NfeXmlBuilderTest {

    @Test
    public void deveGerarXmlNFe() {

        // ======================
        // MONTAR OBJETO
        // ======================

        Ide ide = new Ide();
        ide.setCUF("35");
        ide.setNatOp("VENDA");
        ide.setSerie("1");
        ide.setNNF("1");
        ide.setDhEmi("2026-03-25T11:00:00-03:00");
        ide.setTpNF("1");
        ide.setIdDest("1");
        ide.setTpAmb("2");
        ide.setFinNFe("1");

        Emit emit = new Emit();
        emit.setCnpj("54393421000159");
        emit.setXNome("BORURIO ERP TESTE");
        emit.setIe("123456789");

        Dest dest = new Dest();
        dest.setCpfCnpj("12345678901");
        dest.setXNome("CLIENTE TESTE");

        Produto prod = new Produto();
        prod.setCProd("1");
        prod.setXProd("PRODUTO TESTE");
        prod.setNCM("61091000");
        prod.setCFOP("5102");
        prod.setUCom("UN");
        prod.setQCom("1.00");
        prod.setVUnCom("10.00");
        prod.setVProd("10.00");

        Det det = new Det();
        det.setNItem(1);
        det.setProd(prod);

        Total total = new Total();
        total.setVProd("10.00");
        total.setVNF("10.00");

        InfNFe inf = new InfNFe();
        inf.setId("NFe12345678901234567890123456789012345678901234");
        inf.setIde(ide);
        inf.setEmit(emit);
        inf.setDest(dest);
        inf.setDet(List.of(det));
        inf.setTotal(total);

        NFe nfe = new NFe();
        nfe.setInfNFe(inf);

        // ======================
        // GERAR XML
        // ======================

        NfeXmlBuilder builder = new NfeXmlBuilder();
        String xml = builder.build(nfe);

        System.out.println(xml);
    }
}