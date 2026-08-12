package br.com.borurio.web.service;

import br.com.borurio.app.entity.PedidoItem;
import br.com.borurio.app.mapper.PedidoMapper;
import br.com.borurio.app.service.EstoqueService;
import br.com.borurio.fiscal.config.SefazReconciliacaoProperties;
import br.com.borurio.fiscal.entity.NfeEvento;
import br.com.borurio.fiscal.mapper.NfeEmissaoMapper;
import br.com.borurio.fiscal.mapper.NfeEventoMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Gate de cancelamento (evento 110111, 12-08-2026) — camada transacional pura
 * (NfeEventoService): claim/reivindicação, transmissão marcada e finalização exactly-once.
 * Cobre, em isolamento (sem rede, sem MySQL real — a prova de concorrência real fica em
 * NfeEventoConcorrenciaRealMySqlIT), os cenários de reabertura pós-rejeição (nSeqEvento fixo
 * em 1), corrida de insert/reabertura, e preservação da evidência de autorização original.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NfeEventoService — camada transacional do gate de cancelamento")
class NfeEventoServiceTest {

    @Mock NfeEventoMapper nfeEventoMapper;
    @Mock NfeEmissaoMapper nfeEmissaoMapper;
    @Mock PedidoMapper pedidoMapper;
    @Mock EstoqueService estoqueService;

    NfeEventoService service;

    private static final String CHAVE = "35260500000000000191550010000000011000000013";
    private static final Long PEDIDO_ID = 50L;
    private static final Long EMISSAO_ID = 700L;
    private static final Long EMPRESA_ID = 10L;

    @BeforeEach
    void setUp() {
        service = new NfeEventoService(nfeEventoMapper, nfeEmissaoMapper, pedidoMapper, estoqueService,
                new SefazReconciliacaoProperties());
    }

    private NfeEvento evento(Long id, String estado) {
        NfeEvento e = new NfeEvento();
        e.setId(id);
        e.setPedidoId(PEDIDO_ID);
        e.setEmissaoId(EMISSAO_ID);
        e.setEmpresaId(EMPRESA_ID);
        e.setChaveNfe(CHAVE);
        e.setTipoEvento(NfeEvento.TiposEvento.CANCELAMENTO);
        e.setNSeqEvento(1);
        e.setEstado(estado);
        return e;
    }

    private List<PedidoItem> itens() {
        PedidoItem item = new PedidoItem();
        item.setProdutoId(1L);
        item.setQuantidade(new BigDecimal("2"));
        return List.of(item);
    }

    // -------------------------------------------------------------------------
    // reivindicar — claim
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("reivindicar: sem tentativa anterior — insere PREPARADO com nSeqEvento=1")
    void reivindicar_semTentativaAnterior_insereNova() {
        when(nfeEventoMapper.buscarUltimaTentativa(CHAVE, NfeEvento.TiposEvento.CANCELAMENTO)).thenReturn(null);

        NfeEventoService.Claim claim = service.reivindicar(PEDIDO_ID, EMISSAO_ID, EMPRESA_ID, "22418179000134",
                CHAVE, "Cliente desistiu da compra");

        assertFalse(claim.jaResolvido());
        assertEquals(NfeEvento.Estados.PREPARADO, claim.evento().getEstado());
        assertEquals(1, claim.evento().getNSeqEvento());
        assertEquals("ID110111" + CHAVE + "01", claim.evento().getIdEvento());
        verify(nfeEventoMapper).inserirPreparado(any());
    }

    @Test
    @DisplayName("reivindicar: evento REGISTRADO anteriormente — idempotente, nunca reabre")
    void reivindicar_registradoAnterior_idempotente() {
        NfeEvento existente = evento(1L, NfeEvento.Estados.REGISTRADO);
        when(nfeEventoMapper.buscarUltimaTentativa(CHAVE, NfeEvento.TiposEvento.CANCELAMENTO)).thenReturn(existente);

        NfeEventoService.Claim claim = service.reivindicar(PEDIDO_ID, EMISSAO_ID, EMPRESA_ID, "22418179000134",
                CHAVE, "Cliente desistiu da compra");

        assertTrue(claim.jaResolvido());
        assertSame(existente, claim.evento());
        verify(nfeEventoMapper, never()).inserirPreparado(any());
        verify(nfeEventoMapper, never()).retomarAposRejeicao(any(), any());
    }

