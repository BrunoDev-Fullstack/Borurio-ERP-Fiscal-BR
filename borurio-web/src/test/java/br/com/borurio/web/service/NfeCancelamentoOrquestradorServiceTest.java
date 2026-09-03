package br.com.borurio.web.service;

import br.com.borurio.app.entity.PedidoItem;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.fiscal.dto.NfeCancelamentoRequest;
import br.com.borurio.fiscal.dto.NfeConsultaSituacaoRetorno;
import br.com.borurio.fiscal.dto.NfeEventoPreparado;
import br.com.borurio.fiscal.dto.NfeEventoRetorno;
import br.com.borurio.fiscal.entity.NfeEvento;
import br.com.borurio.fiscal.exception.SefazTransmissaoIncertaException;
import br.com.borurio.fiscal.service.CertificadoContexto;
import br.com.borurio.fiscal.service.NfeCancelamentoService;
import br.com.borurio.fiscal.service.NfeConsultaSituacaoService;
import br.com.borurio.fiscal.service.NfeEventoClassificador;
import br.com.borurio.fiscal.service.NfeEventoRetornoParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Gate de cancelamento (evento 110111, 12-08-2026) — orquestrador (rede + classificação),
 * NfeEventoService mockado (a persistência exactly-once é coberta isoladamente em
 * NfeEventoServiceTest e contra MySQL real em NfeEventoConcorrenciaRealMySqlIT). Usa o
 * NfeEventoClassificador REAL (pequeno, puro, sem valor em mockar) para exercitar a matriz
 * completa ponta a ponta a partir do XML/retorno simulado.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NfeCancelamentoOrquestradorService — matriz de cStat, timeout, reconciliação")
class NfeCancelamentoOrquestradorServiceTest {

    @Mock NfeEventoService nfeEventoService;
    @Mock NfeCancelamentoService cancelamentoService;
    @Mock NfeEventoRetornoParser eventoRetornoParser;
    @Mock NfeConsultaSituacaoService consultaSituacaoService;

    NfeCancelamentoOrquestradorService orquestrador;

    private static final Long PEDIDO_ID = 50L;
    private static final Long EMISSAO_ID = 700L;
    private static final Long EMPRESA_ID = 10L;
    private static final String CHAVE = "35260500000000000191550010000000011000000013";
    private static final String CNPJ = "22418179000134";
    private static final String UF = "SP";

    @BeforeEach
    void setUp() {
        orquestrador = new NfeCancelamentoOrquestradorService(nfeEventoService, cancelamentoService,
                eventoRetornoParser, new NfeEventoClassificador(), consultaSituacaoService);
    }

    private NfeEvento eventoPreparado() {
        NfeEvento e = new NfeEvento();
        e.setId(1L);
        e.setPedidoId(PEDIDO_ID);
        e.setEmissaoId(EMISSAO_ID);
        e.setChaveNfe(CHAVE);
        e.setEstado(NfeEvento.Estados.PREPARADO);
        return e;
    }

    private NfeEventoPreparado preparadoSoap() {
        return new NfeEventoPreparado("ID110111" + CHAVE + "01", "2026-08-12T10:00:00-03:00",
                "<evento-assinado/>", "hash-xyz", CHAVE, CNPJ);
    }

    private List<PedidoItem> itens() {
        PedidoItem item = new PedidoItem();
        item.setProdutoId(1L);
        item.setQuantidade(new BigDecimal("2"));
        return List.of(item);
    }

    private void stubClaimNovo() throws Exception {
        when(nfeEventoService.reivindicar(eq(PEDIDO_ID), eq(EMISSAO_ID), eq(EMPRESA_ID), eq(CNPJ), eq(CHAVE), anyString()))
                .thenReturn(new NfeEventoService.Claim(eventoPreparado(), false));
        when(cancelamentoService.prepararEvento(any(), eq(CNPJ), eq(UF), any(), eq(1))).thenReturn(preparadoSoap());
        when(nfeEventoService.marcarTransmitido(eq(1L), any(), any())).thenReturn(true);
    }

    private String cancelar() throws Exception {
        return orquestrador.cancelar(PEDIDO_ID, EMISSAO_ID, EMPRESA_ID, CNPJ, UF, CHAVE, "135260000000001",
                "Cliente desistiu da compra", mock(CertificadoContexto.class), true, itens(), "sistema");
    }

