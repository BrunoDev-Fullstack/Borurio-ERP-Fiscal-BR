package br.com.borurio.web.service;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.app.mapper.PedidoMapper;
import br.com.borurio.fiscal.entity.NfeEmissao;
import br.com.borurio.fiscal.entity.NfeSequencia;
import br.com.borurio.fiscal.mapper.NfeEmissaoMapper;
import br.com.borurio.fiscal.service.NfeSequenciaService;
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
    @Mock NfeSequenciaService sequenciaService;
    @Mock NfeEmissaoMapper nfeEmissaoMapper;

    NfeEmissaoService service;

    private static final String CNPJ = "22418179000134";
    private static final Long PEDIDO_ID = 99L;

    @BeforeEach
    void setUp() {
        service = new NfeEmissaoService(empresaMapper, pedidoMapper, sequenciaService, nfeEmissaoMapper);
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
}
