package br.com.borurio.web.service;

import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.fiscal.config.SefazReconciliacaoProperties;
import br.com.borurio.fiscal.dto.ListaEventosRetorno;
import br.com.borurio.fiscal.dto.NfeCceEventoPreparado;
import br.com.borurio.fiscal.dto.NfeCceRequest;
import br.com.borurio.fiscal.dto.NfeConsultaSituacaoRetorno;
import br.com.borurio.fiscal.dto.NfeEventoRetorno;
import br.com.borurio.fiscal.entity.NfeEvento;
import br.com.borurio.fiscal.entity.NfeEventoIdempotencia;
import br.com.borurio.fiscal.entity.NfeEventoSequencia;
import br.com.borurio.fiscal.exception.SefazTransmissaoIncertaException;
import br.com.borurio.fiscal.mapper.NfeEventoIdempotenciaMapper;
import br.com.borurio.fiscal.mapper.NfeEventoMapper;
import br.com.borurio.fiscal.mapper.NfeEventoSequenciaMapper;
import br.com.borurio.fiscal.service.CertificadoContexto;
import br.com.borurio.fiscal.service.NfeCceClassificador;
import br.com.borurio.fiscal.service.NfeCceService;
import br.com.borurio.fiscal.service.NfeConsultaSituacaoService;
import br.com.borurio.fiscal.service.NfeEventoRetornoParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Gate CC-e (evento 110110, 12-08-2026) — cobre a matriz de cStat, bootstrap de sequência
 * histórica, reserva/reabertura atômica, idempotência de operação (replay em todos os estados,
 * incluindo o caso divergente) e reconciliação com validação de conteúdo. Mapeadores mockados
 * (a exactly-once contra MySQL real é coberta à parte, em NfeCceGateConcorrenciaRealMySqlIT);
 * usa o {@link NfeCceClassificador} REAL (pequeno, puro) e o mesmo padrão de
 * {@code PlatformTransactionManager} mockado de OmsAuthorizationAdminServiceTest para permitir
 * que {@code TransactionTemplate.execute} invoque o callback de verdade.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NfeCceOrquestradorService — sequência, idempotência, matriz de cStat, reconciliação")
class NfeCceOrquestradorServiceTest {

    @Mock NfeEventoMapper eventoMapper;
    @Mock NfeEventoSequenciaMapper sequenciaMapper;
    @Mock NfeEventoIdempotenciaMapper idempotenciaMapper;
    @Mock NfeCceService cceService;
    @Mock NfeEventoRetornoParser eventoRetornoParser;
    @Mock NfeConsultaSituacaoService consultaSituacaoService;
    @Mock PlatformTransactionManager transactionManager;

    NfeCceOrquestradorService orquestrador;

    private static final Long PEDIDO_ID = 50L;
    private static final Long EMPRESA_ID = 10L;
    private static final String CHAVE = "35260722418179000134550010000000018147502559";
    private static final String CNPJ = "22418179000134";
    private static final String UF = "SP";
    private static final String IDEM_KEY = "11111111-1111-1111-1111-111111111111";
    private static final String CORRECAO = "Correção do endereço do destinatário na NF-e";
    private static final String TIPO = NfeEvento.TiposEvento.CCE;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        orquestrador = new NfeCceOrquestradorService(eventoMapper, sequenciaMapper, idempotenciaMapper,
                cceService, eventoRetornoParser, new NfeCceClassificador(), consultaSituacaoService,
                new SefazReconciliacaoProperties(), transactionManager);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String corrigir() throws Exception {
        return corrigir(IDEM_KEY);
    }

    private String corrigir(String idempotencyKey) throws Exception {
        return orquestrador.corrigir(PEDIDO_ID, EMPRESA_ID, CNPJ, UF, CHAVE, CORRECAO, idempotencyKey,
                mock(CertificadoContexto.class));
    }

    private NfeEventoSequencia sequencia(int ultimoNSeq, Long eventoAtivoId) {
        NfeEventoSequencia s = new NfeEventoSequencia();
        s.setChaveNfe(CHAVE);
        s.setTipoEvento(TIPO);
        s.setUltimoNSeqRegistrado(ultimoNSeq);
        s.setEventoAtivoId(eventoAtivoId);
        return s;
    }

    private NfeEvento evento(Long id, String estado, int nSeq) {
        NfeEvento e = new NfeEvento();
        e.setId(id);
        e.setPedidoId(PEDIDO_ID);
        e.setEmpresaId(EMPRESA_ID);
        e.setCnpjEmitente(CNPJ);
        e.setChaveNfe(CHAVE);
        e.setTipoEvento(TIPO);
        e.setNSeqEvento(nSeq);
        e.setEstado(estado);
        return e;
    }

    private NfeEventoIdempotencia idempotencia(Long id, String estado, Integer cStat, String xMotivo, String nProt) {
        NfeEventoIdempotencia idem = new NfeEventoIdempotencia();
        idem.setId(id);
        idem.setIdempotencyKey(IDEM_KEY);
        idem.setEmpresaId(EMPRESA_ID);
        idem.setPedidoId(PEDIDO_ID);
        idem.setTipoEvento(TIPO);
        idem.setConteudoHash(hash());
        idem.setNfeEventoId(1L);
        idem.setEstadoResultado(estado);
        idem.setCstatResultado(cStat);
        idem.setXmotivoResultado(xMotivo);
        idem.setNprotResultado(nProt);
        return idem;
    }

