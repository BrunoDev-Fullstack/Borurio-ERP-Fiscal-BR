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

    PedidoEmissaoService service;

    @BeforeEach
    void setUp() {
        EmitenteProperties emitente = new EmitenteProperties();
        emitente.setCnpj("11222333000181"); // fallback legado quando empresa não tem CNPJ (helper de teste não seta)
        service = new PedidoEmissaoService(
                pedidoService, nfeGeracaoService, retornoParser, estoqueService, empresaMapper,
                nfeEmissaoService, emitente);
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
        Pedido p = new Pedido();
        p.setId(99L);
        p.setEmpresaId(10L);
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
        verify(estoqueService).baixaDefinitivaItens(pedido.getItens(), 10L, 99L, "sistema");
        verify(estoqueService, never()).desfazerReservaItens(any(), any(), any(), any());
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
        verify(estoqueService, never()).baixaDefinitivaItens(any(), any(), any(), any());
        verify(estoqueService, never()).desfazerReservaItens(any(), any(), any(), any());
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
        verify(estoqueService, never()).desfazerReservaItens(any(), any(), any(), any());
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
        verify(estoqueService).baixaDefinitivaItens(pedido.getItens(), 10L, 99L, "sistema");
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
        verify(pedidoService).atualizarStatus(99L, "AUTORIZADO", "chaveNova");
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
        verify(estoqueService).baixaDefinitivaItens(pedido.getItens(), 10L, 99L, "sistema");
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

        verify(nfeEmissaoService).resolverCiclo(501L, NfeEmissao.Estados.AUTORIZADO, 100, null, null);
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

        verify(nfeEmissaoService).resolverCiclo(501L, NfeEmissao.Estados.AGUARDANDO_CORRECAO, 225, null, null);
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
}
