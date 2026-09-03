package br.com.borurio.web.service;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.entity.Pedido;
import br.com.borurio.app.entity.PedidoItem;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.app.mapper.PedidoMapper;
import br.com.borurio.app.service.EstoqueService;
import br.com.borurio.fiscal.config.SefazReconciliacaoProperties;
import br.com.borurio.fiscal.entity.NfeDocumento;
import br.com.borurio.fiscal.entity.NfeEmissao;
import br.com.borurio.fiscal.entity.NfeSequencia;
import br.com.borurio.fiscal.mapper.NfeEmissaoMapper;
import br.com.borurio.fiscal.service.NfeSequenciaService;

import java.util.Optional;
import br.com.borurio.web.dto.AberturaCicloResultado;
import br.com.borurio.web.dto.TipoAberturaCiclo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Gate 1 — ciclo do nNF. Cobre a correção central da revisão de 07-08-2026: AGUARDANDO_CORRECAO
 * e PENDENTE_CONFIRMACAO NÃO liberam o gate da série; só AUTORIZADO/DENEGADO liberam.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NfeEmissaoService — ciclo do nNF (Gate 1)")
class NfeEmissaoServiceTest {

    @Mock EmpresaMapper empresaMapper;
    @Mock PedidoMapper pedidoMapper;
    @Mock br.com.borurio.app.mapper.PedidoItemMapper pedidoItemMapper;
    @Mock NfeSequenciaService sequenciaService;
    @Mock NfeEmissaoMapper nfeEmissaoMapper;
    @Mock br.com.borurio.fiscal.mapper.NfeDocumentoMapper nfeDocumentoMapper;
    @Mock EstoqueService estoqueService;

    NfeEmissaoService service;

    private static final String CNPJ = "22418179000134";
    private static final Long PEDIDO_ID = 99L;

    @BeforeEach
    void setUp() {
        service = new NfeEmissaoService(empresaMapper, pedidoMapper, pedidoItemMapper, sequenciaService,
                nfeEmissaoMapper, nfeDocumentoMapper, estoqueService, new SefazReconciliacaoProperties());
    }

    private Empresa empresa(Long id, String serie) {
        Empresa e = new Empresa();
        e.setId(id);
        e.setSerieNfePadrao(serie);
        return e;
    }

    private NfeSequencia sequencia(int ultimoNumero, Long emissaoAtivaId) {
        NfeSequencia seq = new NfeSequencia();
        seq.setCnpjEmitente(CNPJ);
        seq.setSerie("1");
        seq.setUltimoNumero(ultimoNumero);
        seq.setEmissaoAtivaId(emissaoAtivaId);
        return seq;
    }

    private NfeEmissao emissao(Long id, Long pedidoId, String estado, int numero) {
        NfeEmissao e = new NfeEmissao();
        e.setId(id);
        e.setPedidoId(pedidoId);
        e.setCnpjEmitente(CNPJ);
        e.setSerie("1");
        e.setNumeroNfe(numero);
        e.setEstado(estado);
        e.setTentativas(1);
        return e;
    }

    // -------------------------------------------------------------------------
    // abrirCiclo — gate livre
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Gate livre: abre ciclo novo com o próximo número candidato, sem persistir o consumo ainda")
    void abrirCiclo_gateLivre_abreCicloNovo() {
        when(empresaMapper.buscarPorCnpjParaAtualizar(CNPJ)).thenReturn(empresa(8L, "1"));
        when(sequenciaService.buscarOuCriarParaAtualizar(CNPJ, "1")).thenReturn(sequencia(4, null));
        doAnswer(inv -> {
            NfeEmissao e = inv.getArgument(0);
            e.setId(501L);
            return 1;
        }).when(nfeEmissaoMapper).inserir(any(NfeEmissao.class));

        AberturaCicloResultado resultado = service.abrirCiclo(PEDIDO_ID, CNPJ);

        assertEquals(TipoAberturaCiclo.NOVA_ABERTURA, resultado.tipo());
        assertEquals(5, resultado.emissao().getNumeroNfe(), "candidato = ultimoNumero(4) + 1");
        assertEquals(NfeEmissao.Estados.RESERVADO, resultado.emissao().getEstado());
        verify(sequenciaService).ocuparGate(CNPJ, "1", 501L);
        verify(pedidoMapper).atualizarSerieReservada(PEDIDO_ID, "1");
        // Gate 1: número só é fiscalmente consumido na resolução terminal, nunca na abertura.
        verify(sequenciaService, never()).consumirNumero(any(), any(), anyInt());
    }

    @Test
    @DisplayName("Empresa não encontrada lança COMPANY_NOT_FOUND sem tocar a sequência")
    void abrirCiclo_empresaNaoEncontrada_lancaCompanyNotFound() {
        when(empresaMapper.buscarPorCnpjParaAtualizar(CNPJ)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.abrirCiclo(PEDIDO_ID, CNPJ));

        assertEquals("COMPANY_NOT_FOUND", ex.getErrorCode());
        verifyNoInteractions(sequenciaService, nfeEmissaoMapper, pedidoMapper);
    }

