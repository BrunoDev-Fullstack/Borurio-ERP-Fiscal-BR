package br.com.borurio.fiscal.service.impl;

import br.com.borurio.fiscal.config.EmitenteProperties;
import br.com.borurio.fiscal.config.SefazProperties;
import br.com.borurio.fiscal.dto.NfeCancelamentoRequest;
import br.com.borurio.fiscal.dto.NfeEventoPreparado;
import br.com.borurio.fiscal.entity.NfeLog;
import br.com.borurio.fiscal.exception.SefazTransmissaoIncertaException;
import br.com.borurio.fiscal.service.AssinaturaXmlService;
import br.com.borurio.fiscal.service.CertificadoContexto;
import br.com.borurio.fiscal.service.CertificadoService;
import br.com.borurio.fiscal.service.NfeLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import javax.net.ssl.SSLContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Gate de cancelamento (12-08-2026) — achado de banca: o novo NfeCancelamentoOrquestradorService
 * chama prepararEvento()+transmitirEvento() diretamente, e transmitirEvento() precisava ser o
 * ÚNICO ponto de gravação de NfeLog (nunca o facade legado cancelar() por cima, nunca zero).
 *
 * Usa um endpoint HTTPS local inalcançável (127.0.0.1:1 — porta reservada, ninguém escuta) para
 * exercitar o transporte real de NfeCancelamentoServiceImpl.enviarSoap sem qualquer chamada real
 * à SEFAZ: a falha de conexão é local e imediata, equivalente a qualquer outra falha de
 * transporte para fins deste teste (registrarLog + SefazTransmissaoIncertaException).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NfeCancelamentoServiceImpl — NfeLog único ponto de gravação")
class NfeCancelamentoServiceImplTest {

    @Mock AssinaturaXmlService assinaturaXmlService;
    @Mock CertificadoService certificadoService;
    @Mock NfeLogService nfeLogService;

    NfeCancelamentoServiceImpl service;
    CertificadoContexto certContexto;

    private static final String CHAVE = "35260500000000000191550010000000011000000013";