    @Test
    @DisplayName("reivindicar: evento REJEITADO anterior — reabre a MESMA linha (nSeqEvento continua 1), nova justificativa persistida, campos de resolução anteriores zerados")
    void reivindicar_rejeitadoAnterior_reabreMesmaLinha() {
        NfeEvento existente = evento(1L, NfeEvento.Estados.REJEITADO);
        existente.setJustificativa("Justificativa antiga, rejeitada");
        existente.setCstat(280);
        existente.setXmotivo("Rejeição: dado inconsistente");
        existente.setNprot(null);
        existente.setResolucaoOrigem(NfeEvento.OrigensResolucao.EVENTO_DIRETO);
        existente.setResolvidoEm(java.time.LocalDateTime.now().minusHours(1));
        existente.setDhEvento("2026-08-12T09:00:00-03:00");
        existente.setPayloadHash("hash-da-tentativa-anterior");
        existente.setTransmitidoEm(java.time.LocalDateTime.now().minusHours(1));
        when(nfeEventoMapper.buscarUltimaTentativa(CHAVE, NfeEvento.TiposEvento.CANCELAMENTO)).thenReturn(existente);
        when(nfeEventoMapper.retomarAposRejeicao(1L, "Nova tentativa corrigida")).thenReturn(1);

        NfeEventoService.Claim claim = service.reivindicar(PEDIDO_ID, EMISSAO_ID, EMPRESA_ID, "22418179000134",
                CHAVE, "Nova tentativa corrigida");

        assertFalse(claim.jaResolvido());
        assertEquals(NfeEvento.Estados.PREPARADO, claim.evento().getEstado());
        assertEquals(1, claim.evento().getNSeqEvento(), "nSeqEvento do cancelamento é sempre 1, nunca incrementa");
        assertEquals(1L, claim.evento().getId(), "mesma linha, mesmo id — nunca insere uma nova");
        assertEquals("Nova tentativa corrigida", claim.evento().getJustificativa(), "nova justificativa precisa ser persistida, nunca a antiga");
        assertNull(claim.evento().getCstat(), "cstat da rejeição anterior não pode vazar pra tentativa nova");
        assertNull(claim.evento().getXmotivo(), "xmotivo da rejeição anterior não pode vazar pra tentativa nova");
        assertNull(claim.evento().getResolucaoOrigem());
        assertNull(claim.evento().getResolvidoEm());
        assertNull(claim.evento().getDhEvento(), "dh_evento da tentativa anterior não pode sobreviver a um crash antes de marcarTransmitido()");
        assertNull(claim.evento().getPayloadHash(), "payload_hash da tentativa anterior não pode sobreviver a um crash antes de marcarTransmitido()");
        assertNull(claim.evento().getTransmitidoEm());
        verify(nfeEventoMapper, never()).inserirPreparado(any());
        verify(nfeEventoMapper).retomarAposRejeicao(1L, "Nova tentativa corrigida");
    }

    @Test
    @DisplayName("reivindicar: evento PREPARADO anterior (crash antes de qualquer transmissão) — retoma na mesma linha")
    void reivindicar_preparadoAnterior_retomaNaMesmaLinha() {
        NfeEvento existente = evento(1L, NfeEvento.Estados.PREPARADO);
        when(nfeEventoMapper.buscarUltimaTentativa(CHAVE, NfeEvento.TiposEvento.CANCELAMENTO)).thenReturn(existente);

        NfeEventoService.Claim claim = service.reivindicar(PEDIDO_ID, EMISSAO_ID, EMPRESA_ID, "22418179000134",
                CHAVE, "Cliente desistiu da compra");

        assertFalse(claim.jaResolvido());
        assertSame(existente, claim.evento());
        verify(nfeEventoMapper, never()).inserirPreparado(any());
    }

