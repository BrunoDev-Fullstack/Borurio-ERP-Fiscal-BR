package br.com.borurio.web.service;

import br.com.borurio.app.context.EmpresaContextHolder;
import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.entity.Pedido;
import br.com.borurio.app.entity.PedidoItem;
import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.app.service.EstoqueService;
import br.com.borurio.app.service.PedidoService;
import br.com.borurio.fiscal.config.EmitenteProperties;
import br.com.borurio.fiscal.domain.nfe.ModalidadeFrete;
import br.com.borurio.fiscal.dto.NfeGeracaoResult;
import br.com.borurio.fiscal.entity.NfeEmissao;
import br.com.borurio.fiscal.exception.SefazTransmissaoIncertaException;
import br.com.borurio.fiscal.exception.XmlSchemaValidationException;
import br.com.borurio.fiscal.service.NfeSefazRetornoParser;
import br.com.borurio.web.dto.AberturaCicloResultado;
import br.com.borurio.web.dto.TipoAberturaCiclo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import br.com.borurio.app.exception.BusinessException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Cobre o desenho de "controle de estoque opcional por empresa": quando a empresa âncora
 * tem controleEstoqueAtivo=false, /emitir não deve validar, reservar, baixar nem desfazer estoque.
 * Quando true (ou empresa não resolvida), o comportamento atual deve ficar 100% preservado.
 */
@ExtendWith(MockitoExtension.class)
class PedidoEmissaoServiceTest {

    @Mock PedidoService pedidoService;
    @Mock NfeGeracaoService nfeGeracaoService;
    @Mock NfeSefazRetornoParser retornoParser;
    @Mock EstoqueService estoqueService;
    @Mock EmpresaMapper empresaMapper;
    @Mock NfeEmissaoService nfeEmissaoService;
    @Mock NfeReconciliacaoService nfeReconciliacaoService;

    PedidoEmissaoService service;

    @BeforeEach
    void setUp() {
        EmitenteProperties emitente = new EmitenteProperties();
        emitente.setCnpj("11222333000181"); // fallback legado quando empresa não tem CNPJ (helper de teste não seta)
        service = new PedidoEmissaoService(
                pedidoService, nfeGeracaoService, retornoParser, estoqueService, empresaMapper,
                nfeEmissaoService, nfeReconciliacaoService, emitente);
        EmpresaContextHolder.clear();
        // Default "feliz" pro claim atômico (P0.1) — testes que não mexem nisso continuam
        // passando; os testes de concorrência/claim sobrescrevem explicitamente por teste.
        // lenient(): os testes que barram antes do claim (status inválido) nunca chamam isso.
        lenient().when(pedidoService.reivindicarParaEmissao(anyLong())).thenReturn(true);
        // Default "feliz" pro Gate 1 (abrirCiclo) — testes que barram antes dele (status
        // inválido, itens vazios, claim perdido, CFOP inconsistente) nunca chamam isso.
        lenient().when(nfeEmissaoService.abrirCiclo(anyLong(), anyString()))
                .thenReturn(new AberturaCicloResultado(emissaoReservada(501L, "1", 101), TipoAberturaCiclo.NOVA_ABERTURA));
        // Default "feliz" pra validação CFOP×destino (Gate 7D) — coerente com destUf/empresa.uf
        // não setados nos fixtures padrão (operação interna, idDest=1). Testes específicos da
        // validação sobrescrevem explicitamente.
        lenient().when(nfeGeracaoService.resolverIdDest(any(), any())).thenReturn("1");
    }

    /** Fixture padrão do Gate 1 — mesma série/número que ReservaFiscalResultado("1", 101) usava antes. */
    private NfeEmissao emissaoReservada(long id, String serie, int numero) {
        NfeEmissao e = new NfeEmissao();
        e.setId(id);
        e.setSerie(serie);
        e.setNumeroNfe(numero);
        e.setEstado(NfeEmissao.Estados.RESERVADO);
        e.setTentativas(1);
        return e;
    }

    @AfterEach
    void tearDown() {
        EmpresaContextHolder.clear();
    }

    private Pedido pedidoRascunho() {
        return pedidoRascunho(99L, 10L);
    }

    /** Variante multiempresa — mesmo fixture, pedidoId/empresaId escolhidos pelo chamador. */
    private Pedido pedidoRascunho(Long pedidoId, Long empresaId) {
        Pedido p = new Pedido();
        p.setId(pedidoId);
        p.setEmpresaId(empresaId);
        p.setStatus("RASCUNHO");
        p.setDestCnpjCpf("12345678000199");

        PedidoItem item = new PedidoItem();
        item.setProdutoId(1L);
        item.setQuantidade(new BigDecimal("2"));
        item.setCsosn("400");
        item.setCfop("5102"); // operação interna — coerente com destUf/empresa.uf não setados (idDest=1 por padrão)
        p.setItens(List.of(item));
        return p;
    }

    private Pedido pedidoComStatus(String status) {
        Pedido p = pedidoRascunho();
        p.setStatus(status);
        return p;
    }

    private Empresa empresa(Long id, Boolean controlaEstoque) {
        Empresa e = new Empresa();
        e.setId(id);
        e.setControleEstoqueAtivo(controlaEstoque);
        return e;
    }