    private NfeEventoRetorno retornoComEvento(Integer cStatLote, boolean infEventoPresente, Integer cStatEvento,
                                               String xMotivo, String nProt) {
        NfeEventoRetorno r = new NfeEventoRetorno();
        r.setCStatLote(cStatLote);
        r.setInfEventoPresente(infEventoPresente);
        r.setCStatEvento(cStatEvento);
        r.setXMotivoEvento(xMotivo);
        r.setNProtEvento(nProt);
        return r;
    }

    // -------------------------------------------------------------------------
    // Matriz principal via transmissão direta
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("135 — REGISTRADO, finaliza com sucesso")
    void cStat135_registrado() throws Exception {
        stubClaimNovo();
        when(cancelamentoService.transmitirEvento(any(), any())).thenReturn("<retEnvEvento/>");
        when(eventoRetornoParser.parse("<retEnvEvento/>"))
                .thenReturn(retornoComEvento(128, true, 135, "Evento registrado e vinculado a NF-e", "135260000009999"));
        when(nfeEventoService.finalizar(eq(1L), any(), eq(NfeEvento.OrigensResolucao.EVENTO_DIRETO), eq(EMISSAO_ID),
                eq(PEDIDO_ID), eq(true), any(), eq(EMPRESA_ID), any())).thenReturn(true);

        String resultado = cancelar();

        assertTrue(resultado.contains("135260000009999"));
        ArgumentCaptor<NfeEventoService.Decisao> captor = ArgumentCaptor.forClass(NfeEventoService.Decisao.class);
        verify(nfeEventoService).finalizar(eq(1L), captor.capture(), any(), any(), any(), anyBoolean(), any(), any(), any());
        assertEquals(NfeEvento.Estados.REGISTRADO, captor.getValue().estado());
        assertFalse(captor.getValue().foraDoPrazo());
    }

    @Test
    @DisplayName("155 — REGISTRADO com foraDoPrazo=true, efeitos aplicados exatamente uma vez")
    void cStat155_registradoForaDoPrazo() throws Exception {
        stubClaimNovo();
        when(cancelamentoService.transmitirEvento(any(), any())).thenReturn("<retEnvEvento/>");
        when(eventoRetornoParser.parse(any()))
                .thenReturn(retornoComEvento(128, true, 155, "Cancelamento homologado fora de prazo", "135260000009998"));
        when(nfeEventoService.finalizar(any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any())).thenReturn(true);

        cancelar();

        ArgumentCaptor<NfeEventoService.Decisao> captor = ArgumentCaptor.forClass(NfeEventoService.Decisao.class);
        verify(nfeEventoService).finalizar(any(), captor.capture(), any(), any(), any(), anyBoolean(), any(), any(), any());
        assertEquals(NfeEvento.Estados.REGISTRADO, captor.getValue().estado());
        assertTrue(captor.getValue().foraDoPrazo());
    }

    @Test
    @DisplayName("136 — evento registrado mas não vinculado — PENDENTE_CONFIRMACAO, nenhum efeito, exceção retryable")
    void cStat136_pendenteConfirmacao_semEfeitos() throws Exception {
        stubClaimNovo();
        when(cancelamentoService.transmitirEvento(any(), any())).thenReturn("<retEnvEvento/>");
        when(eventoRetornoParser.parse(any()))
                .thenReturn(retornoComEvento(128, true, 136, "Evento registrado, mas não vinculado a NF-e", null));
        when(nfeEventoService.finalizar(any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any())).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class, this::cancelar);

