package br.com.borurio.web.service;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.entity.Pedido;
import br.com.borurio.app.entity.PedidoItem;
import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.app.service.EstoqueService;
import br.com.borurio.app.service.PedidoService;
import br.com.borurio.fiscal.config.EmitenteProperties;
import br.com.borurio.fiscal.entity.NfeDocumento;
import br.com.borurio.fiscal.service.NfeCancelamentoService;
import br.com.borurio.fiscal.service.NfeCceService;
import br.com.borurio.fiscal.service.NfeDocumentoService;
import br.com.borurio.fiscal.service.NfeTransmitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Cobre o desenho de "controle de estoque opcional por empresa" no cancelamento:
 * quando a empresa tem controleEstoqueAtivo=false, o estorno de baixa não deve rodar.
 */
@ExtendWith(MockitoExtension.class)
class PedidoOperacaoServiceTest {

    @Mock PedidoService pedidoService;
    @Mock NfeDocumentoService documentoService;
    @Mock NfeTransmitService transmitService;
    @Mock NfeCancelamentoService cancelamentoService;
    @Mock NfeCceService cceService;
    @Mock EmitenteProperties emitente;
    @Mock EstoqueService estoqueService;
    @Mock EmpresaMapper empresaMapper;

    PedidoOperacaoService service;

    @BeforeEach
    void setUp() {
        service = new PedidoOperacaoService(pedidoService, documentoService, transmitService,
                cancelamentoService, cceService, emitente, estoqueService, empresaMapper);
    }

    private Pedido pedidoAutorizado() {
        Pedido p = new Pedido();
        p.setId(50L);
        p.setEmpresaId(10L);
        p.setStatus("AUTORIZADO");
        p.setChaveNfe("35260700000000000000550010000000011000000010");

        PedidoItem item = new PedidoItem();
        item.setProdutoId(1L);
        item.setQuantidade(new BigDecimal("2"));
        p.setItens(List.of(item));
        return p;
    }

    private NfeDocumento documentoComProtocolo() {
        NfeDocumento doc = new NfeDocumento();
        doc.setNProt("135260000001234");
        return doc;
    }

    private Empresa empresa(Boolean controlaEstoque) {
        Empresa e = new Empresa();
        e.setId(10L);
        e.setControleEstoqueAtivo(controlaEstoque);
        return e;
    }

    @Test
    void cancelar_controlaEstoqueTrue_estornaBaixa() throws Exception {
        Pedido pedido = pedidoAutorizado();
        when(pedidoService.buscarComItens(50L)).thenReturn(pedido);
        when(documentoService.buscarPorChave(pedido.getChaveNfe()))
                .thenReturn(Optional.of(documentoComProtocolo()));
        when(cancelamentoService.cancelar(any())).thenReturn("<retEvento/>");
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(true));

        service.cancelar(50L, "Cliente desistiu da compra");

        verify(estoqueService).estornarBaixaItens(pedido.getItens(), 10L, 50L, "sistema");
    }

    @Test
    void cancelar_controlaEstoqueFalse_naoEstornaBaixa() throws Exception {
        Pedido pedido = pedidoAutorizado();
        when(pedidoService.buscarComItens(50L)).thenReturn(pedido);
        when(documentoService.buscarPorChave(pedido.getChaveNfe()))
                .thenReturn(Optional.of(documentoComProtocolo()));
        when(cancelamentoService.cancelar(any())).thenReturn("<retEvento/>");
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(false));

        service.cancelar(50L, "Cliente desistiu da compra");

        verify(estoqueService, never()).estornarBaixaItens(any(), any(), any(), any());
    }

    @Test
    void cancelar_empresaNaoEncontrada_defaultEstornaBaixa() throws Exception {
        Pedido pedido = pedidoAutorizado();
        when(pedidoService.buscarComItens(50L)).thenReturn(pedido);
        when(documentoService.buscarPorChave(pedido.getChaveNfe()))
                .thenReturn(Optional.of(documentoComProtocolo()));
        when(cancelamentoService.cancelar(any())).thenReturn("<retEvento/>");
        when(empresaMapper.buscarPorId(10L)).thenReturn(null);

        service.cancelar(50L, "Cliente desistiu da compra");

        verify(estoqueService).estornarBaixaItens(pedido.getItens(), 10L, 50L, "sistema");
    }
}