    @BeforeEach
    void setUp() throws Exception {
        SefazProperties sefazProperties = new SefazProperties();
        sefazProperties.setRecepcaoEvento("https://127.0.0.1:1/nfeRecepcaoEvento4");

        EmitenteProperties emitente = new EmitenteProperties();
        emitente.setCnpj("22418179000134");
        emitente.setUf("SP");

        service = new NfeCancelamentoServiceImpl(assinaturaXmlService, certificadoService, sefazProperties,
                nfeLogService, emitente);
        // @Value nao e injetado fora do Spring context -- setar manualmente os campos que a
        // aplicacao real preenche via field injection (tpAmb/timezoneFiscal).
        ReflectionTestUtils.setField(service, "tpAmb", 2);
        ReflectionTestUtils.setField(service, "timezoneFiscal", "America/Sao_Paulo");

        // lenient: o teste de registrarLog() não passa por prepararEvento(), então não usa este
        // stub -- não é stubbing incorreto, só não é exercitado por todo teste da classe.
        lenient().when(assinaturaXmlService.assinarEvento(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        certContexto = new CertificadoContexto(8L, null, null, SSLContext.getDefault());
    }

    private NfeCancelamentoRequest request() {
        NfeCancelamentoRequest req = new NfeCancelamentoRequest();
        req.setChaveNfe(CHAVE);
        req.setNProtocolo("135260000000001");
        req.setJustificativa("Cliente desistiu da compra");
        return req;
    }

    @Test
    @DisplayName("transmitirEvento: falha de transporte — registra NfeLog(ERROR) exatamente uma vez e propaga SefazTransmissaoIncertaException")
    void transmitirEvento_falhaTransporte_registraLogUmaVez() throws Exception {
        NfeEventoPreparado preparado = service.prepararEvento(request(), "22418179000134", "SP", certContexto, 1);

        assertThrows(SefazTransmissaoIncertaException.class, () -> service.transmitirEvento(preparado, certContexto));

        verify(nfeLogService, times(1)).salvar(any());
        ArgumentCaptor<NfeLog> captor = ArgumentCaptor.forClass(NfeLog.class);
        verify(nfeLogService).salvar(captor.capture());
        assertEquals("ERROR", captor.getValue().getStatus());
        assertEquals("CANCELAMENTO", captor.getValue().getTipoEvento());
        assertEquals(CHAVE, captor.getValue().getChaveNfe());
        assertNotNull(captor.getValue().getXmlEnvio(), "XML assinado enviado precisa ficar na evidência mesmo em falha");
    }

    @Test
    @DisplayName("cancelar() legado: delega para prepararEvento+transmitirEvento sem gravar log próprio — nunca duplica")
    void cancelarLegado_naoDuplicaLog() throws Exception {
        assertThrows(SefazTransmissaoIncertaException.class,
                () -> service.cancelar(request(), "22418179000134", "SP", certContexto));

        // Se cancelar() ainda gravasse log por cima de transmitirEvento(), este número seria 2.
        verify(nfeLogService, times(1)).salvar(any());
    }

    @Test
    @DisplayName("REJEITADO -> corrigido -> nova transmissão da MESMA identidade (nSeq=1) — duas entradas de NfeLog distintas, histórico nunca perdido")
    void duasTentativasMesmaIdentidade_duasEvidenciasDistintas() throws Exception {
        NfeCancelamentoRequest tentativa1 = request();
        tentativa1.setJustificativa("Primeira tentativa, motivo genérico");
        NfeEventoPreparado preparado1 = service.prepararEvento(tentativa1, "22418179000134", "SP", certContexto, 1);
        assertThrows(SefazTransmissaoIncertaException.class, () -> service.transmitirEvento(preparado1, certContexto));

        // Reabertura da MESMA identidade (nSeqEvento=1) com justificativa corrigida — mesmo
        // padrão de NfeEventoService.decidirComExistente ao reabrir um REJEITADO.
        NfeCancelamentoRequest tentativa2 = request();
        tentativa2.setJustificativa("Segunda tentativa, motivo corrigido e detalhado");
        NfeEventoPreparado preparado2 = service.prepararEvento(tentativa2, "22418179000134", "SP", certContexto, 1);
        assertThrows(SefazTransmissaoIncertaException.class, () -> service.transmitirEvento(preparado2, certContexto));

        assertEquals(preparado1.idEvento(), preparado2.idEvento(), "mesma identidade fiscal (nSeqEvento=1) nas duas tentativas");

        verify(nfeLogService, times(2)).salvar(any());
        ArgumentCaptor<NfeLog> captor = ArgumentCaptor.forClass(NfeLog.class);
        verify(nfeLogService, times(2)).salvar(captor.capture());
        assertEquals(2, captor.getAllValues().size());
        // Cada entrada preserva o XML assinado EXATO daquela tentativa -- nunca a mesma instância
        // reaproveitada, nunca uma tentativa apagando a evidência da outra.
        assertNotEquals(captor.getAllValues().get(0).getXmlEnvio(), captor.getAllValues().get(1).getXmlEnvio(),
                "cada tentativa tem seu próprio xJust/dhEvento no XML assinado, logo XMLs distintos");
    }

    // -------------------------------------------------------------------------
    // Correção 4 — dhEvento timezone-aware, independente do fuso padrão da JVM
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("dhEvento: usa a ZoneId fiscal configurada explicitamente, nunca o fuso padrão da JVM/container")
    void dhEvento_independeDoFusoPadraoDaJvm() throws Exception {
        java.util.TimeZone original = java.util.TimeZone.getDefault();
        try {
            // Simula container/JVM rodando em UTC (cenário real que motivou o achado de banca:
            // "LocalDateTime.now() + \"-03:00\"" rotulava a hora UTC como se fosse Brasília).
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));

            NfeEventoPreparado preparado = service.prepararEvento(request(), "22418179000134", "SP", certContexto, 1);

            assertTrue(preparado.dhEvento().matches(".*-03:00$"),
                    "dhEvento precisa terminar em -03:00 (America/Sao_Paulo) mesmo com a JVM em UTC, dhEvento=" + preparado.dhEvento());

            // O instante representado precisa ser o horário de Brasília REAL no momento da
            // chamada, nao a hora UTC rotulada como se fosse -03:00 (o bug original).
            java.time.ZonedDateTime esperado = java.time.ZonedDateTime.now(java.time.ZoneId.of("America/Sao_Paulo"));
            java.time.ZonedDateTime obtido = java.time.ZonedDateTime.parse(preparado.dhEvento());
            assertTrue(Math.abs(java.time.Duration.between(esperado, obtido).getSeconds()) < 5,
                    "dhEvento precisa refletir o instante real de Brasília, não a hora UTC da JVM rotulada incorretamente");
        } finally {
            java.util.TimeZone.setDefault(original);
        }
    }