    private String hash() {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] h = digest.digest((CHAVE + "|" + CORRECAO).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(h);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Simula o gate já bootstrapped localmente (fast path, sem chamada de rede). */
    private void stubGateExistente(int ultimoNSeq, Long eventoAtivoId) {
        when(sequenciaMapper.buscarSemLock(CHAVE, TIPO)).thenReturn(sequencia(ultimoNSeq, eventoAtivoId));
        when(sequenciaMapper.buscarParaAtualizar(CHAVE, TIPO)).thenReturn(sequencia(ultimoNSeq, eventoAtivoId));
    }

    private void stubSemIdempotenciaExistente() {
        when(idempotenciaMapper.buscarPorIdempotencyKey(IDEM_KEY)).thenReturn(null);
        when(idempotenciaMapper.buscarPorIdempotencyKeyParaAtualizar(IDEM_KEY)).thenReturn(null);
    }

    /** Simula o MyBatis @Options(useGeneratedKeys=true) preenchendo o id gerado no próprio objeto. */
    private void stubInsercoesComId(long eventoId, long idemId) {
        doAnswer(inv -> {
            NfeEvento e = inv.getArgument(0);
            e.setId(eventoId);
            return 1;
        }).when(eventoMapper).inserirPreparado(any());
        doAnswer(inv -> {
            NfeEventoIdempotencia i = inv.getArgument(0);
            i.setId(idemId);
            return 1;
        }).when(idempotenciaMapper).inserir(any());
    }

    private NfeCceEventoPreparado preparadoSoap(int nSeq) {
        return new NfeCceEventoPreparado("ID" + TIPO + CHAVE + String.format("%02d", nSeq),
                "2026-08-12T10:00:00-03:00", "<evento-assinado/>", "hash-xyz", CHAVE, CNPJ, nSeq);
    }

    // -------------------------------------------------------------------------
    // Matriz de cStat — reserva nova + transmissão direta
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("135 — REGISTRADO: sequência avança, gate libera")
    void cStat135_registrado() throws Exception {
        stubGateExistente(0, null);
        stubSemIdempotenciaExistente();
        stubInsercoesComId(1L, 100L);
        when(cceService.prepararEvento(any(), eq(CNPJ), eq(UF), any(), eq(1))).thenReturn(preparadoSoap(1));
        when(eventoMapper.marcarTransmitido(eq(1L), any(), any())).thenReturn(1);
        when(cceService.transmitirEvento(any(), any())).thenReturn("<retEnvEvento/>");

        NfeEventoRetorno retorno = new NfeEventoRetorno();
        retorno.setCStatLote(128);
        retorno.setInfEventoPresente(true);
        retorno.setCStatEvento(135);
        retorno.setXMotivoEvento("Evento registrado e vinculado a NF-e");
        retorno.setNProtEvento("135260000009999");
        when(eventoRetornoParser.parse("<retEnvEvento/>")).thenReturn(retorno);
        when(eventoMapper.buscarPorIdParaAtualizar(1L)).thenReturn(evento(1L, NfeEvento.Estados.TRANSMITIDO, 1));

        String resultado = corrigir();

        assertTrue(resultado.contains("135260000009999"));
        verify(sequenciaMapper).atualizarUltimoNSeq(CHAVE, TIPO, 1);
        verify(sequenciaMapper).liberarGate(CHAVE, TIPO);
        ArgumentCaptor<NfeEvento> captor = ArgumentCaptor.forClass(NfeEvento.class);
        verify(eventoMapper).atualizarResultado(captor.capture());
        assertEquals(NfeEvento.Estados.REGISTRADO, captor.getValue().getEstado());
    }

    @Test
    @DisplayName("136 — evento registrado mas não vinculado — PENDENTE_CONFIRMACAO, retryable, gate mantido ocupado")
    void cStat136_pendenteConfirmacao() throws Exception {
        stubGateExistente(0, null);
        stubSemIdempotenciaExistente();
        stubInsercoesComId(1L, 100L);
        when(cceService.prepararEvento(any(), eq(CNPJ), eq(UF), any(), eq(1))).thenReturn(preparadoSoap(1));
        when(eventoMapper.marcarTransmitido(eq(1L), any(), any())).thenReturn(1);
        when(cceService.transmitirEvento(any(), any())).thenReturn("<retEnvEvento/>");

        NfeEventoRetorno retorno = new NfeEventoRetorno();
        retorno.setCStatLote(128);
        retorno.setInfEventoPresente(true);
        retorno.setCStatEvento(136);
        retorno.setXMotivoEvento("Evento registrado, mas não vinculado a NF-e");
        when(eventoRetornoParser.parse(any())).thenReturn(retorno);
        when(eventoMapper.buscarPorIdParaAtualizar(1L)).thenReturn(evento(1L, NfeEvento.Estados.TRANSMITIDO, 1));

        BusinessException ex = assertThrows(BusinessException.class, this::corrigir);

        assertEquals("CCE_AGUARDANDO_RECONCILIACAO", ex.getErrorCode());
        assertTrue(ex.isRetryable());
        verify(sequenciaMapper, never()).liberarGate(any(), any());
    }

    @Test
    @DisplayName("573 — Duplicidade de Evento — PENDENTE_CONFIRMACAO, nunca retransmite na mesma chamada")
    void cStat573_pendenteConfirmacao_naoRetransmite() throws Exception {
        stubGateExistente(0, null);
        stubSemIdempotenciaExistente();
        stubInsercoesComId(1L, 100L);
        when(cceService.prepararEvento(any(), eq(CNPJ), eq(UF), any(), eq(1))).thenReturn(preparadoSoap(1));
        when(eventoMapper.marcarTransmitido(eq(1L), any(), any())).thenReturn(1);
        when(cceService.transmitirEvento(any(), any())).thenReturn("<retEnvEvento/>");

        NfeEventoRetorno retorno = new NfeEventoRetorno();
        retorno.setCStatLote(128);
        retorno.setInfEventoPresente(true);
        retorno.setCStatEvento(573);
        retorno.setXMotivoEvento("Rejeição: Duplicidade de Evento");
        when(eventoRetornoParser.parse(any())).thenReturn(retorno);
        when(eventoMapper.buscarPorIdParaAtualizar(1L)).thenReturn(evento(1L, NfeEvento.Estados.TRANSMITIDO, 1));

        BusinessException ex = assertThrows(BusinessException.class, this::corrigir);

        assertEquals("CCE_AGUARDANDO_RECONCILIACAO", ex.getErrorCode());
        verify(cceService, times(1)).transmitirEvento(any(), any());
    }

    @Test
    @DisplayName("594 — limite de sequência excedido pela SEFAZ — REJEITADO via classificador default, gate mantido ocupado")
    void cStat594_rejeitadoPeloClassificadorDefault() throws Exception {
        stubGateExistente(0, null);
        stubSemIdempotenciaExistente();
        stubInsercoesComId(1L, 100L);
        when(cceService.prepararEvento(any(), eq(CNPJ), eq(UF), any(), eq(1))).thenReturn(preparadoSoap(1));
        when(eventoMapper.marcarTransmitido(eq(1L), any(), any())).thenReturn(1);
        when(cceService.transmitirEvento(any(), any())).thenReturn("<retEnvEvento/>");

        NfeEventoRetorno retorno = new NfeEventoRetorno();
        retorno.setCStatLote(128);
        retorno.setInfEventoPresente(true);
        retorno.setCStatEvento(594);
        retorno.setXMotivoEvento("Rejeição: nSeqEvento inválido");
        when(eventoRetornoParser.parse(any())).thenReturn(retorno);
        when(eventoMapper.buscarPorIdParaAtualizar(1L)).thenReturn(evento(1L, NfeEvento.Estados.TRANSMITIDO, 1));

        BusinessException ex = assertThrows(BusinessException.class, this::corrigir);

        assertEquals("CCE_REJEITADA", ex.getErrorCode());
        assertFalse(ex.isRetryable());
        verify(sequenciaMapper, never()).liberarGate(any(), any());
        verify(sequenciaMapper, never()).atualizarUltimoNSeq(any(), any(), anyInt());
    }

    @Test
    @DisplayName("Lote rejeitado explicitamente (cStat != 128, sem infEvento) — REJEITADO com dados do lote")
    void loteRejeitadoExplicitamente_rejeitado() throws Exception {
        stubGateExistente(0, null);
        stubSemIdempotenciaExistente();
        stubInsercoesComId(1L, 100L);
        when(cceService.prepararEvento(any(), eq(CNPJ), eq(UF), any(), eq(1))).thenReturn(preparadoSoap(1));
        when(eventoMapper.marcarTransmitido(eq(1L), any(), any())).thenReturn(1);
        when(cceService.transmitirEvento(any(), any())).thenReturn("<retEnvEvento/>");

        NfeEventoRetorno retorno = new NfeEventoRetorno();
        retorno.setCStatLote(225);
        retorno.setXMotivoLote("Rejeição: Falha no Schema XML");
        when(eventoRetornoParser.parse(any())).thenReturn(retorno);
        when(eventoMapper.buscarPorIdParaAtualizar(1L)).thenReturn(evento(1L, NfeEvento.Estados.TRANSMITIDO, 1));

        BusinessException ex = assertThrows(BusinessException.class, this::corrigir);

        assertEquals("CCE_REJEITADA", ex.getErrorCode());
    }

    @Test
    @DisplayName("SOAP malformado (falha de parse) — PENDENTE_CONFIRMACAO, nunca rejeição inventada")
    void soapMalformado_pendenteConfirmacao() throws Exception {
        stubGateExistente(0, null);
        stubSemIdempotenciaExistente();
        stubInsercoesComId(1L, 100L);
        when(cceService.prepararEvento(any(), eq(CNPJ), eq(UF), any(), eq(1))).thenReturn(preparadoSoap(1));
        when(eventoMapper.marcarTransmitido(eq(1L), any(), any())).thenReturn(1);
        when(cceService.transmitirEvento(any(), any())).thenReturn("<xml quebrado");
        when(eventoRetornoParser.parse(any())).thenReturn(NfeEventoRetorno.falhaParse("XML malformado"));
        when(eventoMapper.buscarPorIdParaAtualizar(1L)).thenReturn(evento(1L, NfeEvento.Estados.TRANSMITIDO, 1));

        BusinessException ex = assertThrows(BusinessException.class, this::corrigir);

        assertEquals("CCE_AGUARDANDO_RECONCILIACAO", ex.getErrorCode());
    }

    @Test
    @DisplayName("Timeout na transmissão (SefazTransmissaoIncertaException) — AGUARDANDO_RECONCILIACAO, sem escrita adicional")
    void timeoutNaTransmissao_aguardaReconciliacao() throws Exception {
        stubGateExistente(0, null);
        stubSemIdempotenciaExistente();
        stubInsercoesComId(1L, 100L);
        when(cceService.prepararEvento(any(), eq(CNPJ), eq(UF), any(), eq(1))).thenReturn(preparadoSoap(1));
        when(eventoMapper.marcarTransmitido(eq(1L), any(), any())).thenReturn(1);
        when(cceService.transmitirEvento(any(), any()))
                .thenThrow(new SefazTransmissaoIncertaException(new java.net.SocketTimeoutException()));

        BusinessException ex = assertThrows(BusinessException.class, this::corrigir);

        assertEquals("CCE_AGUARDANDO_RECONCILIACAO", ex.getErrorCode());
        verifyNoInteractions(eventoRetornoParser);
        verify(eventoMapper, never()).atualizarResultado(any());
    }

    // -------------------------------------------------------------------------
    // Concorrência / claim
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("marcarTransmitido perde a corrida — CCE_EM_ANDAMENTO, nunca transmite")
    void marcarTransmitidoPerdeCorrida_emAndamento() throws Exception {
        stubGateExistente(0, null);
        stubSemIdempotenciaExistente();
        stubInsercoesComId(1L, 100L);
        when(cceService.prepararEvento(any(), eq(CNPJ), eq(UF), any(), eq(1))).thenReturn(preparadoSoap(1));
        when(eventoMapper.marcarTransmitido(eq(1L), any(), any())).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class, this::corrigir);

        assertEquals("CCE_EM_ANDAMENTO", ex.getErrorCode());
        verify(cceService, never()).transmitirEvento(any(), any());
    }