    @Test
    @DisplayName("reivindicar: evento TRANSMITIDO/PENDENTE_CONFIRMACAO — devolve o estado real, nunca retransmite")
    void reivindicar_transmitidoOuPendente_devolveEstadoReal() {
        NfeEvento existente = evento(1L, NfeEvento.Estados.PENDENTE_CONFIRMACAO);
        when(nfeEventoMapper.buscarUltimaTentativa(CHAVE, NfeEvento.TiposEvento.CANCELAMENTO)).thenReturn(existente);

        NfeEventoService.Claim claim = service.reivindicar(PEDIDO_ID, EMISSAO_ID, EMPRESA_ID, "22418179000134",
                CHAVE, "Cliente desistiu da compra");

        assertFalse(claim.jaResolvido());
        assertEquals(NfeEvento.Estados.PENDENTE_CONFIRMACAO, claim.evento().getEstado());
        verify(nfeEventoMapper, never()).inserirPreparado(any());
        verify(nfeEventoMapper, never()).retomarAposRejeicao(any(), any());
    }

    @Test
    @DisplayName("reivindicar: corrida no INSERT (DuplicateKeyException) — reconsulta e decide com o estado real, nunca duas linhas")
    void reivindicar_corridaNoInsert_reconsultaEDecide() {
        when(nfeEventoMapper.buscarUltimaTentativa(CHAVE, NfeEvento.TiposEvento.CANCELAMENTO))
                .thenReturn(null) // primeira leitura: nada existe
                .thenReturn(evento(1L, NfeEvento.Estados.PREPARADO)); // outra chamada venceu a corrida
        doThrow(new DuplicateKeyException("uk_nfe_evento_identidade")).when(nfeEventoMapper).inserirPreparado(any());

        NfeEventoService.Claim claim = service.reivindicar(PEDIDO_ID, EMISSAO_ID, EMPRESA_ID, "22418179000134",
                CHAVE, "Cliente desistiu da compra");

        assertFalse(claim.jaResolvido());
        assertEquals(1L, claim.evento().getId());
        verify(nfeEventoMapper, times(2)).buscarUltimaTentativa(CHAVE, NfeEvento.TiposEvento.CANCELAMENTO);
    }

    @Test
    @DisplayName("reivindicar: corrida na reabertura pós-rejeição (retomarAposRejeicao=0) — reconsulta em vez de assumir")
    void reivindicar_corridaNaReaberturaPosRejeicao_reconsulta() {
        NfeEvento rejeitado = evento(1L, NfeEvento.Estados.REJEITADO);
        when(nfeEventoMapper.buscarUltimaTentativa(CHAVE, NfeEvento.TiposEvento.CANCELAMENTO)).thenReturn(rejeitado);
        when(nfeEventoMapper.retomarAposRejeicao(eq(1L), any())).thenReturn(0); // outra chamada já reabriu/avançou
        when(nfeEventoMapper.buscarPorId(1L)).thenReturn(evento(1L, NfeEvento.Estados.TRANSMITIDO));

        NfeEventoService.Claim claim = service.reivindicar(PEDIDO_ID, EMISSAO_ID, EMPRESA_ID, "22418179000134",
                CHAVE, "Nova tentativa corrigida");

        assertEquals(NfeEvento.Estados.TRANSMITIDO, claim.evento().getEstado());
    }

    // -------------------------------------------------------------------------
    // marcarTransmitido
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("marcarTransmitido: affectedRows=1 — true")
    void marcarTransmitido_sucesso() {
        when(nfeEventoMapper.marcarTransmitido(1L, "dh", "hash")).thenReturn(1);
        assertTrue(service.marcarTransmitido(1L, "dh", "hash"));
    }

    @Test
    @DisplayName("marcarTransmitido: affectedRows=0 (corrida perdida) — false, nunca lança exceção aqui")
    void marcarTransmitido_corridaPerdida_false() {
        when(nfeEventoMapper.marcarTransmitido(1L, "dh", "hash")).thenReturn(0);
        assertFalse(service.marcarTransmitido(1L, "dh", "hash"));
    }