    @Test
    void emitir_controlaEstoqueTrue_reservaEBaixaComoAntes() throws Exception {
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenReturn(new NfeGeracaoResult("chave123", "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(autorizada());

        service.emitir(99L);

        verify(estoqueService).reservarItens(pedido.getItens(), 10L, 99L, "sistema");
        // Gate 3 (10-08-2026): baixa definitiva de estoque passou a ser aplicada por
        // NfeEmissaoService.resolverCicloComEfeitos, na MESMA transação do ciclo fiscal — provar
        // que PedidoEmissaoService delega com controlaEstoque=true é a prova correta aqui; o
        // mecanismo real de baixa é coberto por NfeEmissaoServiceTest.
        verify(nfeEmissaoService).resolverCicloComEfeitos(501L, NfeEmissao.Estados.AUTORIZADO, 100, null, null,
                99L, "AUTORIZADO", "chave123", true, pedido.getItens(), 10L, "sistema");
    }

    /**
     * Fluxo OMS de marketplaces: transporte contratado/operado pela plataforma, nunca pelo
     * emitente nem pelo destinatário — PedidoEmissaoService deve sempre declarar
     * ModalidadeFrete.CONTA_TERCEIROS ao chamar NfeGeracaoService, nunca omitir ou inferir.
     */
    @Test
    void emitir_fluxoOms_sempreDeclaraModalidadeFreteTerceiros() throws Exception {
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any(), eq(ModalidadeFrete.CONTA_TERCEIROS), any()))
                .thenReturn(new NfeGeracaoResult("chave123", "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(autorizada());

        service.emitir(99L);

        verify(nfeGeracaoService).gerar(any(), any(), eq(ModalidadeFrete.CONTA_TERCEIROS), any());
    }

    @Test
    void emitir_controlaEstoqueFalse_naoReservaNemBaixa() throws Exception {
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, false));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenReturn(new NfeGeracaoResult("chave123", "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(autorizada());

        service.emitir(99L);

        verify(estoqueService, never()).reservarItens(any(), any(), any(), any());
        verify(nfeEmissaoService).resolverCicloComEfeitos(501L, NfeEmissao.Estados.AUTORIZADO, 100, null, null,
                99L, "AUTORIZADO", "chave123", false, pedido.getItens(), 10L, "sistema");
    }

    @Test
    void emitir_controlaEstoqueFalse_rejeitado_naoDesfazReserva() throws Exception {
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, false));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenReturn(new NfeGeracaoResult(null, "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(rejeitada());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.emitir(99L));
        assertEquals("SEFAZ_REJECTED", ex.getErrorCode());
        assertFalse(ex.isRetryable());

        verify(estoqueService, never()).reservarItens(any(), any(), any(), any());
        verify(nfeEmissaoService).resolverCicloComEfeitos(501L, NfeEmissao.Estados.AGUARDANDO_CORRECAO, 225, null, null,
                99L, "REJEITADO", null, false, pedido.getItens(), 10L, "sistema");
    }

    /**
     * Banca do Gate Estoque (13-08-2026, item 1) — isolamento multiempresa: prova, na mesma
     * execução, que a empresa A (controlaEstoque=false) e a empresa B (controlaEstoque=true) não
     * se influenciam. controlaEstoque(empresaId) é resolvido do zero a cada chamada (sem cache),
     * então o nível de teste correto é uma sequência de chamadas reais ao mesmo serviço — não há
     * necessidade de nenhuma abstração de produção nova só para isolar o teste.
     */
    @Test
    @DisplayName("Isolamento multiempresa: empresa A (controlaEstoque=false) e empresa B (controlaEstoque=true) na mesma execução não se influenciam")
    void emitir_duasEmpresasNaMesmaExecucao_naoInfluenciamEstoqueUmaDaOutra() throws Exception {
        Pedido pedidoA = pedidoRascunho(99L, 10L);
        Pedido pedidoB = pedidoRascunho(88L, 20L);

        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedidoA);
        when(pedidoService.buscarComItensDoTenanteAtual(88L)).thenReturn(pedidoB);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, false));
        when(empresaMapper.buscarPorId(20L)).thenReturn(empresa(20L, true));
        when(nfeEmissaoService.abrirCiclo(eq(88L), anyString()))
                .thenReturn(new AberturaCicloResultado(emissaoReservada(602L, "1", 201), TipoAberturaCiclo.NOVA_ABERTURA));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenReturn(new NfeGeracaoResult("chave123", "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(autorizada());

        service.emitir(99L); // empresa A — controlaEstoque=false
        service.emitir(88L); // empresa B — controlaEstoque=true, na MESMA execução/JVM

        // Empresa A nunca reserva — nem antes nem depois da chamada de B.
        verify(estoqueService, never()).reservarItens(eq(pedidoA.getItens()), eq(10L), eq(99L), any());
        // Empresa B reserva normalmente — comportamento de sempre, intocado pela presença de A.
        verify(estoqueService).reservarItens(pedidoB.getItens(), 20L, 88L, "sistema");
        // Exatamente 1 reserva no total (só B) — se A tivesse vazado para B ou vice-versa, esse
        // total divergiria de 1.
        verify(estoqueService, times(1)).reservarItens(any(), any(), any(), any());

        verify(nfeEmissaoService).resolverCicloComEfeitos(501L, NfeEmissao.Estados.AUTORIZADO, 100, null, null,
                99L, "AUTORIZADO", "chave123", false, pedidoA.getItens(), 10L, "sistema");
        verify(nfeEmissaoService).resolverCicloComEfeitos(602L, NfeEmissao.Estados.AUTORIZADO, 100, null, null,
                88L, "AUTORIZADO", "chave123", true, pedidoB.getItens(), 20L, "sistema");
    }

    /**
     * Banca do Gate Estoque (13-08-2026, item 2) — reemissão: duas chamadas reais de emitir() pro
     * MESMO pedido (1ª rejeitada, 2ª retry autorizado, mesmo padrão de
     * emitir_retomadaAguardandoCorrecao_reservaEstoqueDeNovo mas com controlaEstoque=false) —
     * nenhuma das duas pode tocar EstoqueService, nem a de reserva nem qualquer outra.
     */
    @Test
    @DisplayName("Reemissão do mesmo pedido com controlaEstoque=false: nenhuma das duas chamadas cria movimentação de estoque")
    void emitir_reemissaoMesmoPedido_controlaEstoqueFalse_nuncaMovimentaEstoque() throws Exception {
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, false));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenReturn(new NfeGeracaoResult(null, "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(rejeitada());

        // 1ª tentativa — rejeitada (AGUARDANDO_CORRECAO), controlaEstoque=false.
        assertThrows(BusinessException.class, () -> service.emitir(99L));

        // 2ª tentativa — reemissão do MESMO pedido (agora REJEITADO), retomando o mesmo ciclo
        // (RETOMADA_AGUARDANDO_CORRECAO — o mesmo tipo que, com controlaEstoque=true, reserva de
        // novo em emitir_retomadaAguardandoCorrecao_reservaEstoqueDeNovo), desta vez autorizada.
        Pedido pedidoRetry = pedidoComStatus("REJEITADO");
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedidoRetry);
        NfeEmissao emissaoExistente = emissaoReservada(501L, "1", 101);
        when(nfeEmissaoService.abrirCiclo(eq(99L), anyString()))
                .thenReturn(new AberturaCicloResultado(emissaoExistente, TipoAberturaCiclo.RETOMADA_AGUARDANDO_CORRECAO));
        when(retornoParser.parse("<soap/>")).thenReturn(autorizada());
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenReturn(new NfeGeracaoResult("chaveNova", "<soap/>"));

        service.emitir(99L);

        // Nenhuma interação com EstoqueService em nenhuma das duas chamadas — nem reserva (a
        // única que PedidoEmissaoService chama diretamente), nem qualquer outro método do mock.
        verifyNoInteractions(estoqueService);
    }