    @Test
    @DisplayName("Gate ocupado por outra operação (evento_ativo não REJEITADO) — CCE_EM_ANDAMENTO, nunca insere/prepara")
    void gateOcupadoPorOutraOperacao_emAndamento() throws Exception {
        stubGateExistente(1, 1L);
        stubSemIdempotenciaExistente();
        when(eventoMapper.buscarPorIdParaAtualizar(1L)).thenReturn(evento(1L, NfeEvento.Estados.TRANSMITIDO, 1));

        BusinessException ex = assertThrows(BusinessException.class, this::corrigir);

        assertEquals("CCE_EM_ANDAMENTO", ex.getErrorCode());
        verify(eventoMapper, never()).inserirPreparado(any());
        verifyNoInteractions(cceService);
    }

    @Test
    @DisplayName("Gate aponta para evento REJEITADO — reabre a MESMA identidade fiscal (nSeq preservado)")
    void gateApontaParaRejeitado_reabreComMesmoNSeq() throws Exception {
        stubGateExistente(2, 5L);
        stubSemIdempotenciaExistente();
        NfeEvento rejeitado = evento(5L, NfeEvento.Estados.REJEITADO, 3);
        // 1ª leitura (reservarNovaOperacao, decide reabrir): REJEITADO. 2ª leitura (aplicarFinalizacao,
        // depois de retomarAposRejeicaoComConteudo/marcarTransmitido já terem avançado a linha de
        // verdade): TRANSMITIDO -- o mock não simula a mutação real da linha entre as duas leituras.
        when(eventoMapper.buscarPorIdParaAtualizar(5L))
                .thenReturn(rejeitado, evento(5L, NfeEvento.Estados.TRANSMITIDO, 3));
        when(eventoMapper.retomarAposRejeicaoComConteudo(eq(5L), eq(CORRECAO))).thenReturn(1);
        doAnswer(inv -> {
            NfeEventoIdempotencia i = inv.getArgument(0);
            i.setId(100L);
            return 1;
        }).when(idempotenciaMapper).inserir(any());

        when(cceService.prepararEvento(any(), eq(CNPJ), eq(UF), any(), eq(3))).thenReturn(preparadoSoap(3));
        when(eventoMapper.marcarTransmitido(eq(5L), any(), any())).thenReturn(1);
        when(cceService.transmitirEvento(any(), any())).thenReturn("<retEnvEvento/>");

        NfeEventoRetorno retorno = new NfeEventoRetorno();
        retorno.setCStatLote(128);
        retorno.setInfEventoPresente(true);
        retorno.setCStatEvento(135);
        retorno.setXMotivoEvento("Evento registrado e vinculado a NF-e");
        retorno.setNProtEvento("135260000009998");
        when(eventoRetornoParser.parse(any())).thenReturn(retorno);

        String resultado = corrigir();

        assertTrue(resultado.contains("135260000009998"));
        verify(cceService).prepararEvento(any(), eq(CNPJ), eq(UF), any(), eq(3));
        verify(sequenciaMapper).atualizarUltimoNSeq(CHAVE, TIPO, 3);
        verify(eventoMapper, never()).inserirPreparado(any()); // reaproveita a linha, nunca insere nova
    }