    // -------------------------------------------------------------------------
    // finalizar — exactly-once, preservação da evidência de autorização original
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("finalizar: REGISTRADO — marca NfeEmissao=CANCELADO, Pedido=CANCELADO, estorna estoque; nunca toca cstat/nprot da autorização")
    void finalizar_registrado_aplicaTodosOsEfeitos() {
        NfeEvento travado = evento(1L, NfeEvento.Estados.TRANSMITIDO);
        when(nfeEventoMapper.buscarPorIdParaAtualizar(1L)).thenReturn(travado);

        NfeEventoService.Decisao decisao = new NfeEventoService.Decisao(
                NfeEvento.Estados.REGISTRADO, 135, "Evento registrado e vinculado a NF-e", "135260000009999", false);

        boolean aplicado = service.finalizar(1L, decisao, NfeEvento.OrigensResolucao.EVENTO_DIRETO,
                EMISSAO_ID, PEDIDO_ID, true, itens(), EMPRESA_ID, "sistema");

        assertTrue(aplicado);
        verify(nfeEmissaoMapper).marcarCancelado(EMISSAO_ID);
        // marcarCancelado é a ÚNICA chamada em NfeEmissaoMapper — nunca atualizarResultado, que
        // sobrescreveria cstat/xmotivo/nprot da autorização original.
        verify(nfeEmissaoMapper, never()).atualizarResultado(any());
        verify(pedidoMapper).atualizarStatus(PEDIDO_ID, "CANCELADO", CHAVE);
        verify(estoqueService).estornarBaixaItens(itens(), EMPRESA_ID, PEDIDO_ID, "sistema");

        var eventoCaptor = org.mockito.ArgumentCaptor.forClass(NfeEvento.class);
        verify(nfeEventoMapper).atualizarResultado(eventoCaptor.capture());
        assertEquals(NfeEvento.Estados.REGISTRADO, eventoCaptor.getValue().getEstado());
        assertEquals(135, eventoCaptor.getValue().getCstat());
        assertEquals("135260000009999", eventoCaptor.getValue().getNprot());
        assertNotNull(eventoCaptor.getValue().getResolvidoEm());
    }

    @Test
    @DisplayName("finalizar: REGISTRADO com emissaoId null (pedido legado sem ciclo Gate 1) — não quebra, só pula marcarCancelado")
    void finalizar_registradoSemEmissaoId_naoQuebra() {
        NfeEvento travado = evento(1L, NfeEvento.Estados.TRANSMITIDO);
        when(nfeEventoMapper.buscarPorIdParaAtualizar(1L)).thenReturn(travado);

        NfeEventoService.Decisao decisao = new NfeEventoService.Decisao(
                NfeEvento.Estados.REGISTRADO, 135, "Evento registrado e vinculado a NF-e", "135260000009999", false);

        boolean aplicado = service.finalizar(1L, decisao, NfeEvento.OrigensResolucao.EVENTO_DIRETO,
                null, PEDIDO_ID, true, itens(), EMPRESA_ID, "sistema");

        assertTrue(aplicado);
        verify(nfeEmissaoMapper, never()).marcarCancelado(any());
        verify(pedidoMapper).atualizarStatus(PEDIDO_ID, "CANCELADO", CHAVE);
    }

    @Test
    @DisplayName("finalizar: REJEITADO — nenhum efeito em NfeEmissao/Pedido/estoque")
    void finalizar_rejeitado_nenhumEfeito() {
        NfeEvento travado = evento(1L, NfeEvento.Estados.TRANSMITIDO);
        when(nfeEventoMapper.buscarPorIdParaAtualizar(1L)).thenReturn(travado);

        NfeEventoService.Decisao decisao = new NfeEventoService.Decisao(
                NfeEvento.Estados.REJEITADO, 280, "Rejeição: protocolo divergente", null, false);

        boolean aplicado = service.finalizar(1L, decisao, NfeEvento.OrigensResolucao.EVENTO_DIRETO,
                EMISSAO_ID, PEDIDO_ID, true, itens(), EMPRESA_ID, "sistema");

        assertTrue(aplicado);
        verifyNoInteractions(nfeEmissaoMapper, pedidoMapper, estoqueService);
    }

