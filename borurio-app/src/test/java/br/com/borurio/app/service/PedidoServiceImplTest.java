package br.com.borurio.app.service;

import br.com.borurio.app.mapper.PedidoItemMapper;
import br.com.borurio.app.mapper.PedidoMapper;
import br.com.borurio.app.mapper.ProdutoMapper;
import br.com.borurio.app.service.impl.PedidoServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
}
