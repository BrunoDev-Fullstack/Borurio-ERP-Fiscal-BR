package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.config.SefazProperties;
import br.com.borurio.fiscal.service.impl.CertificadoServiceImpl;
import br.com.borurio.fiscal.service.impl.NfeStatusServiceImpl;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled("Integração — requer certificado A1 válido e conexão ativa com SEFAZ homologação")
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
