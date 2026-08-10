package br.com.borurio.web.service;

import br.com.borurio.app.context.EmpresaContextHolder;
import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.entity.Pedido;
import br.com.borurio.app.entity.PedidoItem;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.app.service.EstoqueService;
import br.com.borurio.app.service.PedidoService;
import br.com.borurio.fiscal.entity.NfeDocumento;
import br.com.borurio.fiscal.service.CertificadoContexto;
import br.com.borurio.fiscal.service.NfeCancelamentoService;
import br.com.borurio.fiscal.service.NfeCceService;
import br.com.borurio.fiscal.service.NfeDocumentoService;
import br.com.borurio.fiscal.service.NfeTransmitService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Cobre a resolução de contexto multi-CNPJ (empresa/certificado corretos em cancelamento,
 * CC-e e consulta, nunca configuração global) e o desenho de "controle de estoque opcional
 * por empresa" no cancelamento.
 */
@ExtendWith(MockitoExtension.class)
class PedidoOperacaoServiceTest {

    private static final String CNPJ_A = "54393421000159";
    private static final String CNPJ_B = "22418179000134";

    @Mock PedidoService pedidoService;
    @Mock NfeDocumentoService documentoService;
    @Mock NfeTransmitService transmitService;
    @Mock NfeCancelamentoService cancelamentoService;
    @Mock NfeCceService cceService;
    @Mock EstoqueService estoqueService;
    @Mock EmpresaMapper empresaMapper;
    @Mock FiscalContextoResolver contextoResolver;

    PedidoOperacaoService service;

    @BeforeEach
    void setUp() {
        service = new PedidoOperacaoService(pedidoService, documentoService, transmitService,
                cancelamentoService, cceService, estoqueService, empresaMapper, contextoResolver);
    }

    /** Todo pedido real sempre tem cnpjEmitente (NOT NULL desde a criação da tabela). */
    private Pedido pedidoAutorizado(String cnpjEmitente) {
        Pedido p = new Pedido();
        p.setId(50L);
        p.setEmpresaId(10L);
        p.setStatus("AUTORIZADO");
        p.setCnpjEmitente(cnpjEmitente);
        p.setChaveNfe(chaveComCnpj(cnpjEmitente));

        PedidoItem item = new PedidoItem();
        item.setProdutoId(1L);
        item.setQuantidade(new BigDecimal("2"));
        p.setItens(List.of(item));
        return p;
    }

    /** Chave de 44 dígitos com o CNPJ nas posições 6-19, mesma estrutura que extrairCnpjDaChave espera. */
    private String chaveComCnpj(String cnpj) {
        String prefixo = "352607";                     // cUF(2) + AAMM(4)
        String sufixo  = "550010000000018147502559";    // mod+serie+nNF+tpEmis+cNF+cDV — 24 dígitos
        return prefixo + cnpj + sufixo;                  // 6 + 14 + 24 = 44
    }

    private NfeDocumento documentoComProtocolo() {
        NfeDocumento doc = new NfeDocumento();
        doc.setNProt("135260000001234");
        return doc;
    }

    private Empresa empresa(Long id, String cnpj, String uf, Boolean controlaEstoque) {
        Empresa e = new Empresa();
        e.setId(id);
        e.setCnpj(cnpj);
        e.setUf(uf);
        e.setControleEstoqueAtivo(controlaEstoque);
        return e;
    }

    private CertificadoContexto certificado(Long empresaId) {
        return new CertificadoContexto(empresaId, null, null, null);
    }

    // -------------------------------------------------------------------------
    // Controle de estoque (comportamento anterior, preservado)
    // -------------------------------------------------------------------------