    @Test
    @DisplayName("Reabertura perde a corrida (retomarAposRejeicaoComConteudo afeta 0 linhas) — CCE_EM_ANDAMENTO")
    void reaberturaPerdeCorrida_emAndamento() throws Exception {
        stubGateExistente(2, 5L);
        stubSemIdempotenciaExistente();
        when(eventoMapper.buscarPorIdParaAtualizar(5L)).thenReturn(evento(5L, NfeEvento.Estados.REJEITADO, 3));
        when(eventoMapper.retomarAposRejeicaoComConteudo(eq(5L), any())).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class, this::corrigir);

        assertEquals("CCE_EM_ANDAMENTO", ex.getErrorCode());
        verifyNoInteractions(cceService);
    }

    @Test
    @DisplayName("Limite de 20 sequências atingido — CCE_LIMITE_SEQUENCIA_ATINGIDO, nunca insere")
    void limiteSequenciaAtingido() throws Exception {
        stubGateExistente(20, null);
        stubSemIdempotenciaExistente();

        BusinessException ex = assertThrows(BusinessException.class, this::corrigir);

        assertEquals("CCE_LIMITE_SEQUENCIA_ATINGIDO", ex.getErrorCode());
        verify(eventoMapper, never()).inserirPreparado(any());
        verifyNoInteractions(cceService);
    }

    @Test
    @DisplayName("Corrida de Idempotency-Key: DuplicateKeyException no insert — releitura e delega ao caminho de replay")
    void corridaIdempotencyKey_duplicateKeyException_delegaReplay() throws Exception {
        stubGateExistente(1, null);
        when(idempotenciaMapper.buscarPorIdempotencyKeyParaAtualizar(IDEM_KEY)).thenReturn(null);
        doThrow(new DuplicateKeyException("uk_nfe_evento_idempotencia_key")).when(idempotenciaMapper).inserir(any());

        NfeEventoIdempotencia concorrente = idempotencia(200L, NfeEventoIdempotencia.Estados.REGISTRADO,
                135, "Evento registrado e vinculado a NF-e", "135260000001111");
        // 1ª leitura (checagem inicial, antes da TX): nada ainda. 2ª leitura (após a corrida perdida
        // dentro da TX): a chamada vencedora já comitou e é encontrada.
        when(idempotenciaMapper.buscarPorIdempotencyKey(IDEM_KEY))
                .thenReturn(null)
                .thenReturn(concorrente);
        when(eventoMapper.buscarPorId(1L)).thenReturn(evento(1L, NfeEvento.Estados.REGISTRADO, 1));

        String resultado = corrigir();

        assertTrue(resultado.contains("135260000001111"));
        verifyNoInteractions(cceService);
    }

    // -------------------------------------------------------------------------
    // Validação de Idempotency-Key
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Idempotency-Key ausente — INVALID_IDEMPOTENCY_KEY, nenhuma interação com mapeadores")
    void idempotencyKeyAusente_invalida() {
        BusinessException ex = assertThrows(BusinessException.class, () -> corrigir(null));

        assertEquals("INVALID_IDEMPOTENCY_KEY", ex.getErrorCode());
        verifyNoInteractions(eventoMapper, sequenciaMapper, idempotenciaMapper, cceService);
    }

    @Test
    @DisplayName("Idempotency-Key não é UUID — INVALID_IDEMPOTENCY_KEY")
    void idempotencyKeyNaoUuid_invalida() {
        BusinessException ex = assertThrows(BusinessException.class, () -> corrigir("nao-e-um-uuid"));

        assertEquals("INVALID_IDEMPOTENCY_KEY", ex.getErrorCode());
        verifyNoInteractions(eventoMapper, sequenciaMapper, idempotenciaMapper, cceService);
    }

    // -------------------------------------------------------------------------
    // Replay por Idempotency-Key — todos os estados possíveis
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Replay — estado REGISTRADO — devolve resultado já homologado, nenhuma nova transmissão")
    void replay_registrado_devolveResultadoCached() throws Exception {
        NfeEventoIdempotencia idem = idempotencia(100L, NfeEventoIdempotencia.Estados.REGISTRADO,
                135, "Evento registrado e vinculado a NF-e", "135260000009999");
        when(idempotenciaMapper.buscarPorIdempotencyKey(IDEM_KEY)).thenReturn(idem);
        when(eventoMapper.buscarPorId(1L)).thenReturn(evento(1L, NfeEvento.Estados.REGISTRADO, 1));

        String resultado = corrigir();

        assertTrue(resultado.contains("135260000009999"));
        verifyNoInteractions(cceService);
        verify(sequenciaMapper, never()).buscarParaAtualizar(any(), any());
    }

    @Test
    @DisplayName("Replay — estado REJEITADO normal (cStat presente) — repete CCE_REJEITADA a partir do snapshot")
    void replay_rejeitadoNormal_repeteRejeicaoDoSnapshot() throws Exception {
        NfeEventoIdempotencia idem = idempotencia(100L, NfeEventoIdempotencia.Estados.REJEITADO,
                594, "Rejeição: nSeqEvento inválido", null);
        when(idempotenciaMapper.buscarPorIdempotencyKey(IDEM_KEY)).thenReturn(idem);
        when(eventoMapper.buscarPorId(1L)).thenReturn(evento(1L, NfeEvento.Estados.REJEITADO, 1));

        BusinessException ex = assertThrows(BusinessException.class, this::corrigir);

        assertEquals("CCE_REJEITADA", ex.getErrorCode());
        verifyNoInteractions(cceService);
    }

    @Test
    @DisplayName("Replay — estado REJEITADO DIVERGENTE (cStat nulo) — CCE_EVENTO_DIVERGENTE, nunca CCE_REJEITADA genérica "
            + "(bug corrigido 12-08-2026: processarComIdempotenciaExistente não distinguia os dois casos)")
    void replay_rejeitadoDivergente_lancaEventoDivergenteNaoRejeicaoGenerica() throws Exception {
        NfeEventoIdempotencia idem = idempotencia(100L, NfeEventoIdempotencia.Estados.REJEITADO,
                null, "Sequência ocupada por conteúdo divergente", null);
        when(idempotenciaMapper.buscarPorIdempotencyKey(IDEM_KEY)).thenReturn(idem);
        when(eventoMapper.buscarPorId(1L)).thenReturn(evento(1L, NfeEvento.Estados.REGISTRADO, 3));

        BusinessException ex = assertThrows(BusinessException.class, this::corrigir);

        assertEquals("CCE_EVENTO_DIVERGENTE", ex.getErrorCode());
        assertTrue(ex.getMessage().contains(CHAVE));
        verifyNoInteractions(cceService);
    }

    @Test
    @DisplayName("Replay — mesma Idempotency-Key, tenant/pedido diferente — CCE_IDEMPOTENCY_KEY_CONFLICT, nenhum dado vaza")
    void replay_tenantDiferente_conflito() {
        NfeEventoIdempotencia idem = idempotencia(100L, NfeEventoIdempotencia.Estados.REGISTRADO,
                135, "Evento registrado e vinculado a NF-e", "135260000009999");
        idem.setEmpresaId(999L); // outra empresa
        when(idempotenciaMapper.buscarPorIdempotencyKey(IDEM_KEY)).thenReturn(idem);

        BusinessException ex = assertThrows(BusinessException.class, this::corrigir);

        assertEquals("CCE_IDEMPOTENCY_KEY_CONFLICT", ex.getErrorCode());
        verifyNoInteractions(eventoMapper, cceService);
    }

    @Test
    @DisplayName("Replay — mesma Idempotency-Key, conteúdo (hash) diferente — CCE_IDEMPOTENCY_KEY_CONFLICT")
    void replay_hashDiferente_conflito() {
        NfeEventoIdempotencia idem = idempotencia(100L, NfeEventoIdempotencia.Estados.REGISTRADO,
                135, "Evento registrado e vinculado a NF-e", "135260000009999");
        idem.setConteudoHash("hash-de-outra-operacao");
        when(idempotenciaMapper.buscarPorIdempotencyKey(IDEM_KEY)).thenReturn(idem);

        BusinessException ex = assertThrows(BusinessException.class, this::corrigir);

        assertEquals("CCE_IDEMPOTENCY_KEY_CONFLICT", ex.getErrorCode());
        verifyNoInteractions(eventoMapper, cceService);
    }

    @Test
    @DisplayName("Replay — operação ainda PREPARADO (crash antes da transmissão) — retoma a transmissão, nunca reabre nova linha")
    void replay_preparado_retomaTransmissao() throws Exception {
        NfeEventoIdempotencia idem = idempotencia(100L, NfeEventoIdempotencia.Estados.PREPARADO, null, null, null);
        when(idempotenciaMapper.buscarPorIdempotencyKey(IDEM_KEY)).thenReturn(idem);
        when(eventoMapper.buscarPorId(1L)).thenReturn(evento(1L, NfeEvento.Estados.PREPARADO, 1));
        when(cceService.prepararEvento(any(), eq(CNPJ), eq(UF), any(), eq(1))).thenReturn(preparadoSoap(1));
        when(eventoMapper.marcarTransmitido(eq(1L), any(), any())).thenReturn(1);
        when(cceService.transmitirEvento(any(), any())).thenReturn("<retEnvEvento/>");

        NfeEventoRetorno retorno = new NfeEventoRetorno();
        retorno.setCStatLote(128);
        retorno.setInfEventoPresente(true);
        retorno.setCStatEvento(135);
        retorno.setXMotivoEvento("Evento registrado e vinculado a NF-e");
        retorno.setNProtEvento("135260000009997");
        when(eventoRetornoParser.parse(any())).thenReturn(retorno);
        when(eventoMapper.buscarPorIdParaAtualizar(1L)).thenReturn(evento(1L, NfeEvento.Estados.TRANSMITIDO, 1));

        String resultado = corrigir();

        assertTrue(resultado.contains("135260000009997"));
        verify(eventoMapper, never()).inserirPreparado(any());
        verify(sequenciaMapper, never()).buscarParaAtualizar(any(), any());
    }

    @Test
    @DisplayName("Replay — operação TRANSMITIDO (timeout anterior) — delega para reconciliação, nunca retransmite")
    void replay_transmitido_delegaParaReconciliacao() throws Exception {
        NfeEventoIdempotencia idem = idempotencia(100L, NfeEventoIdempotencia.Estados.TRANSMITIDO, null, null, null);
        when(idempotenciaMapper.buscarPorIdempotencyKey(IDEM_KEY)).thenReturn(idem);
        when(eventoMapper.buscarPorId(1L)).thenReturn(evento(1L, NfeEvento.Estados.TRANSMITIDO, 1));
        when(eventoMapper.tentarAdquirirJanelaConsulta(eq(1L), any(), anyInt(), anyDouble(), anyInt())).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class, this::corrigir);

        assertEquals("CCE_AGUARDANDO_RECONCILIACAO", ex.getErrorCode());
        verifyNoInteractions(cceService);
    }

    // -------------------------------------------------------------------------
    // Bootstrap de sequência histórica
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Bootstrap: chave nunca vista localmente — calcula baseline a partir dos eventos REGISTRADOS")
    void bootstrap_calculaBaselineDosRegistrados() throws Exception {
        when(sequenciaMapper.buscarSemLock(CHAVE, TIPO)).thenReturn(null);
        ListaEventosRetorno lista = ListaEventosRetorno.ok(List.of(
                new ListaEventosRetorno.EventoEncontrado(1, 135),
                new ListaEventosRetorno.EventoEncontrado(2, 135),
                new ListaEventosRetorno.EventoEncontrado(3, 594))); // rejeitada, não conta
        when(consultaSituacaoService.listarEventosPorTipo(eq(CHAVE), eq(UF), anyInt(), any(), eq(TIPO)))
                .thenReturn(lista);
        when(sequenciaMapper.buscarParaAtualizar(CHAVE, TIPO)).thenReturn(sequencia(2, null));
        stubSemIdempotenciaExistente();
        stubInsercoesComId(1L, 100L);
        when(cceService.prepararEvento(any(), eq(CNPJ), eq(UF), any(), eq(3))).thenReturn(preparadoSoap(3));
        when(eventoMapper.marcarTransmitido(eq(1L), any(), any())).thenReturn(1);
        when(cceService.transmitirEvento(any(), any())).thenReturn("<retEnvEvento/>");
        NfeEventoRetorno retorno = new NfeEventoRetorno();
        retorno.setCStatLote(128);
        retorno.setInfEventoPresente(true);
        retorno.setCStatEvento(135);
        retorno.setXMotivoEvento("Evento registrado e vinculado a NF-e");
        retorno.setNProtEvento("135260000009996");
        when(eventoRetornoParser.parse(any())).thenReturn(retorno);
        when(eventoMapper.buscarPorIdParaAtualizar(1L)).thenReturn(evento(1L, NfeEvento.Estados.TRANSMITIDO, 3));

        corrigir();

        ArgumentCaptor<NfeEventoSequencia> captor = ArgumentCaptor.forClass(NfeEventoSequencia.class);
        verify(sequenciaMapper).inserir(captor.capture());
        assertEquals(2, captor.getValue().getUltimoNSeqRegistrado());
        verify(cceService).prepararEvento(any(), any(), any(), any(), eq(3)); // próxima livre = baseline+1
    }

    @Test
    @DisplayName("Bootstrap: falha de transporte na Consulta Situação — CCE_BOOTSTRAP_INDETERMINADO, fail-closed")
    void bootstrap_falhaTransporte_indeterminado() {
        when(sequenciaMapper.buscarSemLock(CHAVE, TIPO)).thenReturn(null);
        when(consultaSituacaoService.listarEventosPorTipo(eq(CHAVE), eq(UF), anyInt(), any(), eq(TIPO)))
                .thenThrow(new SefazTransmissaoIncertaException(new java.net.SocketTimeoutException()));

        BusinessException ex = assertThrows(BusinessException.class, this::corrigir);

        assertEquals("CCE_BOOTSTRAP_INDETERMINADO", ex.getErrorCode());
        assertTrue(ex.isRetryable());
        verify(sequenciaMapper, never()).inserir(any());
    }

    @Test
    @DisplayName("Bootstrap: resposta ilegível (falha de parse) — CCE_BOOTSTRAP_INDETERMINADO, nunca assume zero")
    void bootstrap_falhaParse_indeterminado() {
        when(sequenciaMapper.buscarSemLock(CHAVE, TIPO)).thenReturn(null);
        when(consultaSituacaoService.listarEventosPorTipo(eq(CHAVE), eq(UF), anyInt(), any(), eq(TIPO)))
                .thenReturn(ListaEventosRetorno.falha("XML malformado"));

        BusinessException ex = assertThrows(BusinessException.class, this::corrigir);

        assertEquals("CCE_BOOTSTRAP_INDETERMINADO", ex.getErrorCode());
        verify(sequenciaMapper, never()).inserir(any());
    }

    @Test
    @DisplayName("Bootstrap: corrida entre duas chamadas concorrentes (DuplicateKeyException no insert) — inofensivo")
    void bootstrap_corridaConcorrente_inofensivo() throws Exception {
        when(sequenciaMapper.buscarSemLock(CHAVE, TIPO)).thenReturn(null);
        when(consultaSituacaoService.listarEventosPorTipo(eq(CHAVE), eq(UF), anyInt(), any(), eq(TIPO)))
                .thenReturn(ListaEventosRetorno.ok(List.of()));
        doThrow(new DuplicateKeyException("uk_nfe_evento_sequencia")).when(sequenciaMapper).inserir(any());
        when(sequenciaMapper.buscarParaAtualizar(CHAVE, TIPO)).thenReturn(sequencia(0, null));
        stubSemIdempotenciaExistente();
        stubInsercoesComId(1L, 100L);
        when(cceService.prepararEvento(any(), eq(CNPJ), eq(UF), any(), eq(1))).thenReturn(preparadoSoap(1));
        when(eventoMapper.marcarTransmitido(eq(1L), any(), any())).thenReturn(1);
        when(cceService.transmitirEvento(any(), any())).thenReturn("<retEnvEvento/>");
        NfeEventoRetorno retorno = new NfeEventoRetorno();
        retorno.setCStatLote(128);
        retorno.setInfEventoPresente(true);
        retorno.setCStatEvento(135);
        retorno.setXMotivoEvento("Evento registrado e vinculado a NF-e");
        retorno.setNProtEvento("135260000009995");
        when(eventoRetornoParser.parse(any())).thenReturn(retorno);
        when(eventoMapper.buscarPorIdParaAtualizar(1L)).thenReturn(evento(1L, NfeEvento.Estados.TRANSMITIDO, 1));

        String resultado = corrigir();

        assertTrue(resultado.contains("135260000009995"));
    }

    // -------------------------------------------------------------------------
    // Reconciliação — com validação de conteúdo (573/registrado)
    // -------------------------------------------------------------------------

    private void stubParaReconciliar(String estadoNfeEvento) {
        NfeEventoIdempotencia idem = idempotencia(100L, NfeEventoIdempotencia.Estados.TRANSMITIDO, null, null, null);
        when(idempotenciaMapper.buscarPorIdempotencyKey(IDEM_KEY)).thenReturn(idem);
        when(eventoMapper.buscarPorId(1L)).thenReturn(evento(1L, estadoNfeEvento, 1));
        when(eventoMapper.tentarAdquirirJanelaConsulta(eq(1L), any(), anyInt(), anyDouble(), anyInt())).thenReturn(1);
        // só usado pelos cenários que chegam a aplicarFinalizacao -- lenient porque falha de
        // transporte/claim-perdido/evento-nao-encontrado nunca chegam lá.
        lenient().when(eventoMapper.buscarPorIdParaAtualizar(1L)).thenReturn(evento(1L, estadoNfeEvento, 1));
    }

    @Test
    @DisplayName("Reconciliação: mesmo conteúdo encontrado — REGISTRADO real, sequência avança")
    void reconciliar_mesmoConteudo_registrado() throws Exception {
        stubParaReconciliar(NfeEvento.Estados.TRANSMITIDO);
        NfeConsultaSituacaoRetorno retorno = new NfeConsultaSituacaoRetorno();
        retorno.setEventoEncontrado(true);
        retorno.setCStatEvento(135);
        retorno.setXMotivoEvento("Evento registrado e vinculado a NF-e");
        retorno.setNProtEvento("135260000009994");
        retorno.setConteudoEventoEncontrado(CORRECAO);
        when(consultaSituacaoService.consultarEvento(eq(CHAVE), eq(UF), anyInt(), any(), eq(TIPO), eq("1")))
                .thenReturn(retorno);

        String resultado = corrigir();

        assertTrue(resultado.contains("135260000009994"));
        verify(sequenciaMapper).atualizarUltimoNSeq(CHAVE, TIPO, 1);
        verify(sequenciaMapper).liberarGate(CHAVE, TIPO);
    }

    @Test
    @DisplayName("Reconciliação: identidade bate (573/135) mas conteúdo é OUTRO — CCE_EVENTO_DIVERGENTE, "
            + "sequência é consumida mesmo assim (nfe_evento vira REGISTRADO, idempotência REJEITADO)")
    void reconciliar_conteudoDivergente_lancaDivergenteMasConsomeSequencia() throws Exception {
        stubParaReconciliar(NfeEvento.Estados.TRANSMITIDO);
        NfeConsultaSituacaoRetorno retorno = new NfeConsultaSituacaoRetorno();
        retorno.setEventoEncontrado(true);
        retorno.setCStatEvento(135);
        retorno.setXMotivoEvento("Evento registrado e vinculado a NF-e");
        retorno.setNProtEvento("135260000009993");
        retorno.setConteudoEventoEncontrado("Texto de correção completamente diferente");
        when(consultaSituacaoService.consultarEvento(eq(CHAVE), eq(UF), anyInt(), any(), eq(TIPO), eq("1")))
                .thenReturn(retorno);

        BusinessException ex = assertThrows(BusinessException.class, this::corrigir);

        assertEquals("CCE_EVENTO_DIVERGENTE", ex.getErrorCode());
        verify(sequenciaMapper).atualizarUltimoNSeq(CHAVE, TIPO, 1);
        verify(sequenciaMapper).liberarGate(CHAVE, TIPO);
        ArgumentCaptor<NfeEvento> captor = ArgumentCaptor.forClass(NfeEvento.class);
        verify(eventoMapper).atualizarResultado(captor.capture());
        assertEquals(NfeEvento.Estados.REGISTRADO, captor.getValue().getEstado());
        ArgumentCaptor<NfeEventoIdempotencia> idemCaptor = ArgumentCaptor.forClass(NfeEventoIdempotencia.class);
        verify(idempotenciaMapper).atualizarResultado(idemCaptor.capture());
        assertEquals(NfeEventoIdempotencia.Estados.REJEITADO, idemCaptor.getValue().getEstadoResultado());
        assertNull(idemCaptor.getValue().getCstatResultado(), "divergente nunca carrega um cStat real");
    }

    @Test
    @DisplayName("Reconciliação: identidade bate mas conteúdo encontrado é nulo (resposta incompleta) — trata como divergente, nunca assume sucesso")
    void reconciliar_conteudoAusente_trataComoDivergente() throws Exception {
        stubParaReconciliar(NfeEvento.Estados.TRANSMITIDO);
        NfeConsultaSituacaoRetorno retorno = new NfeConsultaSituacaoRetorno();
        retorno.setEventoEncontrado(true);
        retorno.setCStatEvento(135);
        retorno.setXMotivoEvento("Evento registrado e vinculado a NF-e");
        retorno.setNProtEvento("135260000009992");
        retorno.setConteudoEventoEncontrado(null);
        when(consultaSituacaoService.consultarEvento(eq(CHAVE), eq(UF), anyInt(), any(), eq(TIPO), eq("1")))
                .thenReturn(retorno);

        BusinessException ex = assertThrows(BusinessException.class, this::corrigir);

        assertEquals("CCE_EVENTO_DIVERGENTE", ex.getErrorCode());
    }

    @Test
    @DisplayName("Reconciliação: claim de janela perdido (backoff não venceu) — AGUARDANDO_RECONCILIACAO, nunca toca a rede")
    void reconciliar_claimPerdido_naoTocaRede() throws Exception {
        NfeEventoIdempotencia idem = idempotencia(100L, NfeEventoIdempotencia.Estados.TRANSMITIDO, null, null, null);
        when(idempotenciaMapper.buscarPorIdempotencyKey(IDEM_KEY)).thenReturn(idem);
        when(eventoMapper.buscarPorId(1L)).thenReturn(evento(1L, NfeEvento.Estados.TRANSMITIDO, 1));
        when(eventoMapper.tentarAdquirirJanelaConsulta(eq(1L), any(), anyInt(), anyDouble(), anyInt())).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class, this::corrigir);

        assertEquals("CCE_AGUARDANDO_RECONCILIACAO", ex.getErrorCode());
        verifyNoInteractions(consultaSituacaoService);
    }

    @Test
    @DisplayName("Reconciliação: falha de transporte na consulta — AGUARDANDO_RECONCILIACAO, nunca decide sem resposta")
    void reconciliar_falhaTransporte_aguardaReconciliacao() throws Exception {
        stubParaReconciliar(NfeEvento.Estados.TRANSMITIDO);
        when(consultaSituacaoService.consultarEvento(any(), any(), anyInt(), any(), any(), any()))
                .thenThrow(new SefazTransmissaoIncertaException(new java.net.SocketTimeoutException()));

        BusinessException ex = assertThrows(BusinessException.class, this::corrigir);

        assertEquals("CCE_AGUARDANDO_RECONCILIACAO", ex.getErrorCode());
        verify(eventoMapper, never()).atualizarResultado(any());
    }

    @Test
    @DisplayName("Reconciliação: evento ainda não encontrado (128 sem infEvento equivalente) — PENDENTE, nunca inventa rejeição")
    void reconciliar_eventoNaoEncontrado_pendente() throws Exception {
        stubParaReconciliar(NfeEvento.Estados.TRANSMITIDO);
        NfeConsultaSituacaoRetorno retorno = new NfeConsultaSituacaoRetorno();
        retorno.setEventoEncontrado(false);
        when(consultaSituacaoService.consultarEvento(eq(CHAVE), eq(UF), anyInt(), any(), eq(TIPO), eq("1")))
                .thenReturn(retorno);

        BusinessException ex = assertThrows(BusinessException.class, this::corrigir);

        assertEquals("CCE_AGUARDANDO_RECONCILIACAO", ex.getErrorCode());
        // PENDENTE_CONFIRMACAO ainda é persistido (bookkeeping de tentativas/estado observável),
        // só não é terminal -- nunca inventa REGISTRADO/REJEITADO sem resposta da SEFAZ.
        ArgumentCaptor<NfeEvento> captor = ArgumentCaptor.forClass(NfeEvento.class);
        verify(eventoMapper).atualizarResultado(captor.capture());
        assertEquals(NfeEvento.Estados.PENDENTE_CONFIRMACAO, captor.getValue().getEstado());
        assertNull(captor.getValue().getResolvidoEm());
    }

    // -------------------------------------------------------------------------
    // Finalização concorrente — ciclo já terminal ao aplicar
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Finalização concorrente: nfe_evento já terminal ao aplicar — reconsulta a PRÓPRIA idempotência por id, nunca decide de novo")
    void finalizacaoConcorrente_jaTerminal_reconsultaPorId() throws Exception {
        stubGateExistente(0, null);
        stubSemIdempotenciaExistente();
        stubInsercoesComId(1L, 100L);
        when(cceService.prepararEvento(any(), eq(CNPJ), eq(UF), any(), eq(1))).thenReturn(preparadoSoap(1));
        when(eventoMapper.marcarTransmitido(eq(1L), any(), any())).thenReturn(1);
        when(cceService.transmitirEvento(any(), any())).thenReturn("<retEnvEvento/>");
        NfeEventoRetorno retorno = new NfeEventoRetorno();
        retorno.setCStatLote(128);
        retorno.setInfEventoPresente(true);
        retorno.setCStatEvento(135);
        retorno.setXMotivoEvento("Evento registrado e vinculado a NF-e");
        retorno.setNProtEvento("135260000009991");
        when(eventoRetornoParser.parse(any())).thenReturn(retorno);

        // outra chamada concorrente da MESMA operação já resolveu a linha antes desta chegar em aplicarFinalizacao
        when(eventoMapper.buscarPorIdParaAtualizar(1L)).thenReturn(evento(1L, NfeEvento.Estados.REGISTRADO, 1));
        NfeEventoIdempotencia jaResolvida = idempotencia(100L, NfeEventoIdempotencia.Estados.REGISTRADO,
                135, "Evento registrado e vinculado a NF-e", "135260000009991");
        when(idempotenciaMapper.buscarPorId(100L)).thenReturn(jaResolvida);

        String resultado = corrigir();

        assertTrue(resultado.contains("135260000009991"));
        verify(eventoMapper, never()).atualizarResultado(any());
        verify(idempotenciaMapper, never()).atualizarResultado(any());
    }
}