    @Test
    @DisplayName("dhEvento: também correto com a JVM em America/Sao_Paulo (caso comum hoje)")
    void dhEvento_correntoComJvmNoFusoFiscal() throws Exception {
        java.util.TimeZone original = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Sao_Paulo"));

            NfeEventoPreparado preparado = service.prepararEvento(request(), "22418179000134", "SP", certContexto, 1);

            assertTrue(preparado.dhEvento().matches(".*-03:00$"));
            assertDoesNotThrow(() -> java.time.ZonedDateTime.parse(preparado.dhEvento()),
                    "dhEvento precisa ser um ZonedDateTime ISO válido, com TZD");
        } finally {
            java.util.TimeZone.setDefault(original);
        }
    }

    // -------------------------------------------------------------------------
    // Correção final de banca (12-08-2026, ponto 1) — falha de auditoria (NfeLog) nunca pode
    // reclassificar uma resposta SEFAZ conhecida como transmissão incerta.
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("registrarLog: falha de persistência do NfeLog é absorvida internamente — nunca escapa para o chamador")
    void registrarLog_falhaDePersistencia_nuncaEscapaParaOChamador() throws Exception {
        // Prova direta do método real (via reflexão, já que é privado): se
        // nfeLogService.salvar(...) lançar, registrarLog() precisa engolir a exceção -- é essa
        // garantia que impede transmitirEvento() de recategorizar uma resposta SEFAZ válida
        // (enviarSoap já retornou) como SefazTransmissaoIncertaException só porque a AUDITORIA
        // falhou. O try/catch interno já existe no código de produção (linhas 236-248 de
        // NfeCancelamentoServiceImpl) -- este teste comprova que ele funciona de verdade, não
        // apenas por leitura.
        doThrow(new RuntimeException("Falha simulada de banco no NfeLog")).when(nfeLogService).salvar(any());

        java.lang.reflect.Method registrarLog = NfeCancelamentoServiceImpl.class.getDeclaredMethod(
                "registrarLog", String.class, String.class, String.class, String.class, String.class, String.class);
        registrarLog.setAccessible(true);

        assertDoesNotThrow(() -> registrarLog.invoke(service, CHAVE, "22418179000134", "SUCCESS",
                        "Cancelamento transmitido", "<xml-envio/>", "<xml-retorno-sefaz-valido/>"),
                "uma falha de persistência do NfeLog nunca pode escapar de registrarLog() -- se escapasse, "
                        + "transmitirEvento() a capturaria no catch de transporte e a transformaria "
                        + "erroneamente em SefazTransmissaoIncertaException, descartando uma resposta SEFAZ real");

        verify(nfeLogService, times(1)).salvar(any());
    }
}