        assertEquals("CANCELAMENTO_AGUARDANDO_RECONCILIACAO", ex.getErrorCode());
        assertTrue(ex.isRetryable());
        ArgumentCaptor<NfeEventoService.Decisao> captor = ArgumentCaptor.forClass(NfeEventoService.Decisao.class);
        verify(nfeEventoService).finalizar(any(), captor.capture(), any(), any(), any(), anyBoolean(), any(), any(), any());
        assertEquals(NfeEvento.Estados.PENDENTE_CONFIRMACAO, captor.getValue().estado());
    }

    @Test
    @DisplayName("573 — Duplicidade de Evento — PENDENTE_CONFIRMACAO, nunca retransmite, exige reconciliação")
    void cStat573_pendenteConfirmacao_naoRetransmite() throws Exception {
        stubClaimNovo();
        when(cancelamentoService.transmitirEvento(any(), any())).thenReturn("<retEnvEvento/>");
        when(eventoRetornoParser.parse(any()))
                .thenReturn(retornoComEvento(128, true, 573, "Rejeição: Duplicidade de Evento", null));
        when(nfeEventoService.finalizar(any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any())).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class, this::cancelar);

        assertEquals("CANCELAMENTO_AGUARDANDO_RECONCILIACAO", ex.getErrorCode());
        verify(cancelamentoService, times(1)).transmitirEvento(any(), any()); // nunca uma segunda tentativa nesta chamada
    }

    @Test
    @DisplayName("Lote 128 + rejeição individual comum (ex.: 280) — REJEITADO, sem efeitos")
    void lote128ComRejeicaoIndividual_rejeitado() throws Exception {
        stubClaimNovo();
        when(cancelamentoService.transmitirEvento(any(), any())).thenReturn("<retEnvEvento/>");
        when(eventoRetornoParser.parse(any()))
                .thenReturn(retornoComEvento(128, true, 280, "Rejeição: dado inconsistente", null));
        when(nfeEventoService.finalizar(any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any())).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class, this::cancelar);

        assertEquals("CANCELAMENTO_REJEITADO", ex.getErrorCode());
        assertFalse(ex.isRetryable());
    }

    @Test
    @DisplayName("Lote rejeitado explicitamente (cStat != 128, sem infEvento) — REJEITADO com cStat/xMotivo do lote")
    void loteRejeitadoExplicitamente_rejeitado() throws Exception {
        stubClaimNovo();
        when(cancelamentoService.transmitirEvento(any(), any())).thenReturn("<retEnvEvento/>");
        NfeEventoRetorno retornoLote = retornoComEvento(225, false, null, null, null);
        retornoLote.setXMotivoLote("Rejeição: Falha no Schema XML");
        when(eventoRetornoParser.parse(any())).thenReturn(retornoLote);
        when(nfeEventoService.finalizar(any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any())).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class, this::cancelar);

        assertEquals("CANCELAMENTO_REJEITADO", ex.getErrorCode());
    }

    @Test
    @DisplayName("128 sem infEvento (resposta incompleta) — PENDENTE_CONFIRMACAO, nunca rejeição inventada")
    void lote128SemInfEvento_pendenteConfirmacao() throws Exception {
        stubClaimNovo();
        when(cancelamentoService.transmitirEvento(any(), any())).thenReturn("<retEnvEvento/>");
        when(eventoRetornoParser.parse(any())).thenReturn(retornoComEvento(128, false, null, null, null));
        when(nfeEventoService.finalizar(any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any())).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class, this::cancelar);

        assertEquals("CANCELAMENTO_AGUARDANDO_RECONCILIACAO", ex.getErrorCode());
    }

    @Test
    @DisplayName("SOAP malformado (falha de parse) — PENDENTE_CONFIRMACAO, nunca rejeição")
    void soapMalformado_pendenteConfirmacao() throws Exception {
        stubClaimNovo();
        when(cancelamentoService.transmitirEvento(any(), any())).thenReturn("<xml quebrado");
        when(eventoRetornoParser.parse(any())).thenReturn(NfeEventoRetorno.falhaParse("XML malformado"));
        when(nfeEventoService.finalizar(any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any())).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class, this::cancelar);

        assertEquals("CANCELAMENTO_AGUARDANDO_RECONCILIACAO", ex.getErrorCode());
    }

    @Test
    @DisplayName("Timeout depois da transmissão (SefazTransmissaoIncertaException) — PENDENTE_CONFIRMACAO, nenhuma escrita adicional")
    void timeoutAposTransmissao_aguardaReconciliacao() throws Exception {
        stubClaimNovo();
        when(cancelamentoService.transmitirEvento(any(), any())).thenThrow(new SefazTransmissaoIncertaException(new java.net.SocketTimeoutException()));

        BusinessException ex = assertThrows(BusinessException.class, this::cancelar);

        assertEquals("CANCELAMENTO_AGUARDANDO_RECONCILIACAO", ex.getErrorCode());
        verifyNoInteractions(eventoRetornoParser);
        verify(nfeEventoService, never()).finalizar(any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // Idempotência e concorrência (via NfeEventoService mockado)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Repetição após cancelamento confirmado — retorno idempotente, nenhuma nova transmissão")
    void repeticaoAposConfirmado_idempotente() throws Exception {
        NfeEvento registrado = eventoPreparado();
        registrado.setEstado(NfeEvento.Estados.REGISTRADO);
        registrado.setNprot("135260000009999");
        when(nfeEventoService.reivindicar(any(), any(), any(), any(), any(), any()))
                .thenReturn(new NfeEventoService.Claim(registrado, true));

        String resultado = cancelar();

        assertTrue(resultado.contains("135260000009999"));
        verifyNoInteractions(cancelamentoService);
    }

    @Test
    @DisplayName("Concorrência: marcarTransmitido perde a corrida — CANCELAMENTO_EM_ANDAMENTO, nunca transmite")
    void marcarTransmitidoPerdeCorrida_emAndamento() throws Exception {
        when(nfeEventoService.reivindicar(any(), any(), any(), any(), any(), any()))
                .thenReturn(new NfeEventoService.Claim(eventoPreparado(), false));
        when(cancelamentoService.prepararEvento(any(), any(), any(), any(), anyInt())).thenReturn(preparadoSoap());
        when(nfeEventoService.marcarTransmitido(any(), any(), any())).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, this::cancelar);

        assertEquals("CANCELAMENTO_EM_ANDAMENTO", ex.getErrorCode());
        verify(cancelamentoService, never()).transmitirEvento(any(), any());
    }

    @Test
    @DisplayName("Evento existente TRANSMITIDO — delega para reconciliação em vez de retransmitir")
    void eventoExistenteTransmitido_delegaParaReconciliacao() throws Exception {
        NfeEvento transmitido = eventoPreparado();
        transmitido.setEstado(NfeEvento.Estados.TRANSMITIDO);
        when(nfeEventoService.reivindicar(any(), any(), any(), any(), any(), any()))
                .thenReturn(new NfeEventoService.Claim(transmitido, false));
        when(nfeEventoService.buscarUltimaTentativa(CHAVE)).thenReturn(transmitido);
        when(nfeEventoService.tentarAdquirirJanelaConsulta(1L)).thenReturn(false); // backoff não venceu

        BusinessException ex = assertThrows(BusinessException.class, this::cancelar);

        assertEquals("CANCELAMENTO_AGUARDANDO_RECONCILIACAO", ex.getErrorCode());
        verifyNoInteractions(cancelamentoService);
        verifyNoInteractions(consultaSituacaoService);
    }

    // -------------------------------------------------------------------------
    // Reconciliação — procEventoNFe exato vs. cStat=101 sem evento detalhado
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Reconciliação: procEventoNFe correspondente encontrado — usa cStat/nProt do EVENTO, origem CONSULTA_SITUACAO")
    void reconciliar_procEventoNFeCorrespondente_usaDadosDoEvento() {
        NfeEvento pendente = eventoPreparado();
        pendente.setEstado(NfeEvento.Estados.PENDENTE_CONFIRMACAO);
        when(nfeEventoService.buscarUltimaTentativa(CHAVE)).thenReturn(pendente);
        when(nfeEventoService.tentarAdquirirJanelaConsulta(1L)).thenReturn(true);

        NfeConsultaSituacaoRetorno retorno = new NfeConsultaSituacaoRetorno();
        retorno.setEventoEncontrado(true);
        retorno.setCStatEvento(135);
        retorno.setXMotivoEvento("Evento registrado e vinculado a NF-e");
        retorno.setNProtEvento("135260000009999");
        when(consultaSituacaoService.consultarEvento(eq(CHAVE), eq(UF), anyInt(), any(),
                eq(NfeEvento.TiposEvento.CANCELAMENTO), eq("01"))).thenReturn(retorno);
        when(nfeEventoService.finalizar(any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any())).thenReturn(true);

        orquestrador.reconciliar(PEDIDO_ID, EMISSAO_ID, EMPRESA_ID, UF, CHAVE, mock(CertificadoContexto.class),
                true, itens(), "sistema");

        ArgumentCaptor<NfeEventoService.Decisao> captor = ArgumentCaptor.forClass(NfeEventoService.Decisao.class);
        verify(nfeEventoService).finalizar(any(), captor.capture(), eq(NfeEvento.OrigensResolucao.CONSULTA_SITUACAO),
                any(), any(), anyBoolean(), any(), any(), any());
        assertEquals(NfeEvento.Estados.REGISTRADO, captor.getValue().estado());
        assertEquals("135260000009999", captor.getValue().nProt());
    }

    @Test
    @DisplayName("Reconciliação: cStat=101 sem procEventoNFe detalhado — projeta REGISTRADO, nunca inventa nProt")
    void reconciliar_101SemEventoDetalhado_naoInventaProtocolo() {
        NfeEvento pendente = eventoPreparado();
        pendente.setEstado(NfeEvento.Estados.PENDENTE_CONFIRMACAO);
        when(nfeEventoService.buscarUltimaTentativa(CHAVE)).thenReturn(pendente);
        when(nfeEventoService.tentarAdquirirJanelaConsulta(1L)).thenReturn(true);

        NfeConsultaSituacaoRetorno retorno = new NfeConsultaSituacaoRetorno();
        retorno.setCStat(101);
        retorno.setXMotivo("Cancelamento de NF-e homologado");
        retorno.setProtNFePresente(true);
        retorno.setEventoEncontrado(false);
        when(consultaSituacaoService.consultarEvento(eq(CHAVE), eq(UF), anyInt(), any(),
                eq(NfeEvento.TiposEvento.CANCELAMENTO), eq("01"))).thenReturn(retorno);
        when(nfeEventoService.finalizar(any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any())).thenReturn(true);

        orquestrador.reconciliar(PEDIDO_ID, EMISSAO_ID, EMPRESA_ID, UF, CHAVE, mock(CertificadoContexto.class),
                true, itens(), "sistema");

        ArgumentCaptor<NfeEventoService.Decisao> captor = ArgumentCaptor.forClass(NfeEventoService.Decisao.class);
        verify(nfeEventoService).finalizar(any(), captor.capture(), any(), any(), any(), anyBoolean(), any(), any(), any());
        assertEquals(NfeEvento.Estados.REGISTRADO, captor.getValue().estado());
        assertNull(captor.getValue().nProt(), "sem procEventoNFe detalhado, nunca inventa nProt");
        assertNull(captor.getValue().cStat(), "101 e cStat de SITUACAO da NF-e, nunca vira cStatEvento (achado de banca 12-08-2026)");
    }

    @Test
    @DisplayName("Reconciliação: backoff não venceu (claim perdido) — AGUARDANDO_RECONCILIACAO sem tocar a rede")
    void reconciliar_backoffNaoVencido_naoTocaRede() {
        NfeEvento pendente = eventoPreparado();
        pendente.setEstado(NfeEvento.Estados.PENDENTE_CONFIRMACAO);
        when(nfeEventoService.buscarUltimaTentativa(CHAVE)).thenReturn(pendente);
        when(nfeEventoService.tentarAdquirirJanelaConsulta(1L)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                orquestrador.reconciliar(PEDIDO_ID, EMISSAO_ID, EMPRESA_ID, UF, CHAVE, mock(CertificadoContexto.class),
                        true, itens(), "sistema"));

        assertEquals("CANCELAMENTO_AGUARDANDO_RECONCILIACAO", ex.getErrorCode());
        verifyNoInteractions(consultaSituacaoService);
    }

    @Test
    @DisplayName("Reconciliação: falha de transporte na consulta — AGUARDANDO_RECONCILIACAO, nunca decide sem resposta")
    void reconciliar_falhaTransporte_aguardaReconciliacao() {
        NfeEvento pendente = eventoPreparado();
        pendente.setEstado(NfeEvento.Estados.PENDENTE_CONFIRMACAO);
        when(nfeEventoService.buscarUltimaTentativa(CHAVE)).thenReturn(pendente);
        when(nfeEventoService.tentarAdquirirJanelaConsulta(1L)).thenReturn(true);
        when(consultaSituacaoService.consultarEvento(any(), any(), anyInt(), any(), any(), any()))
                .thenThrow(new SefazTransmissaoIncertaException(new java.net.SocketTimeoutException()));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                orquestrador.reconciliar(PEDIDO_ID, EMISSAO_ID, EMPRESA_ID, UF, CHAVE, mock(CertificadoContexto.class),
                        true, itens(), "sistema"));

        assertEquals("CANCELAMENTO_AGUARDANDO_RECONCILIACAO", ex.getErrorCode());
        verify(nfeEventoService, never()).finalizar(any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any());
    }
}
