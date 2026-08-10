package br.com.borurio.app.service;

import br.com.borurio.app.context.EmpresaContextHolder;
import br.com.borurio.app.entity.Pedido;
import br.com.borurio.app.mapper.PedidoItemMapper;
import br.com.borurio.app.mapper.PedidoMapper;
import br.com.borurio.app.mapper.ProdutoMapper;
import br.com.borurio.app.service.impl.PedidoServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * P0.1 — cobre só a tradução de rowsAffected para boolean no claim atômico de emissão.
 * A garantia de exclusão mútua em si é do UPDATE condicional no banco (ver
 * PedidoMapper.reivindicarParaEmissao); aqui só se testa que o service repassa o resultado
 * corretamente pra quem decide se venceu ou perdeu a corrida (PedidoEmissaoService).
 */
@ExtendWith(MockitoExtension.class)
class PedidoServiceImplTest {

    @Mock PedidoMapper pedidoMapper;
    @Mock PedidoItemMapper pedidoItemMapper;
    @Mock ProdutoMapper produtoMapper;

    @InjectMocks PedidoServiceImpl service;

    @Test
    @DisplayName("rowsAffected=1 (claim vencido) retorna true")
    void reivindicarParaEmissao_umaLinhaAfetada_retornaTrue() {
        when(pedidoMapper.reivindicarParaEmissao(99L)).thenReturn(1);

        assertTrue(service.reivindicarParaEmissao(99L));
    }

    @Test
    @DisplayName("rowsAffected=0 (claim perdido ou status não emissível) retorna false")
    void reivindicarParaEmissao_zeroLinhasAfetadas_retornaFalse() {
        when(pedidoMapper.reivindicarParaEmissao(99L)).thenReturn(0);

        assertFalse(service.reivindicarParaEmissao(99L));
    }

    // -------------------------------------------------------------------------
    // P0-2 (07-08-2026, hardening pós-banca) — fronteira central de isolamento multiempresa.
    // -------------------------------------------------------------------------

    @AfterEach
    void limparContexto() {
        EmpresaContextHolder.clear();
    }

    private Pedido pedidoComItens(Long id) {
        Pedido p = new Pedido();
        p.setId(id);
        return p;
    }

    @Test
    @DisplayName("buscarComItensDoTenanteAtual: com contexto de empresa, delega pra busca escopada por tenant")
    void buscarComItensDoTenanteAtual_comContexto_delegaParaBuscaEscopada() {
        EmpresaContextHolder.set(10L);
        when(pedidoMapper.buscarPorIdEEmpresa(99L, 10L)).thenReturn(pedidoComItens(99L));

        Pedido resultado = service.buscarComItensDoTenanteAtual(99L);

        assertEquals(99L, resultado.getId());
        verify(pedidoMapper).buscarPorIdEEmpresa(99L, 10L);
        verify(pedidoMapper, never()).buscarPorId(anyLong());
    }

    @Test
    @DisplayName("buscarComItensDoTenanteAtual: pedido de outra empresa lança NoSuchElementException (404), nunca revela existência")
    void buscarComItensDoTenanteAtual_pedidoDeOutraEmpresa_lancaNoSuchElement() {
        EmpresaContextHolder.set(10L);
        when(pedidoMapper.buscarPorIdEEmpresa(99L, 10L)).thenReturn(null); // existe, mas é de outra empresa

        assertThrows(NoSuchElementException.class, () -> service.buscarComItensDoTenanteAtual(99L));
    }

    @Test
    @DisplayName("buscarComItensDoTenanteAtual: sem contexto (fluxo ADMIN), preserva comportamento irrestrito de antes")
    void buscarComItensDoTenanteAtual_semContexto_comportamentoAdminPreservado() {
        EmpresaContextHolder.clear();
        when(pedidoMapper.buscarPorId(99L)).thenReturn(pedidoComItens(99L));

        Pedido resultado = service.buscarComItensDoTenanteAtual(99L);

        assertEquals(99L, resultado.getId());
        verify(pedidoMapper, never()).buscarPorIdEEmpresa(anyLong(), anyLong());
    }

    @Test
    @DisplayName("buscarPorIdDoTenanteAtual: com contexto de empresa, delega pra busca escopada por tenant")
    void buscarPorIdDoTenanteAtual_comContexto_delegaParaBuscaEscopada() {
        EmpresaContextHolder.set(10L);
        when(pedidoMapper.buscarPorIdEEmpresa(99L, 10L)).thenReturn(pedidoComItens(99L));

        Pedido resultado = service.buscarPorIdDoTenanteAtual(99L);

        assertEquals(99L, resultado.getId());
        verify(pedidoMapper, never()).buscarPorId(anyLong());
    }

    @Test
    @DisplayName("buscarPorIdDoTenanteAtual: pedido de outra empresa lança NoSuchElementException (404)")
    void buscarPorIdDoTenanteAtual_pedidoDeOutraEmpresa_lancaNoSuchElement() {
        EmpresaContextHolder.set(10L);
        when(pedidoMapper.buscarPorIdEEmpresa(99L, 10L)).thenReturn(null);

        assertThrows(NoSuchElementException.class, () -> service.buscarPorIdDoTenanteAtual(99L));
    }

    @Test
    @DisplayName("buscarPorIdDoTenanteAtual: sem contexto (fluxo ADMIN), preserva comportamento irrestrito de antes")
    void buscarPorIdDoTenanteAtual_semContexto_comportamentoAdminPreservado() {
        EmpresaContextHolder.clear();
        when(pedidoMapper.buscarPorId(99L)).thenReturn(pedidoComItens(99L));

        Pedido resultado = service.buscarPorIdDoTenanteAtual(99L);

        assertEquals(99L, resultado.getId());
        verify(pedidoMapper, never()).buscarPorIdEEmpresa(anyLong(), anyLong());
    }
}