    @Test
    void emitir_controlaEstoqueTrue_erroTransmissaoDesconhecido_mantemReservaEMarcaPendenteConfirmacao() throws Exception {
        // Gate 1 (07-08-2026): diferente de antes, uma falha de transmissão NÃO desfaz mais a
        // reserva de estoque — o número fiscal continua pertencendo a este pedido (RESERVADO ou
        // PENDENTE_CONFIRMACAO, nunca "solto"), e desfazer o estoque enquanto o resultado real na
        // SEFAZ é desconhecido arriscaria inconsistência se a NF-e tiver sido autorizada do outro
        // lado sem o Borurio saber.
        //
        // P1 corrigido (10-08-2026): a fronteira local/transmissão deixou de ser inferida por tipo
        // de exceção e passa a ser comprovada pela fase — NfeOrquestradorService.processar() envolve
        // qualquer falha da chamada real de transmissão em SefazTransmissaoIncertaException. Este
        // teste simula exatamente isso (falha desconhecida DEPOIS que o I/O de rede começou), não
        // mais uma RuntimeException crua (que hoje seria corretamente reclassificada como local).
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenThrow(new SefazTransmissaoIncertaException(new RuntimeException("erro desconhecido")));

        assertThrows(BusinessException.class, () -> service.emitir(99L));

        verify(estoqueService).reservarItens(pedido.getItens(), 10L, 99L, "sistema");
        verify(estoqueService, never()).desfazerReservaItens(any(), any(), any(), any());
        verify(nfeEmissaoService).resolverCiclo(501L, NfeEmissao.Estados.PENDENTE_CONFIRMACAO, null, null, null);
        verify(nfeEmissaoService, never()).reverterParaReservadoPorFalhaLocal(any());
        verify(pedidoService).atualizarStatus(99L, "ERRO", pedido.getChaveNfe());
    }

    @Test
    void emitir_falhaLocalXsdAntesDaTransmissao_mantemReservaEReverteParaReservado() throws Exception {
        // Falha local e síncrona (XSD/assinatura) — certeza de que nada foi enviado à SEFAZ.
        // Estoque também intocado aqui: a reserva original continua de pé, pronta para o retry
        // (que vai bater em RETOMADA_RESERVADO e não reservar de novo).
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenThrow(new XmlSchemaValidationException("XML inválido", null));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.emitir(99L));