    @Test
    void cancelar_controlaEstoqueTrue_estornaBaixa() throws Exception {
        Pedido pedido = pedidoAutorizado(CNPJ_A);
        Empresa empresaA = empresa(10L, CNPJ_A, "SP", true);
        when(pedidoService.buscarComItensDoTenanteAtual(50L)).thenReturn(pedido);
        when(documentoService.buscarPorChave(pedido.getChaveNfe()))
                .thenReturn(Optional.of(documentoComProtocolo()));
        when(contextoResolver.resolver(pedido)).thenReturn(new FiscalContexto(empresaA, certificado(10L)));
        when(cancelamentoService.cancelar(any(), any(), any(), any())).thenReturn("<retEvento/>");
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresaA);

        service.cancelar(50L, "Cliente desistiu da compra");

        verify(estoqueService).estornarBaixaItens(pedido.getItens(), 10L, 50L, "sistema");
    }

    @Test
    void cancelar_controlaEstoqueFalse_naoEstornaBaixa() throws Exception {
        Pedido pedido = pedidoAutorizado(CNPJ_A);
        Empresa empresaA = empresa(10L, CNPJ_A, "SP", false);
        when(pedidoService.buscarComItensDoTenanteAtual(50L)).thenReturn(pedido);
        when(documentoService.buscarPorChave(pedido.getChaveNfe()))
                .thenReturn(Optional.of(documentoComProtocolo()));
        when(contextoResolver.resolver(pedido)).thenReturn(new FiscalContexto(empresaA, certificado(10L)));
        when(cancelamentoService.cancelar(any(), any(), any(), any())).thenReturn("<retEvento/>");
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresaA);

        service.cancelar(50L, "Cliente desistiu da compra");

        verify(estoqueService, never()).estornarBaixaItens(any(), any(), any(), any());
    }

    @Test
    void cancelar_empresaEstoqueNaoEncontrada_defaultEstornaBaixa() throws Exception {
        Pedido pedido = pedidoAutorizado(CNPJ_A);
        Empresa empresaA = empresa(10L, CNPJ_A, "SP", true);
        when(pedidoService.buscarComItensDoTenanteAtual(50L)).thenReturn(pedido);
        when(documentoService.buscarPorChave(pedido.getChaveNfe()))
                .thenReturn(Optional.of(documentoComProtocolo()));
        when(contextoResolver.resolver(pedido)).thenReturn(new FiscalContexto(empresaA, certificado(10L)));
        when(cancelamentoService.cancelar(any(), any(), any(), any())).thenReturn("<retEvento/>");
        // empresaMapper.buscarPorId aqui é o lookup interno de controlaEstoque() — null é um
        // cadastro diferente da resolução fiscal (contextoResolver), que já foi bem-sucedida acima.
        when(empresaMapper.buscarPorId(10L)).thenReturn(null);

        service.cancelar(50L, "Cliente desistiu da compra");

        verify(estoqueService).estornarBaixaItens(pedido.getItens(), 10L, 50L, "sistema");
    }

    // -------------------------------------------------------------------------
    // Contexto multi-CNPJ correto (nunca configuração global)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Cancelamento de NF-e da empresa B usa CNPJ/certificado de B, nunca de A")
    void cancelar_empresaB_usaContextoB() throws Exception {
        Pedido pedido = pedidoAutorizado(CNPJ_B);
        Empresa empresaB = empresa(8L, CNPJ_B, "SP", true);
        CertificadoContexto certB = certificado(8L);

        when(pedidoService.buscarComItensDoTenanteAtual(50L)).thenReturn(pedido);
        when(documentoService.buscarPorChave(pedido.getChaveNfe()))
                .thenReturn(Optional.of(documentoComProtocolo()));
        when(contextoResolver.resolver(pedido)).thenReturn(new FiscalContexto(empresaB, certB));
        when(cancelamentoService.cancelar(any(), eq(CNPJ_B), eq("SP"), eq(certB)))
                .thenReturn("<retEvento/>");
        when(empresaMapper.buscarPorId(any())).thenReturn(empresaB);

        service.cancelar(50L, "Cliente desistiu da compra");

        verify(cancelamentoService).cancelar(any(), eq(CNPJ_B), eq("SP"), eq(certB));
        verify(cancelamentoService, never()).cancelar(any());
    }

    @Test
    @DisplayName("CC-e de NF-e da empresa B usa CNPJ/certificado de B, nunca de A")
    void emitirCce_empresaB_usaContextoB() throws Exception {
        Pedido pedido = pedidoAutorizado(CNPJ_B);
        Empresa empresaB = empresa(8L, CNPJ_B, "SP", true);
        CertificadoContexto certB = certificado(8L);

        when(pedidoService.buscarPorIdDoTenanteAtual(50L)).thenReturn(pedido);
        when(contextoResolver.resolver(pedido)).thenReturn(new FiscalContexto(empresaB, certB));
        when(cceService.corrigir(any(), eq(CNPJ_B), eq("SP"), eq(certB))).thenReturn("<retEvento/>");

        service.emitirCce(50L, "Correção do endereço do destinatário na NF-e");

        verify(cceService).corrigir(any(), eq(CNPJ_B), eq("SP"), eq(certB));
        verify(cceService, never()).corrigir(any());
    }

    @Test
    @DisplayName("Consulta de situação da empresa B usa UF de B, não a global")
    void consultarSituacao_empresaB_usaUfB() throws Exception {
        Pedido pedido = pedidoAutorizado(CNPJ_B);
        pedido.setNumero("PED-00000050");
        Empresa empresaB = empresa(8L, CNPJ_B, "SP", true);

        when(pedidoService.buscarPorIdDoTenanteAtual(50L)).thenReturn(pedido);
        when(documentoService.buscarPorChave(pedido.getChaveNfe())).thenReturn(Optional.empty());
        when(contextoResolver.resolver(pedido)).thenReturn(new FiscalContexto(empresaB, certificado(8L)));
        // tpAmb não é injetado pelo Spring fora de contexto real — fica no default do campo (0).
        when(transmitService.consultarNfe(pedido.getChaveNfe(), "SP", 0)).thenReturn("<retorno/>");

        service.consultarSituacao(50L);

        verify(transmitService).consultarNfe(pedido.getChaveNfe(), "SP", 0);
    }

    @Test
    @DisplayName("Isolamento: dois CNPJs do mesmo cliente OMS nunca se misturam")
    void cancelar_doisCnpjsMesmoOms_permanecemIsolados() throws Exception {
        Pedido pedidoA = pedidoAutorizado(CNPJ_A);
        pedidoA.setId(51L);
        Pedido pedidoB = pedidoAutorizado(CNPJ_B);
        pedidoB.setId(52L);

        Empresa empresaA = empresa(1L, CNPJ_A, "SP", true);
        Empresa empresaB = empresa(8L, CNPJ_B, "SP", true);
        CertificadoContexto certA = certificado(1L);
        CertificadoContexto certB = certificado(8L);

        when(pedidoService.buscarComItensDoTenanteAtual(51L)).thenReturn(pedidoA);
        when(pedidoService.buscarComItensDoTenanteAtual(52L)).thenReturn(pedidoB);
        when(documentoService.buscarPorChave(pedidoA.getChaveNfe()))
                .thenReturn(Optional.of(documentoComProtocolo()));
        when(documentoService.buscarPorChave(pedidoB.getChaveNfe()))
                .thenReturn(Optional.of(documentoComProtocolo()));
        when(contextoResolver.resolver(pedidoA)).thenReturn(new FiscalContexto(empresaA, certA));
        when(contextoResolver.resolver(pedidoB)).thenReturn(new FiscalContexto(empresaB, certB));
        when(cancelamentoService.cancelar(any(), any(), any(), any())).thenReturn("<retEvento/>");
        when(empresaMapper.buscarPorId(any())).thenReturn(empresaA);

        service.cancelar(51L, "Cliente A desistiu da compra");
        service.cancelar(52L, "Cliente B desistiu da compra");

        verify(cancelamentoService).cancelar(any(), eq(CNPJ_A), eq("SP"), eq(certA));
        verify(cancelamentoService).cancelar(any(), eq(CNPJ_B), eq("SP"), eq(certB));
    }

    @Test
    @DisplayName("CNPJ da chave divergente do CNPJ do pedido falha explicitamente, sem tentar cancelar")
    void cancelar_cnpjDivergente_falhaExplicita() {
        Pedido pedido = pedidoAutorizado(CNPJ_A);
        // Corrompe a chave pra simular inconsistência entre pedido.cnpjEmitente e o que foi
        // de fato transmitido — cenário que a checagem precisa pegar antes de prosseguir.
        pedido.setChaveNfe(chaveComCnpj(CNPJ_B));

        when(pedidoService.buscarComItensDoTenanteAtual(50L)).thenReturn(pedido);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.cancelar(50L, "Cliente desistiu da compra"));

        assertEquals("DOCUMENTO_CNPJ_DIVERGENTE", ex.getErrorCode());
        verifyNoInteractions(cancelamentoService);
        verifyNoInteractions(estoqueService);
        verifyNoInteractions(contextoResolver);
    }

    @Test
    @DisplayName("Resolução de contexto fiscal falha (empresa/certificado ausente) propaga o erro, não cancela silenciosamente")
    void cancelar_resolucaoDeContextoFalha_propagaErro() throws Exception {
        Pedido pedido = pedidoAutorizado(CNPJ_B);
        when(pedidoService.buscarComItensDoTenanteAtual(50L)).thenReturn(pedido);
        when(documentoService.buscarPorChave(pedido.getChaveNfe()))
                .thenReturn(Optional.of(documentoComProtocolo()));
        when(contextoResolver.resolver(pedido))
                .thenThrow(new IllegalStateException("Certificado não configurado para a empresa CNPJ=" + CNPJ_B));

        assertThrows(IllegalStateException.class, () -> service.cancelar(50L, "Cliente desistiu da compra"));

        verifyNoInteractions(cancelamentoService);
        verifyNoInteractions(estoqueService);
    }

    // -------------------------------------------------------------------------
    // P0-2 (07-08-2026, hardening pós-banca) — isolamento multiempresa em cancelar/CC-e.
    // (situacao é coberto em PedidoTenantIsolationAdversarialTest, junto com a prova de emitir.)
    // -------------------------------------------------------------------------

    @AfterEach
    void limparContextoTenant() {
        EmpresaContextHolder.clear();
    }

    @Test
    @DisplayName("P0-2 H) cancelar: pedido de outra empresa é bloqueado antes de qualquer efeito, nenhum dado vaza")
    void cancelar_pedidoDeOutraEmpresa_bloqueadoAntesDeQualquerEfeito() {
        EmpresaContextHolder.set(99L);
        when(pedidoService.buscarComItensDoTenanteAtual(50L))
                .thenThrow(new NoSuchElementException("Pedido não encontrado: id=50"));

        assertThrows(NoSuchElementException.class, () -> service.cancelar(50L, "Cliente desistiu da compra"));

        verifyNoInteractions(cancelamentoService, contextoResolver, documentoService, estoqueService);
    }

    @Test
    @DisplayName("P0-2 H) CC-e: pedido de outra empresa é bloqueado antes de qualquer efeito, nenhum dado vaza")
    void emitirCce_pedidoDeOutraEmpresa_bloqueadoAntesDeQualquerEfeito() {
        EmpresaContextHolder.set(99L);
        when(pedidoService.buscarPorIdDoTenanteAtual(50L))
                .thenThrow(new NoSuchElementException("Pedido não encontrado: id=50"));

        assertThrows(NoSuchElementException.class, () -> service.emitirCce(50L, "Correção do endereço do destinatário"));

        verifyNoInteractions(cceService, contextoResolver);
    }
}