    // -------------------------------------------------------------------------
    // abrirCiclo — gate ocupado por OUTRO pedido (correção central da revisão)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Gate ocupado por outro pedido lança EMISSAO_EM_ANDAMENTO_NA_SERIE, mesmo com o ciclo em AGUARDANDO_CORRECAO")
    void abrirCiclo_gateOcupadoPorOutroPedidoEmAguardandoCorrecao_lancaEmissaoEmAndamentoNaSerie() {
        // Este é EXATAMENTE o cenário que a v1 do plano deixava passar: A001 rejeitado não
        // deveria liberar o número 5 para A002 usar.
        when(empresaMapper.buscarPorCnpjParaAtualizar(CNPJ)).thenReturn(empresa(8L, "1"));
        when(sequenciaService.buscarOuCriarParaAtualizar(CNPJ, "1")).thenReturn(sequencia(4, 501L));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L))
                .thenReturn(emissao(501L, 1L /* outro pedido, não 99L */, NfeEmissao.Estados.AGUARDANDO_CORRECAO, 5));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.abrirCiclo(PEDIDO_ID, CNPJ));

        assertEquals("EMISSAO_EM_ANDAMENTO_NA_SERIE", ex.getErrorCode());
        assertTrue(ex.isRetryable());
        verify(sequenciaService, never()).ocuparGate(any(), any(), any());
        verify(nfeEmissaoMapper, never()).inserir(any());
    }

    @Test
    @DisplayName("Gate ocupado por outro pedido em TRANSMITIDO também lança EMISSAO_EM_ANDAMENTO_NA_SERIE")
    void abrirCiclo_gateOcupadoPorOutroPedidoEmTransmitido_lancaEmissaoEmAndamentoNaSerie() {
        when(empresaMapper.buscarPorCnpjParaAtualizar(CNPJ)).thenReturn(empresa(8L, "1"));
        when(sequenciaService.buscarOuCriarParaAtualizar(CNPJ, "1")).thenReturn(sequencia(4, 501L));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L))
                .thenReturn(emissao(501L, 1L, NfeEmissao.Estados.TRANSMITIDO, 5));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.abrirCiclo(PEDIDO_ID, CNPJ));

        assertEquals("EMISSAO_EM_ANDAMENTO_NA_SERIE", ex.getErrorCode());
    }

    // -------------------------------------------------------------------------
    // abrirCiclo — retomada pelo MESMO pedido
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Mesmo pedido com ciclo em RESERVADO retoma sem tocar a sequência (crash antes de transmitir)")
    void abrirCiclo_mesmoPedidoEmReservado_retomaSemGerarNumeroNovo() {
        when(empresaMapper.buscarPorCnpjParaAtualizar(CNPJ)).thenReturn(empresa(8L, "1"));
        when(sequenciaService.buscarOuCriarParaAtualizar(CNPJ, "1")).thenReturn(sequencia(4, 501L));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L))
                .thenReturn(emissao(501L, PEDIDO_ID, NfeEmissao.Estados.RESERVADO, 5));

        AberturaCicloResultado resultado = service.abrirCiclo(PEDIDO_ID, CNPJ);

        assertEquals(TipoAberturaCiclo.RETOMADA_RESERVADO, resultado.tipo());
        assertEquals(5, resultado.emissao().getNumeroNfe());
        verify(nfeEmissaoMapper, never()).inserir(any());
        verify(nfeEmissaoMapper, never()).retomarComoReservado(any());
        verify(sequenciaService, never()).ocuparGate(any(), any(), any());
        verify(pedidoMapper).atualizarSerieReservada(PEDIDO_ID, "1");
    }

    @Test
    @DisplayName("Mesmo pedido com ciclo em AGUARDANDO_CORRECAO retoma reaproveitando o mesmo número")
    void abrirCiclo_mesmoPedidoEmAguardandoCorrecao_retomaReaproveitandoNumero() {
        when(empresaMapper.buscarPorCnpjParaAtualizar(CNPJ)).thenReturn(empresa(8L, "1"));
        when(sequenciaService.buscarOuCriarParaAtualizar(CNPJ, "1")).thenReturn(sequencia(4, 501L));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L))
                .thenReturn(emissao(501L, PEDIDO_ID, NfeEmissao.Estados.AGUARDANDO_CORRECAO, 5));

        AberturaCicloResultado resultado = service.abrirCiclo(PEDIDO_ID, CNPJ);

        assertEquals(TipoAberturaCiclo.RETOMADA_AGUARDANDO_CORRECAO, resultado.tipo());
        assertEquals(5, resultado.emissao().getNumeroNfe(), "reaproveita o MESMO número — nunca pula pra 6");
        verify(nfeEmissaoMapper).retomarComoReservado(501L);
        verify(nfeEmissaoMapper, never()).inserir(any());
        verify(sequenciaService, never()).consumirNumero(any(), any(), anyInt());
    }

    @Test
    @DisplayName("Mesmo pedido com ciclo em TRANSMITIDO/PENDENTE_CONFIRMACAO é bloqueado — nunca gera chave nova")
    void abrirCiclo_mesmoPedidoEmPendenteConfirmacao_lancaEmissaoAguardandoReconciliacao() {
        when(empresaMapper.buscarPorCnpjParaAtualizar(CNPJ)).thenReturn(empresa(8L, "1"));
        when(sequenciaService.buscarOuCriarParaAtualizar(CNPJ, "1")).thenReturn(sequencia(4, 501L));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L))
                .thenReturn(emissao(501L, PEDIDO_ID, NfeEmissao.Estados.PENDENTE_CONFIRMACAO, 5));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.abrirCiclo(PEDIDO_ID, CNPJ));

        assertEquals("EMISSAO_AGUARDANDO_RECONCILIACAO", ex.getErrorCode());
        assertTrue(ex.isRetryable());
        verify(nfeEmissaoMapper, never()).inserir(any());
        verify(nfeEmissaoMapper, never()).retomarComoReservado(any());
    }

    // -------------------------------------------------------------------------
    // resolverCiclo — só AUTORIZADO/DENEGADO liberam o gate e avançam a sequência
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AUTORIZADO consome o número e libera o gate")
    void resolverCiclo_autorizado_consomeNumeroELiberaGate() {
        NfeEmissao preRead = emissao(501L, PEDIDO_ID, NfeEmissao.Estados.TRANSMITIDO, 5);
        NfeEmissao ativa = emissao(501L, PEDIDO_ID, NfeEmissao.Estados.TRANSMITIDO, 5);
        when(nfeEmissaoMapper.buscarPorId(501L)).thenReturn(preRead);
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "1")).thenReturn(sequencia(4, 501L));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L)).thenReturn(ativa);

        service.resolverCiclo(501L, NfeEmissao.Estados.AUTORIZADO, 100, null, "135240000012345");

        verify(sequenciaService).consumirNumero(CNPJ, "1", 5);
        verify(sequenciaService).liberarGate(CNPJ, "1");
        ArgumentCaptor<NfeEmissao> captor = ArgumentCaptor.forClass(NfeEmissao.class);
        verify(nfeEmissaoMapper).atualizarResultado(captor.capture());
        assertEquals(NfeEmissao.Estados.AUTORIZADO, captor.getValue().getEstado());
        assertNotNull(captor.getValue().getResolvidoEm(), "estado terminal precisa marcar resolvidoEm");
    }

    @Test
    @DisplayName("DENEGADO também consome o número e libera o gate — mas fica distinto de AUTORIZADO no estado gravado")
    void resolverCiclo_denegado_consomeNumeroELiberaGate() {
        NfeEmissao preRead = emissao(501L, PEDIDO_ID, NfeEmissao.Estados.TRANSMITIDO, 5);
        NfeEmissao ativa = emissao(501L, PEDIDO_ID, NfeEmissao.Estados.TRANSMITIDO, 5);
        when(nfeEmissaoMapper.buscarPorId(501L)).thenReturn(preRead);
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "1")).thenReturn(sequencia(4, 501L));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L)).thenReturn(ativa);

        service.resolverCiclo(501L, NfeEmissao.Estados.DENEGADO, 110, "Uso Denegado", null);

        verify(sequenciaService).consumirNumero(CNPJ, "1", 5);
        verify(sequenciaService).liberarGate(CNPJ, "1");
        ArgumentCaptor<NfeEmissao> captor = ArgumentCaptor.forClass(NfeEmissao.class);
        verify(nfeEmissaoMapper).atualizarResultado(captor.capture());
        assertEquals(NfeEmissao.Estados.DENEGADO, captor.getValue().getEstado());
    }

    @Test
    @DisplayName("AGUARDANDO_CORRECAO NÃO consome o número nem libera o gate — correção central da revisão")
    void resolverCiclo_aguardandoCorrecao_naoAvancaNaoLiberaGate() {
        NfeEmissao ativa = emissao(501L, PEDIDO_ID, NfeEmissao.Estados.TRANSMITIDO, 5);
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L)).thenReturn(ativa);

        service.resolverCiclo(501L, NfeEmissao.Estados.AGUARDANDO_CORRECAO, 225, "Erro de schema", null);

        verify(sequenciaService, never()).consumirNumero(any(), any(), anyInt());
        verify(sequenciaService, never()).liberarGate(any(), any());
        ArgumentCaptor<NfeEmissao> captor = ArgumentCaptor.forClass(NfeEmissao.class);
        verify(nfeEmissaoMapper).atualizarResultado(captor.capture());
        assertNull(captor.getValue().getResolvidoEm(), "não é terminal — resolvidoEm continua null");
        // P0-3: transição não-terminal nunca precisa tocar nfe_sequencia — nem pré-leitura,
        // nem lock, escopo de lock minimizado exatamente ao caso que realmente precisa dele.
        verify(nfeEmissaoMapper, never()).buscarPorId(any());
        verify(sequenciaService, never()).buscarSeExistirParaAtualizar(any(), any());
    }

    @Test
    @DisplayName("PENDENTE_CONFIRMACAO NÃO consome o número nem libera o gate")
    void resolverCiclo_pendenteConfirmacao_naoAvancaNaoLiberaGate() {
        NfeEmissao ativa = emissao(501L, PEDIDO_ID, NfeEmissao.Estados.TRANSMITIDO, 5);
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L)).thenReturn(ativa);

        service.resolverCiclo(501L, NfeEmissao.Estados.PENDENTE_CONFIRMACAO, null, null, null);

        verify(sequenciaService, never()).consumirNumero(any(), any(), anyInt());
        verify(sequenciaService, never()).liberarGate(any(), any());
        verify(nfeEmissaoMapper, never()).buscarPorId(any());
        verify(sequenciaService, never()).buscarSeExistirParaAtualizar(any(), any());
    }

    @Test
    @DisplayName("Ciclo já terminal: chamada repetida é no-op (idempotência para reconciliação futura)")
    void resolverCiclo_jaTerminal_naoReaplicaEfeito() {
        NfeEmissao preRead = emissao(501L, PEDIDO_ID, NfeEmissao.Estados.AUTORIZADO, 5);
        NfeEmissao jaAutorizada = emissao(501L, PEDIDO_ID, NfeEmissao.Estados.AUTORIZADO, 5);
        when(nfeEmissaoMapper.buscarPorId(501L)).thenReturn(preRead);
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "1")).thenReturn(sequencia(4, null));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L)).thenReturn(jaAutorizada);

        service.resolverCiclo(501L, NfeEmissao.Estados.AUTORIZADO, 100, null, "prot");

        verify(sequenciaService, never()).consumirNumero(any(), any(), anyInt());
        verify(sequenciaService, never()).liberarGate(any(), any());
        verify(nfeEmissaoMapper, never()).atualizarResultado(any());
    }

    // -------------------------------------------------------------------------
    // resolverCiclo — P0-3 (07-08-2026, hardening pós-banca): ordem de lock e TOCTOU
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("P0-3: transição terminal trava nfe_sequencia ANTES de nfe_emissao (ordem canônica)")
    void resolverCiclo_terminal_travaSequenciaAntesDeEmissao() {
        NfeEmissao preRead = emissao(501L, PEDIDO_ID, NfeEmissao.Estados.TRANSMITIDO, 5);
        NfeEmissao ativa = emissao(501L, PEDIDO_ID, NfeEmissao.Estados.TRANSMITIDO, 5);
        when(nfeEmissaoMapper.buscarPorId(501L)).thenReturn(preRead);
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "1")).thenReturn(sequencia(4, 501L));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L)).thenReturn(ativa);

        service.resolverCiclo(501L, NfeEmissao.Estados.AUTORIZADO, 100, null, "prot");

        InOrder ordem = inOrder(nfeEmissaoMapper, sequenciaService);
        ordem.verify(nfeEmissaoMapper).buscarPorId(501L); // pré-leitura NÃO bloqueante, só p/ localizar cnpj/série
        ordem.verify(sequenciaService).buscarSeExistirParaAtualizar(CNPJ, "1"); // FOR UPDATE nfe_sequencia
        ordem.verify(nfeEmissaoMapper).buscarPorIdParaAtualizar(501L); // FOR UPDATE nfe_emissao — SÓ depois
        ordem.verify(sequenciaService).consumirNumero(CNPJ, "1", 5);
        ordem.verify(sequenciaService).liberarGate(CNPJ, "1");
    }

    @Test
    @DisplayName("P0-3 TOCTOU: pré-leitura desatualizada não decide a transição — decisão usa o estado travado")
    void resolverCiclo_toctou_preLeituraDesatualizada_decisaoUsaEstadoTravado() {
        // Cenário real de corrida: no instante da pré-leitura (sem lock) o ciclo ainda parecia
        // TRANSMITIDO; entre a pré-leitura e a aquisição dos locks, outra thread já resolveu o
        // mesmo ciclo para AUTORIZADO. Se a decisão usasse a pré-leitura, tentaria consumir/
        // liberar de novo. Usando o estado da linha JÁ TRAVADA, vira no-op idempotente.
        NfeEmissao preReadDesatualizada = emissao(501L, PEDIDO_ID, NfeEmissao.Estados.TRANSMITIDO, 5);
        NfeEmissao estadoRealTravado = emissao(501L, PEDIDO_ID, NfeEmissao.Estados.AUTORIZADO, 5);
        when(nfeEmissaoMapper.buscarPorId(501L)).thenReturn(preReadDesatualizada);
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "1")).thenReturn(sequencia(5, null));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L)).thenReturn(estadoRealTravado);

        service.resolverCiclo(501L, NfeEmissao.Estados.AUTORIZADO, 100, null, "prot");

        verify(sequenciaService, never()).consumirNumero(any(), any(), anyInt());
        verify(sequenciaService, never()).liberarGate(any(), any());
        verify(nfeEmissaoMapper, never()).atualizarResultado(any());
    }

    @Test
    @DisplayName("P0-3: gate inconsistente (emissaoAtivaId aponta para outro ciclo) falha e não consome/libera nada")
    void resolverCiclo_gateApontaParaOutroCiclo_falhaSemConsumirOuLiberar() {
        NfeEmissao preRead = emissao(501L, PEDIDO_ID, NfeEmissao.Estados.TRANSMITIDO, 5);
        NfeEmissao ativa = emissao(501L, PEDIDO_ID, NfeEmissao.Estados.TRANSMITIDO, 5);
        when(nfeEmissaoMapper.buscarPorId(501L)).thenReturn(preRead);
        // Gate da série aponta para um ciclo DIFERENTE (999L) — nunca deveria acontecer em fluxo
        // normal (só cobre corrupção de estado / bug futuro), mas resolverCiclo precisa recusar
        // explicitamente em vez de consumir número/liberar gate de um ciclo que não é o dono.
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "1")).thenReturn(sequencia(4, 999L));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L)).thenReturn(ativa);

        assertThrows(IllegalStateException.class,
                () -> service.resolverCiclo(501L, NfeEmissao.Estados.AUTORIZADO, 100, null, "prot"));

        verify(sequenciaService, never()).consumirNumero(any(), any(), anyInt());
        verify(sequenciaService, never()).liberarGate(any(), any());
        verify(nfeEmissaoMapper, never()).atualizarResultado(any());
    }

    @Test
    @DisplayName("P0-3: sequência não encontrada para a série da emissão falha e não consome/libera nada")
    void resolverCiclo_sequenciaNaoEncontrada_falhaSemConsumirOuLiberar() {
        NfeEmissao preRead = emissao(501L, PEDIDO_ID, NfeEmissao.Estados.TRANSMITIDO, 5);
        NfeEmissao ativa = emissao(501L, PEDIDO_ID, NfeEmissao.Estados.TRANSMITIDO, 5);
        when(nfeEmissaoMapper.buscarPorId(501L)).thenReturn(preRead);
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "1")).thenReturn(null);
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L)).thenReturn(ativa);

        assertThrows(IllegalStateException.class,
                () -> service.resolverCiclo(501L, NfeEmissao.Estados.AUTORIZADO, 100, null, "prot"));

        verify(sequenciaService, never()).consumirNumero(any(), any(), anyInt());
        verify(sequenciaService, never()).liberarGate(any(), any());
        verify(nfeEmissaoMapper, never()).atualizarResultado(any());
    }

    @Test
    @DisplayName("P0-3: pré-leitura não encontrada falha explicitamente antes de tentar travar a sequência")
    void resolverCiclo_preLeituraNaoEncontrada_falhaAntesDeTravarSequencia() {
        when(nfeEmissaoMapper.buscarPorId(501L)).thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> service.resolverCiclo(501L, NfeEmissao.Estados.AUTORIZADO, 100, null, "prot"));

        verify(sequenciaService, never()).buscarSeExistirParaAtualizar(any(), any());
        verify(nfeEmissaoMapper, never()).buscarPorIdParaAtualizar(any());
    }

    // -------------------------------------------------------------------------
    // Gate 3 (10-08-2026) — resolverCicloComEfeitos: finalização atômica com Pedido/Estoque
    // -------------------------------------------------------------------------

    private List<PedidoItem> itensPadrao() {
        PedidoItem item = new PedidoItem();
        item.setProdutoId(1L);
        item.setQuantidade(new BigDecimal("2"));
        return List.of(item);
    }

    @Test
    @DisplayName("resolverCicloComEfeitos AUTORIZADO: consome número, libera gate, atualiza Pedido e baixa estoque na mesma chamada")
    void resolverCicloComEfeitos_autorizado_aplicaTudoJunto() {
        NfeEmissao preRead = emissao(501L, PEDIDO_ID, NfeEmissao.Estados.TRANSMITIDO, 5);
        NfeEmissao ativa = emissao(501L, PEDIDO_ID, NfeEmissao.Estados.TRANSMITIDO, 5);
        when(nfeEmissaoMapper.buscarPorId(501L)).thenReturn(preRead);
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "1")).thenReturn(sequencia(4, 501L));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L)).thenReturn(ativa);

        service.resolverCicloComEfeitos(501L, NfeEmissao.Estados.AUTORIZADO, 100, null, "prot",
                PEDIDO_ID, "AUTORIZADO", "chave123", true, itensPadrao(), 10L, "sistema");

        verify(sequenciaService).consumirNumero(CNPJ, "1", 5);
        verify(sequenciaService).liberarGate(CNPJ, "1");
        verify(pedidoMapper).atualizarStatus(PEDIDO_ID, "AUTORIZADO", "chave123");
        verify(estoqueService).baixaDefinitivaItens(itensPadrao(), 10L, PEDIDO_ID, "sistema");
        verify(estoqueService, never()).desfazerReservaItens(any(), any(), any(), any());
    }

    @Test
    @DisplayName("resolverCicloComEfeitos AGUARDANDO_CORRECAO: não consome número, atualiza Pedido e desfaz reserva")
    void resolverCicloComEfeitos_aguardandoCorrecao_desfazReserva() {
        NfeEmissao ativa = emissao(501L, PEDIDO_ID, NfeEmissao.Estados.TRANSMITIDO, 5);
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L)).thenReturn(ativa);

        service.resolverCicloComEfeitos(501L, NfeEmissao.Estados.AGUARDANDO_CORRECAO, 225, "Erro de schema", null,
                PEDIDO_ID, "REJEITADO", null, true, itensPadrao(), 10L, "sistema");

        verify(sequenciaService, never()).consumirNumero(any(), any(), anyInt());
        verify(sequenciaService, never()).liberarGate(any(), any());
        verify(pedidoMapper).atualizarStatus(PEDIDO_ID, "REJEITADO", null);
        verify(estoqueService).desfazerReservaItens(itensPadrao(), 10L, PEDIDO_ID, "sistema");
        verify(estoqueService, never()).baixaDefinitivaItens(any(), any(), any(), any());
    }

    @Test
    @DisplayName("resolverCicloComEfeitos NUMERO_OCUPADO: consome número (nunca reutilizado), libera gate, desfaz reserva, Pedido=ERRO")
    void resolverCicloComEfeitos_numeroOcupado_consomeELiberaGateDesfazReserva() {
        NfeEmissao preRead = emissao(501L, PEDIDO_ID, NfeEmissao.Estados.PENDENTE_CONFIRMACAO, 5);
        NfeEmissao ativa = emissao(501L, PEDIDO_ID, NfeEmissao.Estados.PENDENTE_CONFIRMACAO, 5);
        when(nfeEmissaoMapper.buscarPorId(501L)).thenReturn(preRead);
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "1")).thenReturn(sequencia(4, 501L));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L)).thenReturn(ativa);

        service.resolverCicloComEfeitos(501L, NfeEmissao.Estados.NUMERO_OCUPADO, 205, "NF-e já denegada", null,
                PEDIDO_ID, "ERRO", "chave123", true, itensPadrao(), 10L, "sistema");

        verify(sequenciaService).consumirNumero(CNPJ, "1", 5);
        verify(sequenciaService).liberarGate(CNPJ, "1");
        verify(pedidoMapper).atualizarStatus(PEDIDO_ID, "ERRO", "chave123");
        verify(estoqueService).desfazerReservaItens(itensPadrao(), 10L, PEDIDO_ID, "sistema");
    }

    @Test
    @DisplayName("resolverCicloComEfeitos PENDENTE_CONFIRMACAO: atualiza Pedido para AGUARDANDO, nunca toca estoque")
    void resolverCicloComEfeitos_pendenteConfirmacao_naoTocaEstoque() {
        NfeEmissao ativa = emissao(501L, PEDIDO_ID, NfeEmissao.Estados.TRANSMITIDO, 5);
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L)).thenReturn(ativa);

        service.resolverCicloComEfeitos(501L, NfeEmissao.Estados.PENDENTE_CONFIRMACAO, 103, null, null,
                PEDIDO_ID, "AGUARDANDO", "chave123", true, itensPadrao(), 10L, "sistema");

        verify(pedidoMapper).atualizarStatus(PEDIDO_ID, "AGUARDANDO", "chave123");
        verify(estoqueService, never()).baixaDefinitivaItens(any(), any(), any(), any());
        verify(estoqueService, never()).desfazerReservaItens(any(), any(), any(), any());
    }

    @Test
    @DisplayName("resolverCicloComEfeitos com controlaEstoque=false: resolve o ciclo e atualiza Pedido, mas nunca toca estoque")
    void resolverCicloComEfeitos_controlaEstoqueFalse_nuncaTocaEstoque() {
        NfeEmissao preRead = emissao(501L, PEDIDO_ID, NfeEmissao.Estados.TRANSMITIDO, 5);
        NfeEmissao ativa = emissao(501L, PEDIDO_ID, NfeEmissao.Estados.TRANSMITIDO, 5);
        when(nfeEmissaoMapper.buscarPorId(501L)).thenReturn(preRead);
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "1")).thenReturn(sequencia(4, 501L));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L)).thenReturn(ativa);

        service.resolverCicloComEfeitos(501L, NfeEmissao.Estados.AUTORIZADO, 100, null, "prot",
                PEDIDO_ID, "AUTORIZADO", "chave123", false, itensPadrao(), 10L, "sistema");

        verify(pedidoMapper).atualizarStatus(PEDIDO_ID, "AUTORIZADO", "chave123");
        verifyNoInteractions(estoqueService);
    }

    @Test
    @DisplayName("Banca do Gate Estoque (13-08-2026): PENDENTE_CONFIRMACAO com controlaEstoque=false — nenhuma reserva, baixa, desfazimento ou estorno")
    void resolverCicloComEfeitos_pendenteConfirmacao_controlaEstoqueFalse_naoTocaEstoque() {
        NfeEmissao ativa = emissao(501L, PEDIDO_ID, NfeEmissao.Estados.TRANSMITIDO, 5);
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L)).thenReturn(ativa);

        service.resolverCicloComEfeitos(501L, NfeEmissao.Estados.PENDENTE_CONFIRMACAO, 103, null, null,
                PEDIDO_ID, "AGUARDANDO", "chave123", false, itensPadrao(), 10L, "sistema");

        verify(pedidoMapper).atualizarStatus(PEDIDO_ID, "AGUARDANDO", "chave123");
        // Não é inferência: PENDENTE_CONFIRMACAO já não tocaria estoque nem com controlaEstoque=
        // true (não é terminal — ver resolverCicloComEfeitos_pendenteConfirmacao_naoTocaEstoque),
        // mas o pedido explícito da banca é uma assertion dedicada para a combinação com false,
        // sem depender dessa dedução.
        verifyNoInteractions(estoqueService);
    }

    @Test
    @DisplayName("Exactly-once: ciclo já terminal -- resolverCicloComEfeitos não toca Pedido nem Estoque de novo")
    void resolverCicloComEfeitos_cicloJaTerminal_naoReaplicaEfeitoOperacional() {
        // Simula uma segunda chamada (retry, reconciliação concorrente) sobre um ciclo que outra
        // chamada já resolveu — a proteção central do P0-A: efeito fiscal e efeito operacional
        // nunca podem ficar dessincronizados, e a idempotência de aplicarNovoEstado já impede
        // qualquer efeito de rodar de novo quando o estado já está travado como terminal.
        NfeEmissao preRead = emissao(501L, PEDIDO_ID, NfeEmissao.Estados.AUTORIZADO, 5);
        NfeEmissao jaAutorizada = emissao(501L, PEDIDO_ID, NfeEmissao.Estados.AUTORIZADO, 5);
        when(nfeEmissaoMapper.buscarPorId(501L)).thenReturn(preRead);
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "1")).thenReturn(sequencia(5, null));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L)).thenReturn(jaAutorizada);

        service.resolverCicloComEfeitos(501L, NfeEmissao.Estados.AUTORIZADO, 100, null, "prot",
                PEDIDO_ID, "AUTORIZADO", "chave123", true, itensPadrao(), 10L, "sistema");

        verify(sequenciaService, never()).consumirNumero(any(), any(), anyInt());
        verify(sequenciaService, never()).liberarGate(any(), any());
        verify(nfeEmissaoMapper, never()).atualizarResultado(any());
        verifyNoInteractions(pedidoMapper, estoqueService);
    }

    // -------------------------------------------------------------------------
    // Gate 3 (10-08-2026) — tentarAdquirirJanelaConsulta: claim atômico do backoff
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Claim vencido (UPDATE afetou 1 linha) -- retorna true e delega os parâmetros de backoff configurados")
    void tentarAdquirirJanelaConsulta_claimVencido_retornaTrue() {
        when(nfeEmissaoMapper.tentarAdquirirJanelaConsulta(eq(501L), any(LocalDateTime.class), eq(30), eq(2.0), eq(600)))
                .thenReturn(1);

        boolean venceu = service.tentarAdquirirJanelaConsulta(501L);

        assertTrue(venceu);
    }

    @Test
    @DisplayName("Claim não vencido (UPDATE afetou 0 linhas) -- retorna false")
    void tentarAdquirirJanelaConsulta_claimNaoVencido_retornaFalse() {
        when(nfeEmissaoMapper.tentarAdquirirJanelaConsulta(eq(501L), any(LocalDateTime.class), anyInt(), anyDouble(), anyInt()))
                .thenReturn(0);

        boolean venceu = service.tentarAdquirirJanelaConsulta(501L);

        assertFalse(venceu);
    }

    // -------------------------------------------------------------------------
    // Fase 1 SVC (17-08-2026) — branch late-NORMAL (emissao_origem_id como verdade durável).
    // Mockito devolve null por padrão para buscarPorOrigemIdParaAtualizar em todos os testes
    // ACIMA desta seção -- é exatamente essa a prova de que o comportamento pré-Fase-1 continua
    // 100% inalterado quando não há substituição (filha == null).
    // -------------------------------------------------------------------------

    private NfeEmissao gravadaSubstituida(String estado, Integer cstat, String xmotivo, String nprot) {
        NfeEmissao e = emissao(501L, PEDIDO_ID, estado, 5);
        e.setCstat(cstat);
        e.setXmotivo(xmotivo);
        e.setNprot(nprot);
        return e;
    }

    @Test
    @DisplayName("Fast-path (banca 17-08-2026, achado de gap lock confirmado): gate ainda na própria emissão -- nunca consulta emissao_origem_id, comportamento idêntico ao pré-Fase-1")
    void resolverCicloComEfeitos_gateAindaNaPropriaEmissao_fastPathPulaConsultaOrigem() {
        NfeEmissao preRead = emissao(501L, PEDIDO_ID, NfeEmissao.Estados.TRANSMITIDO, 5);
        NfeEmissao ativa = emissao(501L, PEDIDO_ID, NfeEmissao.Estados.TRANSMITIDO, 5);
        when(nfeEmissaoMapper.buscarPorId(501L)).thenReturn(preRead);
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "1")).thenReturn(sequencia(4, 501L));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L)).thenReturn(ativa);

        service.resolverCicloComEfeitos(501L, NfeEmissao.Estados.AUTORIZADO, 100, null, "prot",
                PEDIDO_ID, "AUTORIZADO", "chave123", true, itensPadrao(), 10L, "sistema");

        // Prova do fast-path: com o gate ainda apontando pra esta emissão (sob o mesmo lock de
        // nfe_sequencia), uma substituição commitada é impossível -- a busca que toma o gap lock
        // em uk_nfe_emissao_origem nunca é chamada (ver evidência real em
        // NfeContingenciaGapLockReproducaoRealMySqlIT#depoisDoFastPath...).
        verify(nfeEmissaoMapper, never()).buscarPorOrigemIdParaAtualizar(any());
        verify(nfeEmissaoMapper, never()).aplicarEvidenciaSubstituida(any(), any(), any(), any(), any());
        verify(sequenciaService).consumirNumero(CNPJ, "1", 5);
        verify(sequenciaService).liberarGate(CNPJ, "1");
        verify(pedidoMapper).atualizarStatus(PEDIDO_ID, "AUTORIZADO", "chave123");
        verify(estoqueService).baixaDefinitivaItens(itensPadrao(), 10L, PEDIDO_ID, "sistema");
    }

    @Test
    @DisplayName("Gate NÃO aponta mais pra esta emissão, mas sem filha (corrupção real): fast-path não se aplica, consulta emissao_origem_id normalmente e falha explícito")
    void resolverCicloComEfeitos_gateNaoCorresponde_semFilha_consultaOrigemEFalha() {
        NfeEmissao preRead = emissao(501L, PEDIDO_ID, NfeEmissao.Estados.TRANSMITIDO, 5);
        NfeEmissao ativa = emissao(501L, PEDIDO_ID, NfeEmissao.Estados.TRANSMITIDO, 5);
        when(nfeEmissaoMapper.buscarPorId(501L)).thenReturn(preRead);
        // Gate aponta pra outro ciclo (999L) -- gateAindaNestaEmissao é false, então o fast-path
        // não se aplica e a consulta por emissao_origem_id roda normalmente.
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "1")).thenReturn(sequencia(4, 999L));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L)).thenReturn(ativa);
        when(nfeEmissaoMapper.buscarPorOrigemIdParaAtualizar(501L)).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> service.resolverCicloComEfeitos(501L, NfeEmissao.Estados.AUTORIZADO, 100, null, "prot",
                PEDIDO_ID, "AUTORIZADO", "chave123", true, itensPadrao(), 10L, "sistema"));

        verify(nfeEmissaoMapper).buscarPorOrigemIdParaAtualizar(501L);
        verifyNoInteractions(pedidoMapper, estoqueService);
    }

    @Test
    @DisplayName("NORMAL substituída, ainda TRANSMITIDO: 1ª evidência aplicada — zero Pedido/Estoque/consumirNumero/liberarGate")
    void resolverCicloComEfeitos_normalSubstituidaAindaTransmitido_aplicaEvidenciaSemEfeitos() {
        NfeEmissao preRead = emissao(501L, PEDIDO_ID, NfeEmissao.Estados.TRANSMITIDO, 5);
        NfeEmissao ativa = emissao(501L, PEDIDO_ID, NfeEmissao.Estados.TRANSMITIDO, 5);
        NfeEmissao filha = emissao(900L, PEDIDO_ID, NfeEmissao.Estados.RESERVADO, 6);
        when(nfeEmissaoMapper.buscarPorId(501L)).thenReturn(preRead);
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L)).thenReturn(ativa);
        when(nfeEmissaoMapper.buscarPorOrigemIdParaAtualizar(501L)).thenReturn(filha);
        when(nfeEmissaoMapper.aplicarEvidenciaSubstituida(501L, NfeEmissao.Estados.AUTORIZADO, 100, null, "prot-tardio"))
                .thenReturn(1);

        service.resolverCicloComEfeitos(501L, NfeEmissao.Estados.AUTORIZADO, 100, null, "prot-tardio",
                PEDIDO_ID, "AUTORIZADO", "chaveNormal", true, itensPadrao(), 10L, "sistema");

        verify(nfeEmissaoMapper).aplicarEvidenciaSubstituida(501L, NfeEmissao.Estados.AUTORIZADO, 100, null, "prot-tardio");
        verify(nfeEmissaoMapper, never()).atualizarResultado(any());
        verify(sequenciaService, never()).consumirNumero(any(), any(), anyInt());
        verify(sequenciaService, never()).liberarGate(any(), any());
        verifyNoInteractions(pedidoMapper, estoqueService);
    }

    @Test
    @DisplayName("Idempotência 1/4: tupla fiscal idêntica repetida — no-op silencioso, zero efeitos")
    void resolverCicloComEfeitos_evidenciaJaRegistrada_tuplaIdentica_noOp() {
        NfeEmissao gravada = gravadaSubstituida(NfeEmissao.Estados.AUTORIZADO, 100, null, "PROT1");
        NfeEmissao filha = emissao(900L, PEDIDO_ID, NfeEmissao.Estados.RESERVADO, 6);
        when(nfeEmissaoMapper.buscarPorId(501L)).thenReturn(gravada);
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L)).thenReturn(gravada);
        when(nfeEmissaoMapper.buscarPorOrigemIdParaAtualizar(501L)).thenReturn(filha);
        when(nfeEmissaoMapper.aplicarEvidenciaSubstituida(501L, NfeEmissao.Estados.AUTORIZADO, 100, null, "PROT1"))
                .thenReturn(0);

        service.resolverCicloComEfeitos(501L, NfeEmissao.Estados.AUTORIZADO, 100, null, "PROT1",
                PEDIDO_ID, "AUTORIZADO", "chaveNormal", true, itensPadrao(), 10L, "sistema");

        // Prova a reordenação (ajuste 3, v4): mesmo com emissao.estado já AUTORIZADO (terminal),
        // a chamada ainda passou pelo caminho de evidência (aplicarEvidenciaSubstituida foi
        // chamado) em vez de cair direto no short-circuit antigo de isTerminal.
        verify(nfeEmissaoMapper).aplicarEvidenciaSubstituida(501L, NfeEmissao.Estados.AUTORIZADO, 100, null, "PROT1");
        verify(nfeEmissaoMapper, never()).atualizarResultado(any());
        verify(sequenciaService, never()).consumirNumero(any(), any(), anyInt());
        verify(sequenciaService, never()).liberarGate(any(), any());
        verifyNoInteractions(pedidoMapper, estoqueService);
    }

    @Test
    @DisplayName("Idempotência 2/4: mesmo estado/cStat, nProt divergente — não sobrescreve, zero efeitos")
    void resolverCicloComEfeitos_evidenciaJaRegistrada_nProtDivergente_naoSobrescreve() {
        NfeEmissao gravada = gravadaSubstituida(NfeEmissao.Estados.AUTORIZADO, 100, null, "PROT_ANTIGO");
        NfeEmissao filha = emissao(900L, PEDIDO_ID, NfeEmissao.Estados.RESERVADO, 6);
        when(nfeEmissaoMapper.buscarPorId(501L)).thenReturn(gravada);
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L)).thenReturn(gravada);
        when(nfeEmissaoMapper.buscarPorOrigemIdParaAtualizar(501L)).thenReturn(filha);
        when(nfeEmissaoMapper.aplicarEvidenciaSubstituida(501L, NfeEmissao.Estados.AUTORIZADO, 100, null, "PROT_NOVO"))
                .thenReturn(0);

        service.resolverCicloComEfeitos(501L, NfeEmissao.Estados.AUTORIZADO, 100, null, "PROT_NOVO",
                PEDIDO_ID, "AUTORIZADO", "chaveNormal", true, itensPadrao(), 10L, "sistema");

        verify(nfeEmissaoMapper, never()).atualizarResultado(any());
        verify(sequenciaService, never()).consumirNumero(any(), any(), anyInt());
        verify(sequenciaService, never()).liberarGate(any(), any());
        verifyNoInteractions(pedidoMapper, estoqueService);
    }

    @Test
    @DisplayName("Idempotência 3/4: mesmo estado, cStat divergente — não sobrescreve, zero efeitos")
    void resolverCicloComEfeitos_evidenciaJaRegistrada_cStatDivergente_naoSobrescreve() {
        NfeEmissao gravada = gravadaSubstituida(NfeEmissao.Estados.AUTORIZADO, 100, null, "PROT1");
        NfeEmissao filha = emissao(900L, PEDIDO_ID, NfeEmissao.Estados.RESERVADO, 6);
        when(nfeEmissaoMapper.buscarPorId(501L)).thenReturn(gravada);
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L)).thenReturn(gravada);
        when(nfeEmissaoMapper.buscarPorOrigemIdParaAtualizar(501L)).thenReturn(filha);
        when(nfeEmissaoMapper.aplicarEvidenciaSubstituida(501L, NfeEmissao.Estados.AUTORIZADO, 150, null, "PROT1"))
                .thenReturn(0);

        service.resolverCicloComEfeitos(501L, NfeEmissao.Estados.AUTORIZADO, 150, null, "PROT1",
                PEDIDO_ID, "AUTORIZADO", "chaveNormal", true, itensPadrao(), 10L, "sistema");

        verify(nfeEmissaoMapper, never()).atualizarResultado(any());
        verify(sequenciaService, never()).consumirNumero(any(), any(), anyInt());
        verify(sequenciaService, never()).liberarGate(any(), any());
        verifyNoInteractions(pedidoMapper, estoqueService);
    }

    @Test
    @DisplayName("Idempotência 4/4: estado divergente — não sobrescreve, zero efeitos (sem usar DENEGADO)")
    void resolverCicloComEfeitos_evidenciaJaRegistrada_estadoDivergente_naoSobrescreve() {
        NfeEmissao gravada = gravadaSubstituida(NfeEmissao.Estados.AUTORIZADO, 100, null, "PROT1");
        NfeEmissao filha = emissao(900L, PEDIDO_ID, NfeEmissao.Estados.RESERVADO, 6);
        when(nfeEmissaoMapper.buscarPorId(501L)).thenReturn(gravada);
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L)).thenReturn(gravada);
        when(nfeEmissaoMapper.buscarPorOrigemIdParaAtualizar(501L)).thenReturn(filha);
        when(nfeEmissaoMapper.aplicarEvidenciaSubstituida(501L, NfeEmissao.Estados.NUMERO_OCUPADO, 205, "NF-e já denegada", null))
                .thenReturn(0);

        service.resolverCicloComEfeitos(501L, NfeEmissao.Estados.NUMERO_OCUPADO, 205, "NF-e já denegada", null,
                PEDIDO_ID, "ERRO", "chaveNormal", true, itensPadrao(), 10L, "sistema");

        verify(nfeEmissaoMapper, never()).atualizarResultado(any());
        verify(sequenciaService, never()).consumirNumero(any(), any(), anyInt());
        verify(sequenciaService, never()).liberarGate(any(), any());
        verifyNoInteractions(pedidoMapper, estoqueService);
    }

    // -------------------------------------------------------------------------
    // abandonarCiclo — recovery administrativo (02-09-2026), estado ABANDONADO.
    //
    // Modelo "gap" (revisão pós-incidente): encerra o ciclo, libera o gate E avança
    // nfe_sequencia.ultimo_numero até o numero_nfe deste ciclo — nunca além, nunca
    // regredindo. A linha ABANDONADA ocupa o slot (cnpj,modelo,serie,nNF) para sempre
    // via uk_nfe_emissao_numero, então o nNF NÃO volta a ser alocável. A chamada
    // idempotente ainda repara o contador se ele ficou atrás — nunca é no-op cego.
    // -------------------------------------------------------------------------

    private NfeEmissao emissaoComEvidencia(Long id, Long pedidoId, String estado, int numero,
                                            Integer cstat, String xmotivo, String nprot) {
        NfeEmissao e = emissao(id, pedidoId, estado, numero);
        e.setCstat(cstat);
        e.setXmotivo(xmotivo);
        e.setNprot(nprot);
        return e;
    }

    @Test
    @DisplayName("abandonarCiclo: AGUARDANDO_CORRECAO nNF=1 ultimo_numero=0 → ABANDONADO, gate liberado, "
            + "ultimo_numero avançado até 1 (modelo gap), cStat/xMotivo preservados")
    void abandonarCiclo_aguardandoCorrecao_encerraCicloLiberaGateAvancaSequencia() {
        NfeEmissao alvo = emissaoComEvidencia(2L, 67L, NfeEmissao.Estados.AGUARDANDO_CORRECAO, 1,
                225, "Rejeição: Falha no Schema XML do lote de NFe", null);
        when(nfeEmissaoMapper.buscarPorId(2L)).thenReturn(alvo);
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "1")).thenReturn(sequencia(0, 2L));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(2L)).thenReturn(alvo);
        when(nfeEmissaoMapper.buscarPorOrigemId(2L)).thenReturn(null);
        when(nfeEmissaoMapper.marcarAbandonado(2L)).thenReturn(1);
        when(sequenciaService.avancarUltimoNumeroParaRecovery(CNPJ, "1", 1)).thenReturn(true);

        var resultado = service.abandonarCiclo(2L, "descricao chinesa incorrigivel no pedido 67");

        verify(nfeEmissaoMapper).marcarAbandonado(2L);
        verify(sequenciaService).liberarGate(CNPJ, "1");
        // Modelo gap: avança ultimo_numero até o nNF; NUNCA consumirNumero (não é destino fiscal).
        verify(sequenciaService).avancarUltimoNumeroParaRecovery(CNPJ, "1", 1);
        verify(sequenciaService, never()).consumirNumero(any(), any(), anyInt());
        // Recovery do ciclo só: NÃO toca Pedido (o pedido de origem fica no status que já tinha,
        // tipicamente REJEITADO — nunca vira DENEGADO) nem Estoque.
        verifyNoInteractions(pedidoMapper, estoqueService);

        assertFalse(resultado.idempotente());
        assertTrue(resultado.gateLiberado());
        assertTrue(resultado.sequenciaAvancada());
        assertEquals(1, resultado.ultimoNumeroResultante());
        assertEquals(NfeEmissao.Estados.AGUARDANDO_CORRECAO, resultado.estadoAnterior());
        assertEquals(NfeEmissao.Estados.ABANDONADO, resultado.estadoAtual());
        assertEquals(1, resultado.numeroNfe());
        assertEquals("1", resultado.serie());
        assertEquals(225, resultado.cstat());
        assertEquals("Rejeição: Falha no Schema XML do lote de NFe", resultado.xmotivo());
    }

    @Test
    @DisplayName("abandonarCiclo: cenário completo — abandono libera o gate, avança ultimo_numero até 1, "
            + "e o próximo abrirCiclo pega o nNF SEGUINTE (2), nunca reusa o nNF queimado")
    void abandonarCiclo_gateLivreDepois_novoCicloPegaProximoNumero() {
        // 1) Abandono do ciclo preso do pedido 67 (série "1", nNF 1, gate ocupado por ele).
        NfeEmissao alvo = emissaoComEvidencia(2L, 67L, NfeEmissao.Estados.AGUARDANDO_CORRECAO, 1, 225, "schema", null);
        when(nfeEmissaoMapper.buscarPorId(2L)).thenReturn(alvo);
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "1")).thenReturn(sequencia(0, 2L));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(2L)).thenReturn(alvo);
        when(nfeEmissaoMapper.buscarPorOrigemId(2L)).thenReturn(null);
        when(nfeEmissaoMapper.marcarAbandonado(2L)).thenReturn(1);
        when(sequenciaService.avancarUltimoNumeroParaRecovery(CNPJ, "1", 1)).thenReturn(true);

        service.abandonarCiclo(2L, "descricao chinesa incorrigivel no pedido 67");

        verify(sequenciaService).liberarGate(CNPJ, "1");
        verify(sequenciaService).avancarUltimoNumeroParaRecovery(CNPJ, "1", 1);
        verify(sequenciaService, never()).consumirNumero(any(), any(), anyInt());

        // 2) Estado pós-abandono: gate livre (emissaoAtivaId=null), ultimo_numero agora 1.
        when(empresaMapper.buscarPorCnpjParaAtualizar(CNPJ)).thenReturn(empresa(8L, "1"));
        when(sequenciaService.buscarOuCriarParaAtualizar(CNPJ, "1")).thenReturn(sequencia(1, null));
        doAnswer(inv -> {
            NfeEmissao e = inv.getArgument(0);
            e.setId(3L);
            return 1;
        }).when(nfeEmissaoMapper).inserir(any(NfeEmissao.class));

        // 3) Pedido 68 abre um ciclo novo — pega o nNF SEGUINTE (1 + 1 = 2), nunca o nNF queimado.
        AberturaCicloResultado novo = service.abrirCiclo(68L, CNPJ);

        assertEquals(TipoAberturaCiclo.NOVA_ABERTURA, novo.tipo());
        assertEquals(2, novo.emissao().getNumeroNfe(), "nNF 1 foi queimado no abandono; o próximo é 2");
        assertEquals("1", novo.emissao().getSerie());
        verify(sequenciaService).ocuparGate(CNPJ, "1", 3L);
    }

    @Test
    @DisplayName("abandonarCiclo: idempotente com contador ATRÁS — não re-marca, mas REPARA a sequência (nunca no-op cego)")
    void abandonarCiclo_jaAbandonado_contadorAtras_reparaSequencia() {
        NfeEmissao jaAbandonado = emissaoComEvidencia(2L, 67L, NfeEmissao.Estados.ABANDONADO, 1, 225, "schema", null);
        when(nfeEmissaoMapper.buscarPorId(2L)).thenReturn(jaAbandonado);
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "1")).thenReturn(sequencia(0, null));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(2L)).thenReturn(jaAbandonado);
        when(sequenciaService.avancarUltimoNumeroParaRecovery(CNPJ, "1", 1)).thenReturn(true);

        var resultado = service.abandonarCiclo(2L, "segunda chamada do mesmo abandono");

        assertTrue(resultado.idempotente());
        assertEquals(NfeEmissao.Estados.ABANDONADO, resultado.estadoAtual());
        verify(nfeEmissaoMapper, never()).marcarAbandonado(anyLong());
        verify(sequenciaService, never()).liberarGate(any(), any());
        verify(sequenciaService, never()).consumirNumero(any(), any(), anyInt());
        // O contador estava atrás (0 < 1) — a chamada idempotente reparou.
        verify(sequenciaService).avancarUltimoNumeroParaRecovery(CNPJ, "1", 1);
        assertTrue(resultado.sequenciaAvancada());
        assertEquals(1, resultado.ultimoNumeroResultante());
    }

    @Test
    @DisplayName("abandonarCiclo: idempotente com contador JÁ alcançado — não toca a sequência (nunca regride)")
    void abandonarCiclo_jaAbandonado_contadorJaAlcancado_naoTocaSequencia() {
        NfeEmissao jaAbandonado = emissaoComEvidencia(2L, 67L, NfeEmissao.Estados.ABANDONADO, 1, 225, "schema", null);
        when(nfeEmissaoMapper.buscarPorId(2L)).thenReturn(jaAbandonado);
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "1")).thenReturn(sequencia(1, null));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(2L)).thenReturn(jaAbandonado);

        var resultado = service.abandonarCiclo(2L, "terceira chamada — contador já em dia");

        assertTrue(resultado.idempotente());
        verify(sequenciaService, never()).avancarUltimoNumeroParaRecovery(any(), any(), anyInt());
        assertFalse(resultado.sequenciaAvancada());
        assertEquals(1, resultado.ultimoNumeroResultante());
    }

    @Test
    @DisplayName("abandonarCiclo: recusa quando há protocolo SEFAZ (nprot != null) — nada é alterado")
    void abandonarCiclo_comProtocolo_recusaSemAlterarNada() {
        NfeEmissao comProt = emissaoComEvidencia(2L, 67L, NfeEmissao.Estados.AGUARDANDO_CORRECAO, 1,
                150, "Autorizado fora de prazo", "135250000012345");
        when(nfeEmissaoMapper.buscarPorId(2L)).thenReturn(comProt);
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "1")).thenReturn(sequencia(0, 2L));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(2L)).thenReturn(comProt);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.abandonarCiclo(2L, "tentativa invalida de abandono com protocolo"));

        assertEquals("CICLO_COM_PROTOCOLO", ex.getErrorCode());
        verify(nfeEmissaoMapper, never()).marcarAbandonado(anyLong());
        verify(sequenciaService, never()).liberarGate(any(), any());
    }

    private void assertNaoAbandonavel(String estado) {
        NfeEmissao alvo = emissaoComEvidencia(2L, 67L, estado, 1, 100, "x", null);
        when(nfeEmissaoMapper.buscarPorId(2L)).thenReturn(alvo);
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "1")).thenReturn(sequencia(0, 2L));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(2L)).thenReturn(alvo);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.abandonarCiclo(2L, "abandono invalido para estado " + estado));

        assertEquals("CICLO_NAO_ABANDONAVEL", ex.getErrorCode(), "estado " + estado);
        verify(nfeEmissaoMapper, never()).marcarAbandonado(anyLong());
        verify(sequenciaService, never()).liberarGate(any(), any());
        verify(sequenciaService, never()).consumirNumero(any(), any(), anyInt());
    }

    @Test
    @DisplayName("abandonarCiclo: recusa AUTORIZADO — terminal real, número já teve destino")
    void abandonarCiclo_recusaAutorizado() {
        assertNaoAbandonavel(NfeEmissao.Estados.AUTORIZADO);
    }

    @Test
    @DisplayName("abandonarCiclo: recusa DENEGADO — terminal real")
    void abandonarCiclo_recusaDenegado() {
        assertNaoAbandonavel(NfeEmissao.Estados.DENEGADO);
    }

    @Test
    @DisplayName("abandonarCiclo: recusa NUMERO_OCUPADO — terminal real")
    void abandonarCiclo_recusaNumeroOcupado() {
        assertNaoAbandonavel(NfeEmissao.Estados.NUMERO_OCUPADO);
    }

    @Test
    @DisplayName("abandonarCiclo: recusa RESERVADO — ciclo ainda em voo, nunca foi rejeitado")
    void abandonarCiclo_recusaReservado() {
        assertNaoAbandonavel(NfeEmissao.Estados.RESERVADO);
    }

    @Test
    @DisplayName("abandonarCiclo: recusa TRANSMITIDO — resultado ainda incerto, resolver por reconciliação")
    void abandonarCiclo_recusaTransmitido() {
        assertNaoAbandonavel(NfeEmissao.Estados.TRANSMITIDO);
    }

    @Test
    @DisplayName("abandonarCiclo: recusa PENDENTE_CONFIRMACAO — resultado ainda incerto")
    void abandonarCiclo_recusaPendenteConfirmacao() {
        assertNaoAbandonavel(NfeEmissao.Estados.PENDENTE_CONFIRMACAO);
    }

    @Test
    @DisplayName("abandonarCiclo: recusa CANCELADO — já é projeção pós-evento homologado")
    void abandonarCiclo_recusaCancelado() {
        assertNaoAbandonavel(NfeEmissao.Estados.CANCELADO);
    }

    @Test
    @DisplayName("abandonarCiclo: emissão inexistente → EMISSAO_NOT_FOUND (404), nada é travado")
    void abandonarCiclo_emissaoNaoEncontrada_lanca404() {
        when(nfeEmissaoMapper.buscarPorId(2L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.abandonarCiclo(2L, "abandono de emissao que nao existe"));

        assertEquals("EMISSAO_NOT_FOUND", ex.getErrorCode());
        assertEquals(404, ex.getHttpStatus());
        verify(sequenciaService, never()).buscarSeExistirParaAtualizar(any(), any());
        verify(nfeEmissaoMapper, never()).buscarPorIdParaAtualizar(anyLong());
        verify(nfeEmissaoMapper, never()).marcarAbandonado(anyLong());
    }

    @Test
    @DisplayName("abandonarCiclo: recusa se o ciclo NORMAL já foi substituído por contingência (filha existe)")
    void abandonarCiclo_cicloSubstituido_recusa() {
        NfeEmissao alvo = emissaoComEvidencia(2L, 67L, NfeEmissao.Estados.AGUARDANDO_CORRECAO, 1, 225, "schema", null);
        NfeEmissao filha = emissao(3L, 67L, NfeEmissao.Estados.RESERVADO, 1);
        when(nfeEmissaoMapper.buscarPorId(2L)).thenReturn(alvo);
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "1")).thenReturn(sequencia(0, 3L));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(2L)).thenReturn(alvo);
        when(nfeEmissaoMapper.buscarPorOrigemId(2L)).thenReturn(filha);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.abandonarCiclo(2L, "abandono de normal ja substituida"));

        assertEquals("CICLO_SUBSTITUIDO", ex.getErrorCode());
        verify(nfeEmissaoMapper, never()).marcarAbandonado(anyLong());
        verify(sequenciaService, never()).liberarGate(any(), any());
    }

    @Test
    @DisplayName("abandonarCiclo: se o gate aponta para OUTRO ciclo, marca ABANDONADO mas NÃO libera o gate alheio")
    void abandonarCiclo_gateApontaParaOutroCiclo_naoLiberaGateAlheio() {
        NfeEmissao alvo = emissaoComEvidencia(2L, 67L, NfeEmissao.Estados.AGUARDANDO_CORRECAO, 1, 225, "schema", null);
        when(nfeEmissaoMapper.buscarPorId(2L)).thenReturn(alvo);
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "1")).thenReturn(sequencia(0, 999L));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(2L)).thenReturn(alvo);
        when(nfeEmissaoMapper.buscarPorOrigemId(2L)).thenReturn(null);
        when(nfeEmissaoMapper.marcarAbandonado(2L)).thenReturn(1);
        when(sequenciaService.avancarUltimoNumeroParaRecovery(CNPJ, "1", 1)).thenReturn(true);

        var resultado = service.abandonarCiclo(2L, "abandono com gate ja de outro ciclo");

        verify(nfeEmissaoMapper).marcarAbandonado(2L);
        verify(sequenciaService, never()).liberarGate(any(), any());
        // O contador ainda é avançado — o gap independe de o gate ser ou não deste ciclo.
        verify(sequenciaService).avancarUltimoNumeroParaRecovery(CNPJ, "1", 1);
        assertFalse(resultado.gateLiberado());
        assertTrue(resultado.sequenciaAvancada());
        assertEquals(NfeEmissao.Estados.ABANDONADO, resultado.estadoAtual());
    }

    @Test
    @DisplayName("abandonarCiclo: ordem canônica de lock — nfe_sequencia (FOR UPDATE) ANTES de nfe_emissao (FOR UPDATE)")
    void abandonarCiclo_ordemDeLock_sequenciaAntesDeEmissao() {
        NfeEmissao alvo = emissaoComEvidencia(2L, 67L, NfeEmissao.Estados.AGUARDANDO_CORRECAO, 1, 225, "schema", null);
        when(nfeEmissaoMapper.buscarPorId(2L)).thenReturn(alvo);
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "1")).thenReturn(sequencia(0, 2L));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(2L)).thenReturn(alvo);
        when(nfeEmissaoMapper.buscarPorOrigemId(2L)).thenReturn(null);
        when(nfeEmissaoMapper.marcarAbandonado(2L)).thenReturn(1);

        service.abandonarCiclo(2L, "verificacao de ordem de lock no abandono");

        InOrder ordem = inOrder(nfeEmissaoMapper, sequenciaService);
        ordem.verify(nfeEmissaoMapper).buscarPorId(2L);                     // pré-leitura NÃO bloqueante
        ordem.verify(sequenciaService).buscarSeExistirParaAtualizar(CNPJ, "1"); // FOR UPDATE nfe_sequencia
        ordem.verify(nfeEmissaoMapper).buscarPorIdParaAtualizar(2L);        // FOR UPDATE nfe_emissao — só depois
        ordem.verify(nfeEmissaoMapper).marcarAbandonado(2L);
        ordem.verify(sequenciaService).liberarGate(CNPJ, "1");
    }

    @Test
    @DisplayName("resolverCiclo: ABANDONADO nunca é destino de resolução — rejeitado explicitamente")
    void resolverCiclo_estadoAbandonado_rejeitado() {
        assertThrows(IllegalArgumentException.class,
                () -> service.resolverCiclo(2L, NfeEmissao.Estados.ABANDONADO, 225, "schema", null));

        verifyNoInteractions(sequenciaService, pedidoMapper, estoqueService);
        verify(nfeEmissaoMapper, never()).buscarPorIdParaAtualizar(anyLong());
        verify(nfeEmissaoMapper, never()).atualizarResultado(any());
    }

    @Test
    @DisplayName("resolverCiclo: resposta tardia da SEFAZ sobre um ciclo já ABANDONADO é no-op — não ressuscita")
    void resolverCiclo_cicloJaAbandonado_respostaTardiaEhNoop() {
        NfeEmissao jaAbandonado = emissaoComEvidencia(2L, 67L, NfeEmissao.Estados.ABANDONADO, 1, 225, "schema", null);
        when(nfeEmissaoMapper.buscarPorId(2L)).thenReturn(jaAbandonado);
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "1")).thenReturn(sequencia(0, null));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(2L)).thenReturn(jaAbandonado);
        when(nfeEmissaoMapper.buscarPorOrigemIdParaAtualizar(2L)).thenReturn(null);

        service.resolverCiclo(2L, NfeEmissao.Estados.AUTORIZADO, 100, null, "135250000099999");

        verify(nfeEmissaoMapper, never()).atualizarResultado(any());
        verify(sequenciaService, never()).consumirNumero(any(), any(), anyInt());
        verify(sequenciaService, never()).liberarGate(any(), any());
    }

    // -------------------------------------------------------------------------
    // marcarTransporteNaoEntregue — recovery de ciclo TRANSMITIDO/PENDENTE_CONFIRMACAO cuja
    // transmissão foi comprovadamente rejeitada no transporte/gateway antes do autorizador.
    // Modelo "gap": encerra o ciclo, libera o gate, avança ultimo_numero até o nNF do ciclo
    // (nunca além, nunca regride), devolve o pedido a ERRO. Nunca chama SEFAZ.
    // -------------------------------------------------------------------------

    private NfeEmissao emissaoTransmitida(Long id, Long pedidoId, String estado, int numero,
                                           String chave, String nprot, int tentativasConsulta) {
        NfeEmissao e = emissao(id, pedidoId, estado, numero);
        e.setCstat(-1);
        e.setXmotivo("Erro ao interpretar resposta: DOCTYPE is disallowed ...");
        e.setChaveNfe(chave);
        e.setNprot(nprot);
        e.setTentativasConsulta(tentativasConsulta);
        return e;
    }

    private Pedido pedido(Long id, Long empresaId) {
        Pedido p = new Pedido();
        p.setId(id);
        p.setEmpresaId(empresaId);
        p.setStatus("AGUARDANDO");
        return p;
    }

    private Empresa empresaControlaEstoque(Long id, boolean controla) {
        Empresa e = new Empresa();
        e.setId(id);
        e.setControleEstoqueAtivo(controla);
        return e;
    }

    private static final String CHAVE_TNE = "35260954393421000159550010000000491699768389";

    @Test
    @DisplayName("marcarTransporteNaoEntregue: PENDENTE_CONFIRMACAO sem evidência, empresa sem estoque → "
            + "TRANSPORTE_NAO_ENTREGUE, gate liberado, ultimo_numero avançado até 49 (gap), pedido → ERRO")
    void marcarTransporteNaoEntregue_pendenteSemEvidencia_encerraLiberaGate_avancaSequencia_pedidoParaErro() {
        NfeEmissao alvo = emissaoTransmitida(3L, 68L, NfeEmissao.Estados.PENDENTE_CONFIRMACAO, 49,
                CHAVE_TNE, null, 0);
        when(nfeEmissaoMapper.buscarPorId(3L)).thenReturn(alvo);
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "1")).thenReturn(sequencia(48, 3L));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(3L)).thenReturn(alvo);
        when(nfeEmissaoMapper.buscarPorOrigemId(3L)).thenReturn(null);
        when(nfeDocumentoMapper.findByChave(CHAVE_TNE)).thenReturn(Optional.empty());
        when(nfeEmissaoMapper.marcarTransporteNaoEntregue(3L)).thenReturn(1);
        when(sequenciaService.avancarUltimoNumeroParaRecovery(CNPJ, "1", 49)).thenReturn(true);
        when(pedidoMapper.buscarPorId(68L)).thenReturn(pedido(68L, 1L));
        when(empresaMapper.buscarPorId(1L)).thenReturn(empresaControlaEstoque(1L, false));

        var r = service.marcarTransporteNaoEntregue(3L, "gateway 403 - lote nao chegou ao autorizador");

        verify(nfeEmissaoMapper).marcarTransporteNaoEntregue(3L);
        verify(sequenciaService).liberarGate(CNPJ, "1");
        verify(sequenciaService).avancarUltimoNumeroParaRecovery(CNPJ, "1", 49);
        verify(sequenciaService, never()).consumirNumero(any(), any(), anyInt());
        verify(pedidoMapper).atualizarStatus(68L, "ERRO", null);
        verify(estoqueService, never()).desfazerReservaItens(any(), any(), any(), any());

        assertFalse(r.idempotente());
        assertTrue(r.gateLiberado());
        assertTrue(r.sequenciaAvancada());
        assertEquals(49, r.ultimoNumeroResultante());
        assertTrue(r.pedidoParaErro());
        assertFalse(r.estoqueDesfeito());
        assertEquals(NfeEmissao.Estados.PENDENTE_CONFIRMACAO, r.estadoAnterior());
        assertEquals(NfeEmissao.Estados.TRANSPORTE_NAO_ENTREGUE, r.estadoAtual());
        assertEquals(49, r.numeroNfe());
        assertEquals(CHAVE_TNE, r.chaveNfePreservada());
        assertEquals(-1, r.cstatSintetico());
    }

    @Test
    @DisplayName("marcarTransporteNaoEntregue: empresa âncora controla estoque → desfaz a reserva")
    void marcarTransporteNaoEntregue_comEstoque_desfazReserva() {
        NfeEmissao alvo = emissaoTransmitida(3L, 68L, NfeEmissao.Estados.TRANSMITIDO, 49, CHAVE_TNE, null, 0);
        when(nfeEmissaoMapper.buscarPorId(3L)).thenReturn(alvo);
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "1")).thenReturn(sequencia(48, 3L));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(3L)).thenReturn(alvo);
        when(nfeEmissaoMapper.buscarPorOrigemId(3L)).thenReturn(null);
        when(nfeDocumentoMapper.findByChave(CHAVE_TNE)).thenReturn(Optional.empty());
        when(nfeEmissaoMapper.marcarTransporteNaoEntregue(3L)).thenReturn(1);
        when(sequenciaService.avancarUltimoNumeroParaRecovery(CNPJ, "1", 49)).thenReturn(true);
        when(pedidoMapper.buscarPorId(68L)).thenReturn(pedido(68L, 8L));
        when(empresaMapper.buscarPorId(8L)).thenReturn(empresaControlaEstoque(8L, true));
        when(pedidoItemMapper.listarPorPedido(68L)).thenReturn(List.of(new PedidoItem()));

        var r = service.marcarTransporteNaoEntregue(3L, "gateway 403 com empresa que controla estoque");

        verify(estoqueService).desfazerReservaItens(anyList(), eq(8L), eq(68L), anyString());
        assertTrue(r.estoqueDesfeito());
        verify(sequenciaService).avancarUltimoNumeroParaRecovery(CNPJ, "1", 49);
        verify(sequenciaService, never()).consumirNumero(any(), any(), anyInt());
    }

    @Test
    @DisplayName("marcarTransporteNaoEntregue: recusa se nfe_documento da chave tem nProt (evidência de processamento)")
    void marcarTransporteNaoEntregue_evidenciaEmNfeDocumento_recusa() {
        NfeEmissao alvo = emissaoTransmitida(3L, 68L, NfeEmissao.Estados.PENDENTE_CONFIRMACAO, 49, CHAVE_TNE, null, 0);
        when(nfeEmissaoMapper.buscarPorId(3L)).thenReturn(alvo);
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "1")).thenReturn(sequencia(48, 3L));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(3L)).thenReturn(alvo);
        when(nfeEmissaoMapper.buscarPorOrigemId(3L)).thenReturn(null);
        NfeDocumento doc = new NfeDocumento();
        doc.setNProt("135260000000001");
        when(nfeDocumentoMapper.findByChave(CHAVE_TNE)).thenReturn(Optional.of(doc));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.marcarTransporteNaoEntregue(3L, "tentativa invalida - existe protocolo real"));

        assertEquals("EVIDENCIA_DE_PROCESSAMENTO", ex.getErrorCode());
        verify(nfeEmissaoMapper, never()).marcarTransporteNaoEntregue(anyLong());
        verify(sequenciaService, never()).liberarGate(any(), any());
        verify(pedidoMapper, never()).atualizarStatus(anyLong(), any(), any());
    }

    @Test
    @DisplayName("marcarTransporteNaoEntregue: recusa se há nProt na própria emissão")
    void marcarTransporteNaoEntregue_comNprot_recusa() {
        NfeEmissao alvo = emissaoTransmitida(3L, 68L, NfeEmissao.Estados.PENDENTE_CONFIRMACAO, 49,
                CHAVE_TNE, "135260000000009", 0);
        when(nfeEmissaoMapper.buscarPorId(3L)).thenReturn(alvo);
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "1")).thenReturn(sequencia(48, 3L));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(3L)).thenReturn(alvo);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.marcarTransporteNaoEntregue(3L, "tentativa invalida - emissao com protocolo"));

        assertEquals("CICLO_COM_PROTOCOLO", ex.getErrorCode());
        verify(nfeEmissaoMapper, never()).marcarTransporteNaoEntregue(anyLong());
    }

    @Test
    @DisplayName("marcarTransporteNaoEntregue: recusa se já houve consulta de reconciliação (tentativas_consulta > 0)")
    void marcarTransporteNaoEntregue_jaReconciliado_recusa() {
        NfeEmissao alvo = emissaoTransmitida(3L, 68L, NfeEmissao.Estados.PENDENTE_CONFIRMACAO, 49, CHAVE_TNE, null, 2);
        when(nfeEmissaoMapper.buscarPorId(3L)).thenReturn(alvo);
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "1")).thenReturn(sequencia(48, 3L));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(3L)).thenReturn(alvo);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.marcarTransporteNaoEntregue(3L, "tentativa invalida - ciclo ja reconciliado"));

        assertEquals("CICLO_JA_RECONCILIADO", ex.getErrorCode());
        verify(nfeEmissaoMapper, never()).marcarTransporteNaoEntregue(anyLong());
    }

    @Test
    @DisplayName("marcarTransporteNaoEntregue: recusa RESERVADO / AUTORIZADO / AGUARDANDO_CORRECAO / ABANDONADO / DENEGADO")
    void marcarTransporteNaoEntregue_estadoNaoElegivel_recusa() {
        for (String estado : new String[]{
                NfeEmissao.Estados.RESERVADO, NfeEmissao.Estados.AUTORIZADO,
                NfeEmissao.Estados.AGUARDANDO_CORRECAO, NfeEmissao.Estados.ABANDONADO,
                NfeEmissao.Estados.DENEGADO, NfeEmissao.Estados.NUMERO_OCUPADO,
                NfeEmissao.Estados.CANCELADO}) {
            NfeEmissao alvo = emissaoTransmitida(3L, 68L, estado, 49, CHAVE_TNE, null, 0);
            when(nfeEmissaoMapper.buscarPorId(3L)).thenReturn(alvo);
            when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "1")).thenReturn(sequencia(48, 3L));
            when(nfeEmissaoMapper.buscarPorIdParaAtualizar(3L)).thenReturn(alvo);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.marcarTransporteNaoEntregue(3L, "abandono invalido para estado " + estado));
            assertEquals("CICLO_NAO_ELEGIVEL_TRANSPORTE", ex.getErrorCode(), "estado " + estado);
            verify(nfeEmissaoMapper, never()).marcarTransporteNaoEntregue(anyLong());
            reset(nfeEmissaoMapper, sequenciaService);
        }
    }

    @Test
    @DisplayName("marcarTransporteNaoEntregue: idempotente com contador ATRÁS — não re-marca, mas REPARA a sequência")
    void marcarTransporteNaoEntregue_jaMarcado_contadorAtras_reparaSequencia() {
        NfeEmissao ja = emissaoTransmitida(3L, 68L, NfeEmissao.Estados.TRANSPORTE_NAO_ENTREGUE, 49, CHAVE_TNE, null, 0);
        when(nfeEmissaoMapper.buscarPorId(3L)).thenReturn(ja);
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "1")).thenReturn(sequencia(48, null));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(3L)).thenReturn(ja);
        when(sequenciaService.avancarUltimoNumeroParaRecovery(CNPJ, "1", 49)).thenReturn(true);

        var r = service.marcarTransporteNaoEntregue(3L, "segunda chamada do mesmo recovery de transporte");

        assertTrue(r.idempotente());
        assertEquals(NfeEmissao.Estados.TRANSPORTE_NAO_ENTREGUE, r.estadoAtual());
        verify(nfeEmissaoMapper, never()).marcarTransporteNaoEntregue(anyLong());
        verify(sequenciaService, never()).liberarGate(any(), any());
        verify(pedidoMapper, never()).atualizarStatus(anyLong(), any(), any());
        // Contador estava atrás (48 < 49) — a chamada idempotente reparou.
        verify(sequenciaService).avancarUltimoNumeroParaRecovery(CNPJ, "1", 49);
        assertTrue(r.sequenciaAvancada());
        assertEquals(49, r.ultimoNumeroResultante());
    }

    @Test
    @DisplayName("marcarTransporteNaoEntregue: idempotente com contador JÁ alcançado — não toca a sequência")
    void marcarTransporteNaoEntregue_jaMarcado_contadorJaAlcancado_naoTocaSequencia() {
        NfeEmissao ja = emissaoTransmitida(3L, 68L, NfeEmissao.Estados.TRANSPORTE_NAO_ENTREGUE, 49, CHAVE_TNE, null, 0);
        when(nfeEmissaoMapper.buscarPorId(3L)).thenReturn(ja);
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "1")).thenReturn(sequencia(49, null));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(3L)).thenReturn(ja);

        var r = service.marcarTransporteNaoEntregue(3L, "terceira chamada — contador já em dia");

        assertTrue(r.idempotente());
        verify(sequenciaService, never()).avancarUltimoNumeroParaRecovery(any(), any(), anyInt());
        assertFalse(r.sequenciaAvancada());
        assertEquals(49, r.ultimoNumeroResultante());
    }

    @Test
    @DisplayName("marcarTransporteNaoEntregue: emissão inexistente → EMISSAO_NOT_FOUND (404)")
    void marcarTransporteNaoEntregue_emissaoNaoEncontrada_404() {
        when(nfeEmissaoMapper.buscarPorId(3L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.marcarTransporteNaoEntregue(3L, "recovery de emissao que nao existe"));

        assertEquals("EMISSAO_NOT_FOUND", ex.getErrorCode());
        assertEquals(404, ex.getHttpStatus());
        verify(sequenciaService, never()).buscarSeExistirParaAtualizar(any(), any());
    }

    @Test
    @DisplayName("marcarTransporteNaoEntregue: se o gate aponta para OUTRO ciclo, marca estado mas NÃO libera o gate alheio")
    void marcarTransporteNaoEntregue_gateApontaParaOutroCiclo_naoLiberaGateAlheio() {
        NfeEmissao alvo = emissaoTransmitida(3L, 68L, NfeEmissao.Estados.PENDENTE_CONFIRMACAO, 49, CHAVE_TNE, null, 0);
        when(nfeEmissaoMapper.buscarPorId(3L)).thenReturn(alvo);
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "1")).thenReturn(sequencia(48, 999L));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(3L)).thenReturn(alvo);
        when(nfeEmissaoMapper.buscarPorOrigemId(3L)).thenReturn(null);
        when(nfeDocumentoMapper.findByChave(CHAVE_TNE)).thenReturn(Optional.empty());
        when(nfeEmissaoMapper.marcarTransporteNaoEntregue(3L)).thenReturn(1);
        when(pedidoMapper.buscarPorId(68L)).thenReturn(pedido(68L, 1L));
        when(empresaMapper.buscarPorId(1L)).thenReturn(empresaControlaEstoque(1L, false));

        var r = service.marcarTransporteNaoEntregue(3L, "recovery com gate ja de outro ciclo");

        verify(nfeEmissaoMapper).marcarTransporteNaoEntregue(3L);
        verify(sequenciaService, never()).liberarGate(any(), any());
        assertFalse(r.gateLiberado());
        assertTrue(r.pedidoParaErro());
    }

    @Test
    @DisplayName("resolverCiclo: TRANSPORTE_NAO_ENTREGUE nunca é destino de resolução — rejeitado")
    void resolverCiclo_estadoTransporteNaoEntregue_rejeitado() {
        assertThrows(IllegalArgumentException.class,
                () -> service.resolverCiclo(3L, NfeEmissao.Estados.TRANSPORTE_NAO_ENTREGUE, -1, "x", null));
        verifyNoInteractions(sequenciaService, pedidoMapper, estoqueService);
    }

    @Test
    @DisplayName("resolverCiclo: resposta tardia da SEFAZ sobre um ciclo já TRANSPORTE_NAO_ENTREGUE é no-op")
    void resolverCiclo_cicloJaTransporteNaoEntregue_respostaTardiaEhNoop() {
        NfeEmissao ja = emissaoTransmitida(3L, 68L, NfeEmissao.Estados.TRANSPORTE_NAO_ENTREGUE, 49, CHAVE_TNE, null, 0);
        when(nfeEmissaoMapper.buscarPorId(3L)).thenReturn(ja);
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "1")).thenReturn(sequencia(48, null));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(3L)).thenReturn(ja);
        when(nfeEmissaoMapper.buscarPorOrigemIdParaAtualizar(3L)).thenReturn(null);

        service.resolverCiclo(3L, NfeEmissao.Estados.AUTORIZADO, 100, null, "135250000012345");

        verify(nfeEmissaoMapper, never()).atualizarResultado(any());
        verify(sequenciaService, never()).consumirNumero(any(), any(), anyInt());
        verify(sequenciaService, never()).liberarGate(any(), any());
    }
}
