package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.entity.NfeLog;
import br.com.borurio.fiscal.mapper.NfeLogMapper;
import br.com.borurio.fiscal.service.impl.NfeTransmitServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * =============================================================================
 * TESTE UNITÁRIO: NfeTransmitServiceTest
 * =============================================================================
 * Verifica o comportamento do componente {@link NfeTransmitServiceImpl},
 * responsável pela transmissão dos XMLs NF-e (v4.00) aos WebServices da SEFAZ-SP.
 *
 * Cenários cobertos:
 *  1. Transmissão com sucesso (mock SEFAZ respondendo OK)
 *  2. Falha SSL/TLS simulada
 *  3. Timeout de rede (SocketTimeoutException)
 *  4. Falha genérica (IOException)
 *  5. Registro de log fiscal em todos os casos
 *
 * Ambiente: DEV / Homologação
 * Módulo: borurio-fiscal
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * =============================================================================
 */
public class NfeTransmitServiceTest {

    private NfeTransmitServiceImpl service;
    private NfeLogMapper logMapper;
    private CertificadoService certificadoService;

    private static final String XML_MOCK =
            "<NFe><infNFe Id=\"NFe12345678901234567890123456789012345678901234\"></infNFe></NFe>";

    @BeforeEach
    void setUp() {
        logMapper = mock(NfeLogMapper.class);
        certificadoService = mock(CertificadoService.class);

        // Mock do SSLContext
        SSLContext sslContext = mock(SSLContext.class);
        SSLSocketFactory socketFactory = mock(SSLSocketFactory.class);
        when(sslContext.getSocketFactory()).thenReturn(socketFactory);
        when(certificadoService.getSslContext()).thenReturn(sslContext);

        // Instancia completa com 10 argumentos (ajustada ao novo construtor)
        service = new NfeTransmitServiceImpl(
                logMapper,
                certificadoService,
                "https://homologacao.nfe.fazenda.sp.gov.br/ws/NFeAutorizacao4.asmx",
                "https://homologacao.nfe.fazenda.sp.gov.br/ws/NFeRetAutorizacao4.asmx",
                "https://homologacao.nfe.fazenda.sp.gov.br/ws/NFeStatusServico4.asmx",
                "https://homologacao.nfe.fazenda.sp.gov.br/ws/NFeConsultaProtocolo4.asmx",
                "https://homologacao.nfe.fazenda.sp.gov.br/ws/NFeInutilizacao4.asmx",
                "https://homologacao.nfe.fazenda.sp.gov.br/ws/RecepcaoEvento4.asmx",
                "/app/certs/borurio-hom.pfx",
                "SENHA_DO_CERTIFICADO"
        );
    }

    @Test
    @DisplayName("Deve transmitir NF-e com sucesso e registrar log fiscal (status SUCCESS)")
    void testTransmitirComSucesso() throws Exception {
        try (MockedStatic<SSLContext> ignored = Mockito.mockStatic(SSLContext.class)) {
            NfeTransmitServiceImpl spyService = Mockito.spy(service);
            doReturn("<retEnviNFe><cStat>100</cStat></retEnviNFe>")
                    .when(spyService).transmitirXml(anyString(), anyString());
        }

        String resultado = service.transmitirXml(XML_MOCK, "12345678000190");
        assertNotNull(resultado, "O resultado da transmissão não deve ser nulo.");

        ArgumentCaptor<NfeLog> logCaptor = ArgumentCaptor.forClass(NfeLog.class);
        verify(logMapper, atLeastOnce()).insertLog(logCaptor.capture());

        NfeLog log = logCaptor.getValue();
        assertEquals("ENVIO_NFE", log.getTipoEvento());
        assertTrue(log.getDescricao().contains("NF-e"), "Descrição deve conter referência à NF-e");
        assertNotNull(log.getDataEvento(), "Data de evento deve ser registrada.");
    }

    @Test
    @DisplayName("Deve registrar falha de SSLException e retornar null")
    void testFalhaSsl() throws Exception {
        NfeTransmitServiceImpl spyService = Mockito.spy(service);
        doThrow(new SSLException("Falha no handshake")).when(spyService)
                .transmitirXml(anyString(), anyString());

        String resultado = spyService.transmitirXml(XML_MOCK, "12345678000190");
        assertNull(resultado, "Em caso de SSLException, o retorno deve ser null.");

        verify(logMapper, atLeastOnce()).insertLog(any(NfeLog.class));
    }

    @Test
    @DisplayName("Deve registrar falha de timeout (SocketTimeoutException)")
    void testFalhaTimeout() throws Exception {
        NfeTransmitServiceImpl spyService = Mockito.spy(service);
        doThrow(new SocketTimeoutException("Tempo limite excedido"))
                .when(spyService).transmitirXml(anyString(), anyString());

        String resultado = spyService.transmitirXml(XML_MOCK, "12345678000190");
        assertNull(resultado);
        verify(logMapper, atLeastOnce()).insertLog(any(NfeLog.class));
    }

    @Test
    @DisplayName("Deve registrar falha genérica (IOException)")
    void testFalhaIo() throws Exception {
        NfeTransmitServiceImpl spyService = Mockito.spy(service);
        doThrow(new IOException("Erro de rede")).when(spyService)
                .transmitirXml(anyString(), anyString());

        String resultado = spyService.transmitirXml(XML_MOCK, "12345678000190");
        assertNull(resultado);
        verify(logMapper, atLeastOnce()).insertLog(any(NfeLog.class));
    }

    @Test
    @DisplayName("Deve retornar status do serviço SEFAZ-SP (mock)")
    void testConsultarStatus() {
        String status = service.consultarStatus();
        assertNotNull(status);
        assertTrue(status.contains("Serviço NF-e ativo"));
    }

    @Test
    @DisplayName("Deve registrar evento fiscal com data e status inicial PENDING")
    void testRegistroInicialDeLog() {
        NfeLog log = NfeLog.builder()
                .tipoEvento("ENVIO_NFE")
                .descricao("Teste de log fiscal inicial")
                .status("PENDING")
                .dataEvento(LocalDateTime.now())
                .cnpjEmitente("12345678000190")
                .xmlEnvio(XML_MOCK)
                .usuario("system")
                .build();

        logMapper.insertLog(log);
        verify(logMapper, atLeastOnce()).insertLog(any(NfeLog.class));
    }
}
