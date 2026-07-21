package br.com.borurio.web.service;

import br.com.borurio.app.context.EmpresaContextHolder;
import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.entity.Pedido;
import br.com.borurio.app.entity.PedidoItem;
import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.app.service.EstoqueService;
import br.com.borurio.app.service.PedidoService;
import br.com.borurio.fiscal.config.EmitenteProperties;
import br.com.borurio.fiscal.dto.NfeGeracaoResult;
import br.com.borurio.fiscal.service.NfeSefazRetornoParser;
import br.com.borurio.web.dto.ReservaFiscalResultado;
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
    @Mock ReservaFiscalService reservaFiscalService;

    PedidoEmissaoService service;

    @BeforeEach
    void setUp() {
        EmitenteProperties emitente = new EmitenteProperties();
        emitente.setCnpj("11222333000181"); // fallback legado quando empresa não tem CNPJ (helper de teste não seta)
        service = new PedidoEmissaoService(
                pedidoService, nfeGeracaoService, retornoParser, estoqueService, empresaMapper,
                reservaFiscalService, emitente);
        EmpresaContextHolder.clear();
        // Default "feliz" pro claim atômico (P0.1) — testes que não mexem nisso continuam
        // passando; os testes de concorrência/claim sobrescrevem explicitamente por teste.
        // lenient(): os testes que barram antes do claim (status inválido) nunca chamam isso.
        lenient().when(pedidoService.reivindicarParaEmissao(anyLong())).thenReturn(true);
        // Default "feliz" pra reserva fiscal (20-07-2026) — testes que barram antes dela
        // (status inválido, itens vazios, claim perdido) nunca chamam isso.
        lenient().when(reservaFiscalService.reservar(anyLong(), anyString()))
                .thenReturn(new ReservaFiscalResultado("1", 101));
        // Default "feliz" pra validação CFOP×destino (Gate 7D) — coerente com destUf/empresa.uf
        // não setados nos fixtures padrão (operação interna, idDest=1). Testes específicos da
        // validação sobrescrevem explicitamente.
        lenient().when(nfeGeracaoService.resolverIdDest(any(), any())).thenReturn("1");
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
        when(pedidoService.buscarComItens(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any()))
                .thenReturn(new NfeGeracaoResult("chave123", "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(autorizada());

        service.emitir(99L);

        verify(estoqueService).reservarItens(pedido.getItens(), 10L, 99L, "sistema");
        verify(estoqueService).baixaDefinitivaItens(pedido.getItens(), 10L, 99L, "sistema");
        verify(estoqueService, never()).desfazerReservaItens(any(), any(), any(), any());
    }

    @Test
    void emitir_controlaEstoqueFalse_naoReservaNemBaixa() throws Exception {
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItens(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, false));
        when(nfeGeracaoService.gerar(any(), any()))
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
        when(pedidoService.buscarComItens(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, false));
        when(nfeGeracaoService.gerar(any(), any()))
                .thenReturn(new NfeGeracaoResult(null, "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(rejeitada());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.emitir(99L));
        assertEquals("SEFAZ_REJECTED", ex.getErrorCode());
        assertFalse(ex.isRetryable());

        verify(estoqueService, never()).reservarItens(any(), any(), any(), any());
        verify(estoqueService, never()).desfazerReservaItens(any(), any(), any(), any());
    }

    @Test
    void emitir_controlaEstoqueTrue_erroTransmissao_desfazReserva() throws Exception {
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItens(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any())).thenThrow(new RuntimeException("timeout SEFAZ"));

        assertThrows(RuntimeException.class, () -> service.emitir(99L));

        verify(estoqueService).reservarItens(pedido.getItens(), 10L, 99L, "sistema");
        verify(estoqueService).desfazerReservaItens(pedido.getItens(), 10L, 99L, "sistema");
    }

    @Test
    void emitir_empresaNaoEncontrada_defaultControlaEstoque() throws Exception {
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItens(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(null);
        when(nfeGeracaoService.gerar(any(), any()))
                .thenReturn(new NfeGeracaoResult("chave123", "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(autorizada());

        service.emitir(99L);

        verify(estoqueService).reservarItens(pedido.getItens(), 10L, 99L, "sistema");
        verify(estoqueService).baixaDefinitivaItens(pedido.getItens(), 10L, 99L, "sistema");
    }

    @Test
    void emitir_pedidoRejeitado_permiteReemissao() throws Exception {
        Pedido pedido = pedidoComStatus("REJEITADO");
        when(pedidoService.buscarComItens(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any()))
                .thenReturn(new NfeGeracaoResult("chaveNova", "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(autorizada());

        NfeGeracaoResult result = service.emitir(99L);

        assertEquals("chaveNova", result.getChaveNfe());
        verify(pedidoService).atualizarStatus(99L, "AUTORIZADO", "chaveNova");
    }

    @Test
    void emitir_pedidoComErro_permiteReemissao() throws Exception {
        Pedido pedido = pedidoComStatus("ERRO");
        when(pedidoService.buscarComItens(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any()))
                .thenReturn(new NfeGeracaoResult("chaveNova", "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(autorizada());

        NfeGeracaoResult result = service.emitir(99L);

        assertEquals("chaveNova", result.getChaveNfe());
    }

    @Test
    void emitir_pedidoAutorizado_bloqueiaReemissao() {
        Pedido pedido = pedidoComStatus("AUTORIZADO");
        when(pedidoService.buscarComItens(99L)).thenReturn(pedido);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.emitir(99L));
        assertEquals("INVALID_ORDER_STATUS", ex.getErrorCode());
        verifyNoInteractions(nfeGeracaoService);
        // Status já inválido no SELECT inicial — nem tenta o claim atômico.
        verify(pedidoService, never()).reivindicarParaEmissao(any());
    }

    @Test
    void emitir_pedidoCancelado_bloqueiaReemissao() {
        Pedido pedido = pedidoComStatus("CANCELADO");
        when(pedidoService.buscarComItens(99L)).thenReturn(pedido);

        assertThrows(BusinessException.class, () -> service.emitir(99L));
        verifyNoInteractions(nfeGeracaoService);
        verify(pedidoService, never()).reivindicarParaEmissao(any());
    }

    @Test
    void emitir_pedidoAguardando_bloqueiaReemissao() {
        Pedido pedido = pedidoComStatus("AGUARDANDO");
        when(pedidoService.buscarComItens(99L)).thenReturn(pedido);

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
        when(pedidoService.buscarComItens(99L)).thenReturn(pedido);
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
        when(pedidoService.buscarComItens(99L)).thenReturn(pedido);

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
        when(pedidoService.buscarComItens(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any()))
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
        verify(nfeGeracaoService, times(1)).gerar(any(), any());
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
        when(pedidoService.buscarComItens(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any()))
                .thenReturn(new NfeGeracaoResult("chave123", "<soap/>"));
        when(retornoParser.parse("<soap/>")).thenReturn(autorizada());

        assertDoesNotThrow(() -> service.emitir(99L));
    }

    @Test
    void emitir_cfopInterestadualParaOperacaoInterna_rejeitaAntesDeReservar() {
        Pedido pedido = pedidoRascunho();
        pedido.getItens().get(0).setCfop("6102"); // interestadual — inconsistente com idDest=1
        when(pedidoService.buscarComItens(99L)).thenReturn(pedido);
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
        when(pedidoService.buscarComItens(99L)).thenReturn(pedido);
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
        when(pedidoService.buscarComItens(99L)).thenReturn(pedido);
        when(nfeGeracaoService.resolverIdDest(any(), any())).thenReturn("2");
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any()))
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
        when(pedidoService.buscarComItens(99L)).thenReturn(pedido);
        when(nfeGeracaoService.resolverIdDest(any(), any())).thenReturn("3");
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));

        assertThrows(IllegalStateException.class, () -> service.emitir(99L));
        verify(nfeGeracaoService, never()).gerar(any(), any());
        verifyNoInteractions(reservaFiscalService);
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
        when(pedidoService.buscarComItens(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.emitir(99L));

        assertEquals("CFOP_DESTINATION_MISMATCH", ex.getErrorCode());
        verify(nfeGeracaoService, never()).gerar(any(), any());
        verify(estoqueService, never()).reservarItens(any(), any(), any(), any());
    }

    @Test
    void emitir_cfopInconsistente_naoChamaReservaFiscalNemMotorFiscal() throws Exception {
        Pedido pedido = pedidoRascunho();
        pedido.getItens().get(0).setCfop("6102");
        when(pedidoService.buscarComItens(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));

        assertThrows(BusinessException.class, () -> service.emitir(99L));

        // Nada que consome numeração fiscal, cria documento ou envia à SEFAZ pode ter rodado —
        // tudo isso acontece só dentro de reservaFiscalService.reservar()/nfeGeracaoService.gerar().
        // (resolverIdDest, usado pela própria validação, é a única interação legítima aqui.)
        verifyNoInteractions(reservaFiscalService);
        verify(nfeGeracaoService, never()).gerar(any(), any());
    }

    @Test
    void emitir_cfopInconsistente_revertePedidoParaErroNaoParaEstadoIncorreto() {
        Pedido pedido = pedidoRascunho();
        pedido.getItens().get(0).setCfop("6102");
        when(pedidoService.buscarComItens(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));

        assertThrows(BusinessException.class, () -> service.emitir(99L));

        verify(pedidoService).atualizarStatus(99L, "ERRO", pedido.getChaveNfe());
        verify(pedidoService, never()).atualizarStatus(eq(99L), eq("AUTORIZADO"), any());
        verify(pedidoService, never()).atualizarStatus(eq(99L), eq("REJEITADO"), any());
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