    @Test
    @DisplayName("finalizar: PENDENTE_CONFIRMACAO — nenhum efeito, resolvidoEm continua null")
    void finalizar_pendenteConfirmacao_nenhumEfeitoResolvidoEmNull() {
        NfeEvento travado = evento(1L, NfeEvento.Estados.TRANSMITIDO);
        when(nfeEventoMapper.buscarPorIdParaAtualizar(1L)).thenReturn(travado);

        NfeEventoService.Decisao decisao = new NfeEventoService.Decisao(
                NfeEvento.Estados.PENDENTE_CONFIRMACAO, 136, "Evento registrado, mas não vinculado a NF-e", null, false);

        boolean aplicado = service.finalizar(1L, decisao, NfeEvento.OrigensResolucao.EVENTO_DIRETO,
                EMISSAO_ID, PEDIDO_ID, true, itens(), EMPRESA_ID, "sistema");

        assertTrue(aplicado);
        verifyNoInteractions(nfeEmissaoMapper, pedidoMapper, estoqueService);
        var eventoCaptor = org.mockito.ArgumentCaptor.forClass(NfeEvento.class);
        verify(nfeEventoMapper).atualizarResultado(eventoCaptor.capture());
        assertNull(eventoCaptor.getValue().getResolvidoEm(), "PENDENTE_CONFIRMACAO nunca é terminal");
    }

    @Test
    @DisplayName("finalizar: evento já terminal (chamada duplicada/concorrente) — no-op idempotente, retorna false")
    void finalizar_jaTerminal_noOpIdempotente() {
        NfeEvento jaRegistrado = evento(1L, NfeEvento.Estados.REGISTRADO);
        when(nfeEventoMapper.buscarPorIdParaAtualizar(1L)).thenReturn(jaRegistrado);

        NfeEventoService.Decisao decisao = new NfeEventoService.Decisao(
                NfeEvento.Estados.REGISTRADO, 135, "Evento registrado e vinculado a NF-e", "135260000009999", false);

        boolean aplicado = service.finalizar(1L, decisao, NfeEvento.OrigensResolucao.EVENTO_DIRETO,
                EMISSAO_ID, PEDIDO_ID, true, itens(), EMPRESA_ID, "sistema");

        assertFalse(aplicado, "ciclo já terminal — nunca reaplica efeitos");
        verifyNoInteractions(nfeEmissaoMapper, pedidoMapper, estoqueService);
        verify(nfeEventoMapper, never()).atualizarResultado(any());
    }

    @Test
    @DisplayName("finalizar: REGISTRADO com controlaEstoque=false — nunca estorna, mesmo com itens presentes")
    void finalizar_registradoSemControleEstoque_naoEstorna() {
        NfeEvento travado = evento(1L, NfeEvento.Estados.TRANSMITIDO);
        when(nfeEventoMapper.buscarPorIdParaAtualizar(1L)).thenReturn(travado);

        NfeEventoService.Decisao decisao = new NfeEventoService.Decisao(
                NfeEvento.Estados.REGISTRADO, 155, "Cancelamento homologado fora de prazo", "135260000009999", true);

        service.finalizar(1L, decisao, NfeEvento.OrigensResolucao.EVENTO_DIRETO,
                EMISSAO_ID, PEDIDO_ID, false, itens(), EMPRESA_ID, "sistema");

        verify(estoqueService, never()).estornarBaixaItens(any(), any(), any(), any());
        var eventoCaptor = org.mockito.ArgumentCaptor.forClass(NfeEvento.class);
        verify(nfeEventoMapper).atualizarResultado(eventoCaptor.capture());
        assertTrue(eventoCaptor.getValue().isForaDoPrazo());
    }
}
