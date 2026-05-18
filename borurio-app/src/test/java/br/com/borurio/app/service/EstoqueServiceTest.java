package br.com.borurio.app.service;

import br.com.borurio.app.entity.EstoqueMovimento;
import br.com.borurio.app.entity.EstoqueSaldo;
import br.com.borurio.app.entity.PedidoItem;
import br.com.borurio.app.entity.Produto;
import br.com.borurio.app.mapper.EstoqueMovimentoMapper;
import br.com.borurio.app.mapper.ProdutoMapper;
import br.com.borurio.app.service.impl.EstoqueServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EstoqueServiceTest {

    @Mock ProdutoMapper produtoMapper;
    @Mock EstoqueMovimentoMapper movimentoMapper;

    @InjectMocks EstoqueServiceImpl service;

    private Produto produto;
    private PedidoItem item;

    @BeforeEach
    void setUp() {
        produto = new Produto();
        produto.setId(1L);
        produto.setEmpresaId(10L);
        produto.setDescricao("Produto Teste");
        produto.setEstoque(new BigDecimal("100.0000"));
        produto.setEstoqueReservado(new BigDecimal("0.0000"));

        item = new PedidoItem();
        item.setProdutoId(1L);
        item.setQuantidade(new BigDecimal("5.0000"));
    }

    @Test
    void reservarItens_estoqueDisponivel_sucesso() {
        when(produtoMapper.reservarEstoque(1L, item.getQuantidade(), 10L)).thenReturn(1);

        assertDoesNotThrow(() ->
                service.reservarItens(List.of(item), 10L, 99L, "user1"));

        verify(movimentoMapper).inserir(argThat(m -> "RESERVA".equals(m.getTipo())));
    }

    @Test
    void reservarItens_estoqueInsuficiente_lancaException() {
        when(produtoMapper.reservarEstoque(1L, item.getQuantidade(), 10L)).thenReturn(0);
        when(produtoMapper.buscarPorIdEEmpresa(1L, 10L)).thenReturn(produto);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                service.reservarItens(List.of(item), 10L, 99L, "user1"));

        assertTrue(ex.getMessage().contains("Estoque insuficiente"));
        verify(movimentoMapper, never()).inserir(any());
    }

    @Test
    void reservarItens_produtoNaoEncontrado_lancaException() {
        when(produtoMapper.reservarEstoque(1L, item.getQuantidade(), 10L)).thenReturn(0);
        when(produtoMapper.buscarPorIdEEmpresa(1L, 10L)).thenReturn(null);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                service.reservarItens(List.of(item), 10L, 99L, "user1"));

        assertTrue(ex.getMessage().contains("Produto não encontrado"));
    }

    @Test
    void baixaDefinitivaItens_sucesso() {
        when(produtoMapper.baixaDefinitiva(anyLong(), any(), anyLong())).thenReturn(1);

        assertDoesNotThrow(() ->
                service.baixaDefinitivaItens(List.of(item), 10L, 99L, "user1"));

        verify(movimentoMapper).inserir(argThat(m -> "BAIXA".equals(m.getTipo())));
    }

    @Test
    void desfazerReservaItens_sucesso() {
        when(produtoMapper.desfazerReserva(anyLong(), any(), anyLong())).thenReturn(1);

        assertDoesNotThrow(() ->
                service.desfazerReservaItens(List.of(item), 10L, 99L, "user1"));

        verify(movimentoMapper).inserir(argThat(m -> "DESFAZER_RESERVA".equals(m.getTipo())));
    }

    @Test
    void estornarBaixaItens_sucesso() {
        when(produtoMapper.estornarBaixa(anyLong(), any(), anyLong())).thenReturn(1);

        assertDoesNotThrow(() ->
                service.estornarBaixaItens(List.of(item), 10L, 99L, "user1"));

        verify(movimentoMapper).inserir(argThat(m -> "ESTORNO".equals(m.getTipo())));
    }

    @Test
    void entrada_quantidadeValida_retornaMovimento() {
        when(produtoMapper.buscarPorIdEEmpresa(1L, 10L)).thenReturn(produto);
        when(produtoMapper.estornarBaixa(anyLong(), any(), anyLong())).thenReturn(1);

        EstoqueMovimento mov = service.entrada(1L, 10L, new BigDecimal("50"), "admin", "Ajuste inicial");

        assertNotNull(mov);
        assertEquals("ENTRADA", mov.getTipo());
        assertEquals("MANUAL", mov.getReferenciaTipo());
        verify(movimentoMapper).inserir(any());
    }

    @Test
    void entrada_quantidadeZero_lancaException() {
        assertThrows(IllegalArgumentException.class, () ->
                service.entrada(1L, 10L, BigDecimal.ZERO, "admin", null));
    }

    @Test
    void entrada_produtoNaoEncontrado_lancaException() {
        when(produtoMapper.buscarPorIdEEmpresa(1L, 10L)).thenReturn(null);

        assertThrows(NoSuchElementException.class, () ->
                service.entrada(1L, 10L, new BigDecimal("10"), "admin", null));
    }

    @Test
    void consultarSaldo_retornaSaldoCorreto() {
        produto.setEstoque(new BigDecimal("100.0000"));
        produto.setEstoqueReservado(new BigDecimal("20.0000"));
        when(produtoMapper.buscarPorIdEEmpresa(1L, 10L)).thenReturn(produto);

        EstoqueSaldo saldo = service.consultarSaldo(1L, 10L);

        assertEquals(0, new BigDecimal("100.0000").compareTo(saldo.getEstoqueTotal()));
        assertEquals(0, new BigDecimal("20.0000").compareTo(saldo.getEstoqueReservado()));
        assertEquals(0, new BigDecimal("80.0000").compareTo(saldo.getEstoqueDisponivel()));
    }

    @Test
    void consultarSaldo_produtoNaoEncontrado_lancaException() {
        when(produtoMapper.buscarPorIdEEmpresa(1L, 10L)).thenReturn(null);

        assertThrows(NoSuchElementException.class, () ->
                service.consultarSaldo(1L, 10L));
    }
}
