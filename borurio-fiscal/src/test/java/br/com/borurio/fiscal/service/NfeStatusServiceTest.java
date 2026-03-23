package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.config.SefazProperties;
import br.com.borurio.fiscal.service.impl.CertificadoServiceImpl;
import br.com.borurio.fiscal.service.impl.NfeStatusServiceImpl;
import org.junit.jupiter.api.Test;

public class NfeStatusServiceTest {

    @Test
    public void testConsultarStatus() {

        CertificadoService certificadoService = new CertificadoServiceImpl();

        SefazProperties sefazProperties = new SefazProperties();
        sefazProperties.setStatus(
                "https://homologacao.nfe.fazenda.sp.gov.br/ws/NFeStatusServico4.asmx"
        );

        NfeStatusServiceImpl service =
                new NfeStatusServiceImpl(certificadoService, sefazProperties);

        String resposta = service.consultarStatusServico();

        System.out.println(resposta);

    }
}