        assertEquals("XML_SCHEMA_INVALID", ex.getErrorCode());
        verify(estoqueService).reservarItens(pedido.getItens(), 10L, 99L, "sistema");
        verify(estoqueService, never()).desfazerReservaItens(any(), any(), any(), any());
        verify(nfeEmissaoService).reverterParaReservadoPorFalhaLocal(501L);
        verify(nfeEmissaoService, never()).resolverCiclo(anyLong(), anyString(), any(), any(), any());
        verify(pedidoService).atualizarStatus(99L, "ERRO", pedido.getChaveNfe());
    }

    @Test
    void emitir_empresaNaoEncontrada_defaultControlaEstoque() throws Exception {
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(null);
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenReturn(new NfeGeracaoResult("chave123", "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(autorizada());

        service.emitir(99L);

        verify(estoqueService).reservarItens(pedido.getItens(), 10L, 99L, "sistema");
        verify(nfeEmissaoService).resolverCicloComEfeitos(501L, NfeEmissao.Estados.AUTORIZADO, 100, null, null,
                99L, "AUTORIZADO", "chave123", true, pedido.getItens(), 10L, "sistema");
    }

    @Test
    void emitir_pedidoRejeitado_permiteReemissao() throws Exception {
        Pedido pedido = pedidoComStatus("REJEITADO");
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenReturn(new NfeGeracaoResult("chaveNova", "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(autorizada());

        NfeGeracaoResult result = service.emitir(99L);

        assertEquals("chaveNova", result.getChaveNfe());
        verify(nfeEmissaoService).resolverCicloComEfeitos(501L, NfeEmissao.Estados.AUTORIZADO, 100, null, null,
                99L, "AUTORIZADO", "chaveNova", true, pedido.getItens(), 10L, "sistema");
    }

    @Test
    void emitir_pedidoComErro_permiteReemissao() throws Exception {
        Pedido pedido = pedidoComStatus("ERRO");
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenReturn(new NfeGeracaoResult("chaveNova", "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(autorizada());

        NfeGeracaoResult result = service.emitir(99L);

        assertEquals("chaveNova", result.getChaveNfe());
    }

    @Test
    void emitir_pedidoAutorizado_bloqueiaReemissao() {
        Pedido pedido = pedidoComStatus("AUTORIZADO");
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.emitir(99L));
        assertEquals("INVALID_ORDER_STATUS", ex.getErrorCode());
        verifyNoInteractions(nfeGeracaoService);
        // Status já inválido no SELECT inicial — nem tenta o claim atômico.
        verify(pedidoService, never()).reivindicarParaEmissao(any());
    }

    @Test
    void emitir_pedidoCancelado_bloqueiaReemissao() {
        Pedido pedido = pedidoComStatus("CANCELADO");
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);

        assertThrows(BusinessException.class, () -> service.emitir(99L));
        verifyNoInteractions(nfeGeracaoService);
        verify(pedidoService, never()).reivindicarParaEmissao(any());
    }

    @Test
    void emitir_pedidoAguardando_bloqueiaReemissao() {
        Pedido pedido = pedidoComStatus("AGUARDANDO");
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);

        assertThrows(BusinessException.class, () -> service.emitir(99L));
        verifyNoInteractions(nfeGeracaoService);
        verify(pedidoService, never()).reivindicarParaEmissao(any());
    }

    // -------------------------------------------------------------------------
    // P0.1 — claim atômico e concorrência
    // -------------------------------------------------------------------------

    @Test
    void emitir_claimPerdido_lancaEmissaoEmAndamentoENaoTocaSefaz() {
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(pedidoService.reivindicarParaEmissao(99L)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.emitir(99L));

        assertEquals("EMISSAO_EM_ANDAMENTO", ex.getErrorCode());
        assertTrue(ex.isRetryable(), "corrida é falha transitória — retry deve ser seguro");
        assertEquals(409, ex.getHttpStatus());
        verifyNoInteractions(nfeGeracaoService);
        verify(estoqueService, never()).reservarItens(any(), any(), any(), any());
        // Nenhuma tentativa de mudar status — quem perdeu o claim não é dono do pedido.
        verify(pedidoService, never()).atualizarStatus(eq(99L), anyString(), any());
    }

    @Test
    void emitir_itensVazios_revertePraErroAposClaim() {
        Pedido pedido = pedidoRascunho();
        pedido.setItens(List.of());
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);

        assertThrows(IllegalArgumentException.class, () -> service.emitir(99L));

        // Claim foi conquistado (EMITINDO), mas a falha de validação — antes de qualquer nNF
        // alocado — precisa devolver o pedido a um status emissível, senão fica preso.
        verify(pedidoService).reivindicarParaEmissao(99L);
        verify(pedidoService).atualizarStatus(99L, "ERRO", pedido.getChaveNfe());
        verifyNoInteractions(nfeGeracaoService);
    }

    @Test
    @DisplayName("2 threads simultâneas no mesmo pedido — só uma chega ao motor fiscal")
    void emitir_duasThreadsSimultaneas_apenasUmaProssegue() throws Exception {
        executarConcorrenciaMesmoPedido(2);
    }

    @Test
    @DisplayName("10 threads simultâneas no mesmo pedido — só uma chega ao motor fiscal")
    void emitir_dezThreadsSimultaneas_apenasUmaProssegue() throws Exception {
        executarConcorrenciaMesmoPedido(10);
    }

    /**
     * Simula, no nível de serviço, a garantia atômica que o UPDATE condicional real
     * (PedidoMapper.reivindicarParaEmissao) dá no banco: apenas a primeira chamada concorrente
     * "vence" o claim. Não prova que o MySQL serializa a UPDATE (isso é garantia do motor
     * relacional, papel do banco, não da JVM) — prova que PedidoEmissaoService reage
     * corretamente a essa garantia sem introduzir uma corrida própria em cima dela.
     */
    private void executarConcorrenciaMesmoPedido(int numThreads) throws Exception {
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenReturn(new NfeGeracaoResult("chave123", "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(autorizada());

        AtomicBoolean claimTomado = new AtomicBoolean(false);
        when(pedidoService.reivindicarParaEmissao(99L))
                .thenAnswer(inv -> claimTomado.compareAndSet(false, true));

        ExecutorService pool = Executors.newFixedThreadPool(numThreads);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<Boolean>> futuros = new ArrayList<>();

        for (int i = 0; i < numThreads; i++) {
            futuros.add(pool.submit(() -> {
                largada.await();
                try {
                    service.emitir(99L);
                    return true;
                } catch (BusinessException e) {
                    assertEquals("EMISSAO_EM_ANDAMENTO", e.getErrorCode());
                    return false;
                }
            }));
        }
        largada.countDown();

        int sucessos = 0;
        for (Future<Boolean> f : futuros) {
            if (f.get(10, TimeUnit.SECONDS)) sucessos++;
        }
        pool.shutdown();

        assertEquals(1, sucessos,
                "exatamente uma das " + numThreads + " chamadas concorrentes deveria vencer o claim");
        verify(nfeGeracaoService, times(1)).gerar(any(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // Validação CFOP×destino (Gate 7D) — antes de qualquer reserva de estoque/numeração.
    // Achado real: pedido 22 (J.ZHENG) foi rejeitado pela SEFAZ com cStat=732 só depois de já
    // ter consumido um número fiscal, porque o CFOP do item (6102, interestadual) era
    // incompatível com o destino calculado (idDest=1, operação interna SP→SP).
    // -------------------------------------------------------------------------

    @Test
    void emitir_cfopCoerenteComOperacaoInterna_permiteEmissao() throws Exception {
        Pedido pedido = pedidoRascunho(); // idDest=1 (default do mock), CFOP=5102
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenReturn(new NfeGeracaoResult("chave123", "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(autorizada());

        assertDoesNotThrow(() -> service.emitir(99L));
    }

    @Test
    void emitir_cfopInterestadualParaOperacaoInterna_rejeitaAntesDeReservar() {
        Pedido pedido = pedidoRascunho();
        pedido.getItens().get(0).setCfop("6102"); // interestadual — inconsistente com idDest=1
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.emitir(99L));

        assertEquals("CFOP_DESTINATION_MISMATCH", ex.getErrorCode());
        assertEquals(422, ex.getHttpStatus());
        assertFalse(ex.isRetryable());
        assertTrue(ex.getMessage().contains("6102"),
                "a mensagem deve informar o CFOP recebido, sem corrigi-lo silenciosamente");
        assertTrue(ex.getMessage().contains("idDest=1"));
    }

    @Test
    void emitir_cfopInternoParaOperacaoInterestadual_rejeita() {
        Pedido pedido = pedidoRascunho();
        pedido.getItens().get(0).setCfop("5102"); // interno — inconsistente com idDest=2
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(nfeGeracaoService.resolverIdDest(any(), any())).thenReturn("2");
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.emitir(99L));

        assertEquals("CFOP_DESTINATION_MISMATCH", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("5102"));
        assertTrue(ex.getMessage().contains("idDest=2"));
    }

    @Test
    void emitir_cfopInterestadualComIdDestInterestadual_permiteEmissao() throws Exception {
        Pedido pedido = pedidoRascunho();
        pedido.getItens().get(0).setCfop("6102"); // interestadual — coerente com idDest=2
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(nfeGeracaoService.resolverIdDest(any(), any())).thenReturn("2");
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenReturn(new NfeGeracaoResult("chave123", "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(autorizada());

        assertDoesNotThrow(() -> service.emitir(99L));
    }

    @Test
    void emitir_idDestInesperado_lancaErroInternoEmVezDePermitirSilenciosamente() throws Exception {
        // idDest="3" (destinatário no exterior) não é alcançável hoje — o pedido não tem campo
        // de país do destinatário, só UF brasileira. Documenta o limite: se algum dia
        // resolverIdDest passar a devolver um valor fora de "1"/"2", falha explicitamente em
        // vez de deixar passar um CFOP não validado.
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(nfeGeracaoService.resolverIdDest(any(), any())).thenReturn("3");
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));

        assertThrows(IllegalStateException.class, () -> service.emitir(99L));
        verify(nfeGeracaoService, never()).gerar(any(), any(), any(), any());
        verifyNoInteractions(nfeEmissaoService);
    }

    @Test
    void emitir_umItemInconsistenteEntreVarios_bloqueiaPedidoInteiro() throws Exception {
        Pedido pedido = pedidoRascunho();
        PedidoItem itemValido = pedido.getItens().get(0);
        itemValido.setCfop("5102");
        PedidoItem itemInvalido = new PedidoItem();
        itemInvalido.setProdutoId(2L);
        itemInvalido.setQuantidade(new BigDecimal("1"));
        itemInvalido.setCsosn("400");
        itemInvalido.setCfop("6102"); // inconsistente — deve bloquear o pedido inteiro
        pedido.setItens(List.of(itemValido, itemInvalido));
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.emitir(99L));

        assertEquals("CFOP_DESTINATION_MISMATCH", ex.getErrorCode());
        verify(nfeGeracaoService, never()).gerar(any(), any(), any(), any());
        verify(estoqueService, never()).reservarItens(any(), any(), any(), any());
    }

    @Test
    void emitir_cfopInconsistente_naoChamaReservaFiscalNemMotorFiscal() throws Exception {
        Pedido pedido = pedidoRascunho();
        pedido.getItens().get(0).setCfop("6102");
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));

        assertThrows(BusinessException.class, () -> service.emitir(99L));

        // Nada que consome numeração fiscal, cria documento ou envia à SEFAZ pode ter rodado —
        // tudo isso acontece só dentro de nfeEmissaoService.abrirCiclo()/nfeGeracaoService.gerar().
        // (resolverIdDest, usado pela própria validação, é a única interação legítima aqui.)
        verifyNoInteractions(nfeEmissaoService);
        verify(nfeGeracaoService, never()).gerar(any(), any(), any(), any());
    }

    @Test
    void emitir_cfopInconsistente_revertePedidoParaErroNaoParaEstadoIncorreto() {
        Pedido pedido = pedidoRascunho();
        pedido.getItens().get(0).setCfop("6102");
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));

        assertThrows(BusinessException.class, () -> service.emitir(99L));

        verify(pedidoService).atualizarStatus(99L, "ERRO", pedido.getChaveNfe());
        verify(pedidoService, never()).atualizarStatus(eq(99L), eq("AUTORIZADO"), any());
        verify(pedidoService, never()).atualizarStatus(eq(99L), eq("REJEITADO"), any());
    }

    // -------------------------------------------------------------------------
    // Gate 1 — ciclo do nNF: retomada, matriz de estoque por transição, crash em EMITINDO
    // -------------------------------------------------------------------------

    @Test
    void emitir_retomadaReservado_naoReservaEstoqueDeNovo() throws Exception {
        Pedido pedido = pedidoComStatus("EMITINDO");
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        NfeEmissao emissaoExistente = emissaoReservada(501L, "1", 101);
        when(nfeEmissaoService.buscarUltimaEmissaoDoPedido(99L)).thenReturn(emissaoExistente);
        when(nfeEmissaoService.abrirCiclo(eq(99L), anyString()))
                .thenReturn(new AberturaCicloResultado(emissaoExistente, TipoAberturaCiclo.RETOMADA_RESERVADO));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenReturn(new NfeGeracaoResult("chave123", "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(autorizada());

        service.emitir(99L);

        // Retomada de EMITINDO não repete o claim atômico — já estava reivindicado.
        verify(pedidoService, never()).reivindicarParaEmissao(any());
        // RESERVADO retomado: a reserva de estoque da tentativa original continua de pé.
        verify(estoqueService, never()).reservarItens(any(), any(), any(), any());
        verify(nfeEmissaoService).resolverCicloComEfeitos(501L, NfeEmissao.Estados.AUTORIZADO, 100, null, null,
                99L, "AUTORIZADO", "chave123", true, pedido.getItens(), 10L, "sistema");
    }

    @Test
    void emitir_retomadaAguardandoCorrecao_reservaEstoqueDeNovo() throws Exception {
        Pedido pedido = pedidoComStatus("EMITINDO");
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        NfeEmissao emissaoExistente = emissaoReservada(501L, "1", 101);
        when(nfeEmissaoService.buscarUltimaEmissaoDoPedido(99L)).thenReturn(emissaoExistente);
        when(nfeEmissaoService.abrirCiclo(eq(99L), anyString()))
                .thenReturn(new AberturaCicloResultado(emissaoExistente, TipoAberturaCiclo.RETOMADA_AGUARDANDO_CORRECAO));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenReturn(new NfeGeracaoResult("chave123", "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(autorizada());

        service.emitir(99L);

        // AGUARDANDO_CORRECAO retomado: a reserva foi desfeita ao entrar nesse estado — o retry
        // reserva de novo, mesmo padrão de antes do Gate 1 para REJEITADO.
        verify(estoqueService).reservarItens(pedido.getItens(), 10L, 99L, "sistema");
    }

    @Test
    void emitir_emitindoSemCiclo_lancaPedidoEmissaoInconsistente() {
        Pedido pedido = pedidoComStatus("EMITINDO");
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(nfeEmissaoService.buscarUltimaEmissaoDoPedido(99L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.emitir(99L));

        assertEquals("PEDIDO_EMISSAO_INCONSISTENTE", ex.getErrorCode());
        assertFalse(ex.isRetryable());
        verify(pedidoService, never()).reivindicarParaEmissao(any());
        // Não tenta adivinhar: nenhuma tentativa de mudar o status, nenhuma interação com o
        // motor fiscal ou estoque — exige verificação manual, não um retry automático mascarado.
        verify(pedidoService, never()).atualizarStatus(anyLong(), anyString(), any());
        verifyNoInteractions(nfeGeracaoService);
        verify(estoqueService, never()).reservarItens(any(), any(), any(), any());
    }

    @Test
    void emitir_emitindoComCicloJaTerminal_corrigeStatusELancaPedidoJaResolvido() {
        Pedido pedido = pedidoComStatus("EMITINDO");
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        NfeEmissao emissaoAutorizada = emissaoReservada(501L, "1", 101);
        emissaoAutorizada.setEstado(NfeEmissao.Estados.AUTORIZADO);
        when(nfeEmissaoService.buscarUltimaEmissaoDoPedido(99L)).thenReturn(emissaoAutorizada);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.emitir(99L));

        assertEquals("PEDIDO_JA_RESOLVIDO", ex.getErrorCode());
        assertFalse(ex.isRetryable());
        // Corrige o status pra refletir o resultado real em vez de abrir um ciclo novo — um gate
        // livre lido como "abertura nova" alocaria um número seguinte para um pedido já resolvido.
        verify(pedidoService).atualizarStatus(99L, "AUTORIZADO", pedido.getChaveNfe());
        verifyNoInteractions(nfeGeracaoService);
        verify(nfeEmissaoService, never()).abrirCiclo(any(), any());
    }

    @Test
    void emitir_gateOcupadoPorOutroPedido_propagaEmissaoEmAndamentoNaSerie() throws Exception {
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeEmissaoService.abrirCiclo(eq(99L), anyString()))
                .thenThrow(BusinessException.emissaoEmAndamentoNaSerie("11222333000181", "1"));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.emitir(99L));

        assertEquals("EMISSAO_EM_ANDAMENTO_NA_SERIE", ex.getErrorCode());
        assertTrue(ex.isRetryable());
        verify(pedidoService).atualizarStatus(99L, "ERRO", pedido.getChaveNfe());
        // resolverIdDest (usado pela validação de CFOP, que roda antes de abrirCiclo) é a única
        // interação legítima com este mock — gerar() nunca deve ser chamado.
        verify(nfeGeracaoService, never()).gerar(any(), any(), any(), any());
    }

    @Test
    void emitir_cStat100_resolveCicloComoAutorizado() throws Exception {
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenReturn(new NfeGeracaoResult("chave123", "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(autorizada());

        service.emitir(99L);

        verify(nfeEmissaoService).resolverCicloComEfeitos(501L, NfeEmissao.Estados.AUTORIZADO, 100, null, null,
                99L, "AUTORIZADO", "chave123", true, pedido.getItens(), 10L, "sistema");
    }

    @Test
    void emitir_cStat225_resolveCicloComoAguardandoCorrecao() throws Exception {
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenReturn(new NfeGeracaoResult(null, "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(rejeitada());

        assertThrows(BusinessException.class, () -> service.emitir(99L));

        verify(nfeEmissaoService).resolverCicloComEfeitos(501L, NfeEmissao.Estados.AGUARDANDO_CORRECAO, 225, null, null,
                99L, "REJEITADO", null, true, pedido.getItens(), 10L, "sistema");
    }

    // -------------------------------------------------------------------------
    // Gate 2 — matriz de classificação semântica do cStat (10-08-2026)
    // -------------------------------------------------------------------------

    @Test
    void emitir_cStat150_resolveCicloComoAutorizado_consomeNumeroEBaixaEstoque() throws Exception {
        // 150 = autorizado fora do prazo, mesma classe fiscal de 100 — número consumido, gate
        // liberado, estoque baixado definitivamente.
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenReturn(new NfeGeracaoResult("chave123", "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(comCStat(150));

        service.emitir(99L);

        verify(nfeEmissaoService).resolverCicloComEfeitos(501L, NfeEmissao.Estados.AUTORIZADO, 150, null, null,
                99L, "AUTORIZADO", "chave123", true, pedido.getItens(), 10L, "sistema");
    }

    @Test
    void emitir_cStat302_resolveCicloComoAguardandoCorrecao() throws Exception {
        // 302 = rejeição por irregularidade fiscal do destinatário (Ajuste SINIEF 43/23 — deixou
        // de ser denegação em 01-08-2024) — mesmo nNF, gate mantido, reserva desfeita.
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenReturn(new NfeGeracaoResult(null, "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(comCStat(302));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.emitir(99L));

        assertEquals("SEFAZ_REJECTED", ex.getErrorCode());
        verify(nfeEmissaoService).resolverCicloComEfeitos(501L, NfeEmissao.Estados.AGUARDANDO_CORRECAO, 302, null, null,
                99L, "REJEITADO", null, true, pedido.getItens(), 10L, "sistema");
    }

    @Test
    void emitir_cStat303_resolveCicloComoAguardandoCorrecao() throws Exception {
        // 303 = rejeição por destinatário não habilitado a operar na UF — mesma classe de 302.
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenReturn(new NfeGeracaoResult(null, "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(comCStat(303));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.emitir(99L));

        assertEquals("SEFAZ_REJECTED", ex.getErrorCode());
        verify(nfeEmissaoService).resolverCicloComEfeitos(501L, NfeEmissao.Estados.AGUARDANDO_CORRECAO, 303, null, null,
                99L, "REJEITADO", null, true, pedido.getItens(), 10L, "sistema");
    }

    @Test
    void emitir_cStat301_resolveCicloComoPendenteConfirmacao_naoTrataComoRejeicaoCorrigivel() throws Exception {
        // 301 = "irregularidade fiscal do emitente" (nome histórico do MOC 7.0) — a NT 2024.001
        // EXCLUIU a regra que produzia especificamente este código; diferente de 302/303 (que
        // mantiveram o número com efeito alterado), não há evidência de que a SEFAZ ainda devolva
        // 301 para modelo 55 — fail-safe conservador, nunca reaproveitamento automático.
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenReturn(new NfeGeracaoResult("chave123", "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(comCStat(301));

        service.emitir(99L);

        verify(nfeEmissaoService).resolverCicloComEfeitos(501L, NfeEmissao.Estados.PENDENTE_CONFIRMACAO, 301, null, null,
                99L, "AGUARDANDO", "chave123", true, pedido.getItens(), 10L, "sistema");
    }

    @Test
    void emitir_cStat103LoteAindaProcessando_resolveCicloComoPendenteConfirmacao() throws Exception {
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenReturn(new NfeGeracaoResult("chave123", "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(comCStat(103));

        service.emitir(99L);

        verify(nfeEmissaoService).resolverCicloComEfeitos(501L, NfeEmissao.Estados.PENDENTE_CONFIRMACAO, 103, null, null,
                99L, "AGUARDANDO", "chave123", true, pedido.getItens(), 10L, "sistema");
    }

    @Test
    void emitir_cStat104SemInfProt_resolveCicloComoPendenteConfirmacao_naoDuplicaExtracaoDoParser() throws Exception {
        // Com indSinc=1 (único modo do Borurio), o cStat individual real já deveria ter vindo
        // dentro de infProt e sido extraído por NfeSefazRetornoParser ANTES deste método ser
        // chamado. Se este método recebe 104 literal, é porque infProt estava ausente — resposta
        // anômala, não o caminho feliz de indSinc=1. Prova o fallback, não reimplementa o parser.
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenReturn(new NfeGeracaoResult("chave123", "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(comCStat(104));

        service.emitir(99L);

        verify(nfeEmissaoService).resolverCicloComEfeitos(501L, NfeEmissao.Estados.PENDENTE_CONFIRMACAO, 104, null, null,
                99L, "AGUARDANDO", "chave123", true, pedido.getItens(), 10L, "sistema");
    }

    @Test
    void emitir_cStat105LoteEmProcessamento_resolveCicloComoPendenteConfirmacao() throws Exception {
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenReturn(new NfeGeracaoResult("chave123", "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(comCStat(105));

        service.emitir(99L);

        verify(nfeEmissaoService).resolverCicloComEfeitos(501L, NfeEmissao.Estados.PENDENTE_CONFIRMACAO, 105, null, null,
                99L, "AGUARDANDO", "chave123", true, pedido.getItens(), 10L, "sistema");
    }

    @Test
    void emitir_cStat106LoteNaoLocalizado_resolveCicloComoPendenteConfirmacao() throws Exception {
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenReturn(new NfeGeracaoResult("chave123", "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(comCStat(106));

        service.emitir(99L);

        verify(nfeEmissaoService).resolverCicloComEfeitos(501L, NfeEmissao.Estados.PENDENTE_CONFIRMACAO, 106, null, null,
                99L, "AGUARDANDO", "chave123", true, pedido.getItens(), 10L, "sistema");
    }

    @Test
    void emitir_cStat110UsoDenegadoHistorico_resolveCicloComoPendenteConfirmacao_nuncaAutomatico() throws Exception {
        // "Uso Denegado" — revogado pelo Ajuste SINIEF 43/23 desde 01-08-2024 para modelo 55. Se
        // ainda assim ocorrer, sem evidência oficial de tratamento seguro: nunca decide sozinho.
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenReturn(new NfeGeracaoResult("chave123", "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(comCStat(110));

        service.emitir(99L);

        verify(nfeEmissaoService).resolverCicloComEfeitos(501L, NfeEmissao.Estados.PENDENTE_CONFIRMACAO, 110, null, null,
                99L, "AGUARDANDO", "chave123", true, pedido.getItens(), 10L, "sistema");
    }

    @Test
    void emitir_cStat204Duplicidade_resolveCicloComoPendenteConfirmacao_naoDecideIdempotenciaSozinho() throws Exception {
        // Duplicidade pode legitimamente vir com protocolo já emitido (idempotência real) — mas
        // auditar isso é Gate 3; este método nunca decide automaticamente aqui.
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenReturn(new NfeGeracaoResult("chave123", "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(comCStat(204));

        service.emitir(99L);

        verify(nfeEmissaoService).resolverCicloComEfeitos(501L, NfeEmissao.Estados.PENDENTE_CONFIRMACAO, 204, null, null,
                99L, "AGUARDANDO", "chave123", true, pedido.getItens(), 10L, "sistema");
    }

    @Test
    void emitir_cStat205JaDenegadaNaBase_resolveCicloComoPendenteConfirmacao_nuncaReaproveitaNumero() throws Exception {
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenReturn(new NfeGeracaoResult("chave123", "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(comCStat(205));

        service.emitir(99L);

        verify(nfeEmissaoService).resolverCicloComEfeitos(501L, NfeEmissao.Estados.PENDENTE_CONFIRMACAO, 205, null, null,
                99L, "AGUARDANDO", "chave123", true, pedido.getItens(), 10L, "sistema");
    }

    @Test
    void emitir_cStat218JaCanceladaNaBase_resolveCicloComoPendenteConfirmacao_nuncaReaproveitaNumero() throws Exception {
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenReturn(new NfeGeracaoResult("chave123", "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(comCStat(218));

        service.emitir(99L);

        verify(nfeEmissaoService).resolverCicloComEfeitos(501L, NfeEmissao.Estados.PENDENTE_CONFIRMACAO, 218, null, null,
                99L, "AGUARDANDO", "chave123", true, pedido.getItens(), 10L, "sistema");
    }

    @Test
    void emitir_cStat539DuplicidadeComChaveDiferente_resolveCicloComoPendenteConfirmacao_nuncaLiberaGate() throws Exception {
        // Caso mais sensível da tabela: identidade lógica já existe com chave de acesso diferente
        // da que acabamos de transmitir — nunca libera o gate, nunca gera chave nova sozinho.
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenReturn(new NfeGeracaoResult("chave123", "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(comCStat(539));

        service.emitir(99L);

        verify(nfeEmissaoService).resolverCicloComEfeitos(501L, NfeEmissao.Estados.PENDENTE_CONFIRMACAO, 539, null, null,
                99L, "AGUARDANDO", "chave123", true, pedido.getItens(), 10L, "sistema");
    }

    // -------------------------------------------------------------------------
    // Gate de contrato OMS (11-08-2026) — serie/numeroNfe/estadoFiscal/cStat/xMotivo/nProt
    // aditivos ao retorno de /emitir, sempre a partir de nfe_emissao.
    // -------------------------------------------------------------------------

    @Test
    void emitir_autorizado100_retornaCamposFiscaisEnriquecidos() throws Exception {
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenReturn(new NfeGeracaoResult("chave123", "<soap/>"));
        br.com.borurio.fiscal.dto.NfeSefazRetorno retorno = autorizada();
        retorno.setXMotivo("Autorizado o uso da NF-e");
        retorno.setNProt("135260000001234");
        when(retornoParser.parse("<soap/>")).thenReturn(retorno);

        NfeGeracaoResult result = service.emitir(99L);

        assertEquals("chave123", result.getChaveNfe());
        assertEquals("<soap/>", result.getSoapRetorno());
        assertEquals("1", result.getSerie());
        assertEquals(101, result.getNumeroNfe());
        assertEquals(NfeEmissao.Estados.AUTORIZADO, result.getEstadoFiscal());
        assertEquals(100, result.getCStat());
        assertEquals("Autorizado o uso da NF-e", result.getXMotivo());
        assertEquals("135260000001234", result.getNProt());
    }

    @Test
    void emitir_autorizado150_retornaEstadoFiscalAutorizado() throws Exception {
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenReturn(new NfeGeracaoResult("chave123", "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(comCStat(150));

        NfeGeracaoResult result = service.emitir(99L);

        assertEquals(NfeEmissao.Estados.AUTORIZADO, result.getEstadoFiscal());
        assertEquals(150, result.getCStat());
        assertEquals("1", result.getSerie());
        assertEquals(101, result.getNumeroNfe());
    }

    @Test
    void emitir_rejeicaoCorrigivel_dataContemSerieNumeroNfeEstadoFiscal() throws Exception {
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenReturn(new NfeGeracaoResult(null, "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(rejeitada());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.emitir(99L));

        assertEquals("SEFAZ_REJECTED", ex.getErrorCode());
        assertEquals("1", ex.getData().get("serie"));
        assertEquals(101, ex.getData().get("numeroNFe"));
        assertEquals("AGUARDANDO_CORRECAO", ex.getData().get("estadoFiscal"));
        assertEquals(225, ex.getData().get("cStat"));
    }

    @Test
    void emitir_pendenteConfirmacaoSemRespostaSefaz_camposFiscaisNullMasEstadoFiscalPreenchido() throws Exception {
        // retornoParser.parse falha (resposta ilegível) — parseRetornoSeguro devolve null, então
        // cStat/xMotivo/nProt ficam null, mas o OMS ainda precisa distinguir isso de "sem dado
        // nenhum": estadoFiscal e serie/numeroNfe continuam preenchidos.
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenReturn(new NfeGeracaoResult("chave123", "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenThrow(new RuntimeException("falha ao parsear XML"));

        NfeGeracaoResult result = service.emitir(99L);

        assertEquals("chave123", result.getChaveNfe());
        assertEquals("1", result.getSerie());
        assertEquals(101, result.getNumeroNfe());
        assertEquals(NfeEmissao.Estados.PENDENTE_CONFIRMACAO, result.getEstadoFiscal());
        assertNull(result.getCStat());
        assertNull(result.getXMotivo());
        assertNull(result.getNProt());
    }

    @Test
    void emitir_pendenteConfirmacaoComCStatConhecido_retornaCStatPreenchido() throws Exception {
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenReturn(new NfeGeracaoResult("chave123", "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(comCStat(103));

        NfeGeracaoResult result = service.emitir(99L);

        assertEquals(NfeEmissao.Estados.PENDENTE_CONFIRMACAO, result.getEstadoFiscal());
        assertEquals(103, result.getCStat());
        assertEquals("1", result.getSerie());
        assertEquals(101, result.getNumeroNfe());
    }

    @Test
    @DisplayName("reemissão/reconciliação: pedido AGUARDANDO resolvido como AUTORIZADO devolve os campos do ciclo persistido, não do objeto em memória pré-reconciliação")
    void reconciliarAguardando_autorizado_retornaCamposFiscaisDoCicloResolvido() throws Exception {
        Pedido pedido = pedidoComStatus("AGUARDANDO");
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));

        NfeEmissao pendente = emissaoReservada(501L, "1", 101);
        pendente.setEstado(NfeEmissao.Estados.TRANSMITIDO);
        pendente.setChaveNfe("chavePendente");

        NfeEmissao resolvida = emissaoReservada(501L, "1", 101);
        resolvida.setEstado(NfeEmissao.Estados.AUTORIZADO);
        resolvida.setChaveNfe("chavePendente");
        resolvida.setCstat(100);
        resolvida.setXmotivo("Autorizado o uso da NF-e");
        resolvida.setNprot("135260000001234");

        // reconciliar() persiste em nfe_emissao sem devolver o resultado — o serviço relê o ciclo
        // depois da chamada, então o mock precisa refletir "antes" na 1ª leitura e "depois" na 2ª.
        when(nfeEmissaoService.buscarUltimaEmissaoDoPedido(99L))
                .thenReturn(pendente)
                .thenReturn(resolvida);

        NfeGeracaoResult result = service.emitir(99L);

        assertEquals("chavePendente", result.getChaveNfe());
        assertEquals("1", result.getSerie());
        assertEquals(101, result.getNumeroNfe());
        assertEquals(NfeEmissao.Estados.AUTORIZADO, result.getEstadoFiscal());
        assertEquals(100, result.getCStat());
        assertEquals("Autorizado o uso da NF-e", result.getXMotivo());
        assertEquals("135260000001234", result.getNProt());
        verify(nfeReconciliacaoService).reconciliar(eq(pedido), eq(pendente), any(), eq(true));
    }

    private br.com.borurio.fiscal.dto.NfeSefazRetorno autorizada() {
        br.com.borurio.fiscal.dto.NfeSefazRetorno r = new br.com.borurio.fiscal.dto.NfeSefazRetorno();
        r.setCStat(100);
        return r;
    }

    private br.com.borurio.fiscal.dto.NfeSefazRetorno rejeitada() {
        br.com.borurio.fiscal.dto.NfeSefazRetorno r = new br.com.borurio.fiscal.dto.NfeSefazRetorno();
        r.setCStat(225);
        return r;
    }

    private br.com.borurio.fiscal.dto.NfeSefazRetorno comCStat(int cStat) {
        br.com.borurio.fiscal.dto.NfeSefazRetorno r = new br.com.borurio.fiscal.dto.NfeSefazRetorno();
        r.setCStat(cStat);
        return r;
    }
}
