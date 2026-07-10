package br.com.borurio.web.service;

import br.com.borurio.app.context.EmpresaContextHolder;
import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.entity.Pedido;
import br.com.borurio.app.entity.PedidoItem;
import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.app.service.EstoqueService;
import br.com.borurio.app.service.PedidoService;
import br.com.borurio.fiscal.dto.NfeGeracaoResult;
import br.com.borurio.fiscal.service.NfeSefazRetornoParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

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

    PedidoEmissaoService service;

    @BeforeEach
    void setUp() {
        service = new PedidoEmissaoService(
                pedidoService, nfeGeracaoService, retornoParser, estoqueService, empresaMapper);
        EmpresaContextHolder.clear();
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
    }

    @Test
    void emitir_pedidoCancelado_bloqueiaReemissao() {
        Pedido pedido = pedidoComStatus("CANCELADO");
        when(pedidoService.buscarComItens(99L)).thenReturn(pedido);

        assertThrows(BusinessException.class, () -> service.emitir(99L));
        verifyNoInteractions(nfeGeracaoService);
    }

    @Test
    void emitir_pedidoAguardando_bloqueiaReemissao() {
        Pedido pedido = pedidoComStatus("AGUARDANDO");
        when(pedidoService.buscarComItens(99L)).thenReturn(pedido);

        assertThrows(BusinessException.class, () -> service.emitir(99L));
        verifyNoInteractions(nfeGeracaoService);
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
