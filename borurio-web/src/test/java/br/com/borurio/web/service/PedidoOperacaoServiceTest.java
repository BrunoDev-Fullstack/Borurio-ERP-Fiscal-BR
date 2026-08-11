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
import br.com.borurio.fiscal.entity.NfeEmissao;
import br.com.borurio.fiscal.service.CertificadoContexto;
import br.com.borurio.fiscal.service.NfeCancelamentoService;
import br.com.borurio.fiscal.service.NfeCceService;
import br.com.borurio.fiscal.service.NfeDocumentoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
    @Mock NfeCancelamentoService cancelamentoService;
    @Mock NfeCceService cceService;
    @Mock EstoqueService estoqueService;
    @Mock EmpresaMapper empresaMapper;
    @Mock FiscalContextoResolver contextoResolver;
    @Mock NfeEmissaoService nfeEmissaoService;

    PedidoOperacaoService service;

    @BeforeEach
    void setUp() {
        service = new PedidoOperacaoService(pedidoService, documentoService,
                cancelamentoService, cceService, estoqueService, empresaMapper, contextoResolver,
                nfeEmissaoService);
    }

    private NfeEmissao emissaoResolvida(String serie, int numeroNfe, String estado, Integer cStat,
                                         String xMotivo, String nProt, String chaveNfe) {
        NfeEmissao e = new NfeEmissao();
        e.setSerie(serie);
        e.setNumeroNfe(numeroNfe);
        e.setEstado(estado);
        e.setCstat(cStat);
        e.setXmotivo(xMotivo);
        e.setNprot(nProt);
        e.setChaveNfe(chaveNfe);
        return e;
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

    // -------------------------------------------------------------------------
    // Gate de contrato OMS (11-08-2026) — nfe_emissao como fonte para pedidos com ciclo; leitura
    // pura do estado persistido (nunca chama a SEFAZ — ver P0 de segurança no próprio serviço).
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("situacao: nunca consulta a SEFAZ ao vivo — sem interação nenhuma com transmissão/consulta SOAP")
    void consultarSituacao_nuncaChamaSefazAoVivo() throws Exception {
        Pedido pedido = pedidoAutorizado(CNPJ_B);
        when(pedidoService.buscarPorIdDoTenanteAtual(50L)).thenReturn(pedido);
        when(nfeEmissaoService.buscarUltimaEmissaoDoPedido(50L)).thenReturn(null);
        when(documentoService.buscarPorChave(pedido.getChaveNfe())).thenReturn(Optional.empty());

        service.consultarSituacao(50L);

        // P0 de segurança do Gate de contrato OMS: /situacao é polling do OMS e não pode contornar
        // o claim/backoff do Gate 3 gerando uma consulta SOAP a cada GET.
        verifyNoInteractions(contextoResolver);
    }

    @Test
    @DisplayName("situacao: AUTORIZADO — serie/numeroNFe/estadoFiscal/xMotivo/nProt vêm de nfe_emissao; cStat preserva tipo String do contrato legado")
    void consultarSituacao_autorizado_fonteNfeEmissao() throws Exception {
        Pedido pedido = pedidoAutorizado(CNPJ_B);
        NfeEmissao emissao = emissaoResolvida("1", 5, NfeEmissao.Estados.AUTORIZADO, 100,
                "Autorizado o uso da NF-e", "135260000001234", pedido.getChaveNfe());

        when(pedidoService.buscarPorIdDoTenanteAtual(50L)).thenReturn(pedido);
        when(nfeEmissaoService.buscarUltimaEmissaoDoPedido(50L)).thenReturn(emissao);
        when(documentoService.buscarPorChave(pedido.getChaveNfe())).thenReturn(Optional.empty());

        Map<String, Object> resp = service.consultarSituacao(50L);

        assertEquals(pedido.getChaveNfe(), resp.get("chaveNfe"));
        assertEquals("1", resp.get("serie"));
        assertEquals(5, resp.get("numeroNFe"));
        assertEquals(NfeEmissao.Estados.AUTORIZADO, resp.get("estadoFiscal"));
        // cStat histórico sempre veio de NfeDocumento como String ("100") — nunca pode virar
        // Integer (100) só porque este pedido tem ciclo em nfe_emissao.
        assertEquals("100", resp.get("cStat"));
        assertInstanceOf(String.class, resp.get("cStat"));
        assertEquals("Autorizado o uso da NF-e", resp.get("xMotivo"));
        assertEquals("135260000001234", resp.get("nProt"));
        // consultaSefaz é campo legado (deprecated): continua presente por retrocompatibilidade,
        // mas sempre null — nunca mais dispara consulta live à SEFAZ.
        assertTrue(resp.containsKey("consultaSefaz"));
        assertNull(resp.get("consultaSefaz"));
    }

    @Test
    @DisplayName("situacao: Pedido.chaveNfe null mas nfe_emissao.chaveNfe congelada (falha de rede na 1ª tentativa) — funciona via nfe_emissao, nunca bloqueia")
    void consultarSituacao_pedidoChaveNulaComEmissaoCongelada_funcionaViaNfeEmissao() throws Exception {
        // Reproduz o cenário real e comprovado em PedidoEmissaoService.emitir(): timeout de rede na
        // PRIMEIRA tentativa cai no catch, que chama atualizarStatus(id, "ERRO",
        // pedido.getChaveNfe()) com o valor ANTERIOR (null, pois é a primeira tentativa) e resolve
        // o ciclo via resolverCiclo (nunca toca Pedido). Resultado: nfe_emissao com chaveNfe
        // congelada + PENDENTE_CONFIRMACAO, Pedido com chaveNfe ainda null e status ERRO.
        Pedido pedido = pedidoAutorizado(CNPJ_B);
        pedido.setChaveNfe(null);
        pedido.setStatus("ERRO");
        NfeEmissao emissao = emissaoResolvida("1", 9, NfeEmissao.Estados.PENDENTE_CONFIRMACAO, null,
                null, null, chaveComCnpj(CNPJ_B));

        when(pedidoService.buscarPorIdDoTenanteAtual(50L)).thenReturn(pedido);
        when(nfeEmissaoService.buscarUltimaEmissaoDoPedido(50L)).thenReturn(emissao);
        when(documentoService.buscarPorChave(chaveComCnpj(CNPJ_B))).thenReturn(Optional.empty());

        Map<String, Object> resp = assertDoesNotThrow(() -> service.consultarSituacao(50L));

        assertEquals(chaveComCnpj(CNPJ_B), resp.get("chaveNfe"));
        assertEquals(NfeEmissao.Estados.PENDENTE_CONFIRMACAO, resp.get("estadoFiscal"));
        assertEquals(9, resp.get("numeroNFe"));
    }

    @Test
    @DisplayName("situacao: PENDENTE_CONFIRMACAO sem resposta SEFAZ — cStat/xMotivo/nProt null, estadoFiscal explícito")
    void consultarSituacao_pendenteSemResposta_camposFiscaisNull() throws Exception {
        Pedido pedido = pedidoAutorizado(CNPJ_B);
        pedido.setStatus("AGUARDANDO");
        NfeEmissao emissao = emissaoResolvida("1", 7, NfeEmissao.Estados.PENDENTE_CONFIRMACAO, null,
                null, null, pedido.getChaveNfe());

        when(pedidoService.buscarPorIdDoTenanteAtual(50L)).thenReturn(pedido);
        when(nfeEmissaoService.buscarUltimaEmissaoDoPedido(50L)).thenReturn(emissao);
        when(documentoService.buscarPorChave(pedido.getChaveNfe())).thenReturn(Optional.empty());

        Map<String, Object> resp = service.consultarSituacao(50L);

        assertEquals("1", resp.get("serie"));
        assertEquals(7, resp.get("numeroNFe"));
        assertEquals(NfeEmissao.Estados.PENDENTE_CONFIRMACAO, resp.get("estadoFiscal"));
        assertNull(resp.get("cStat"));
        assertNull(resp.get("xMotivo"));
        assertNull(resp.get("nProt"));
    }

    @Test
    @DisplayName("situacao: chaveNfe divergente entre Pedido e nfe_emissao — usa a de nfe_emissao e registra a inconsistência (fail-safe, nunca mistura silenciosamente)")
    void consultarSituacao_chaveDivergente_usaFonteNfeEmissao() throws Exception {
        Pedido pedido = pedidoAutorizado(CNPJ_B);
        // Mesmo CNPJ embutido (posições 6-19) que o pedido — só o restante da chave diverge — para
        // isolar exatamente a decisão de fonte, sem disparar DOCUMENTO_CNPJ_DIVERGENTE por um
        // motivo não relacionado ao que este teste prova.
        String chaveComCnpjIgualMasDivergente =
                chaveComCnpj(CNPJ_B).substring(0, 25) + "9" + chaveComCnpj(CNPJ_B).substring(26);
        NfeEmissao emissao = emissaoResolvida("1", 5, NfeEmissao.Estados.AUTORIZADO, 100,
                "Autorizado o uso da NF-e", "135260000001234", chaveComCnpjIgualMasDivergente);

        when(pedidoService.buscarPorIdDoTenanteAtual(50L)).thenReturn(pedido);
        when(nfeEmissaoService.buscarUltimaEmissaoDoPedido(50L)).thenReturn(emissao);
        // A busca em nfe_documento também deve usar a MESMA chave (a de nfe_emissao, vencedora) —
        // nunca a de Pedido, que é a que está divergindo/perdendo aqui.
        when(documentoService.buscarPorChave(chaveComCnpjIgualMasDivergente)).thenReturn(Optional.empty());

        Map<String, Object> resp = service.consultarSituacao(50L);

        // nfe_emissao é a fonte do ciclo fiscal — vence em caso de divergência, nunca mistura.
        assertEquals(emissao.getChaveNfe(), resp.get("chaveNfe"));
        verify(documentoService).buscarPorChave(chaveComCnpjIgualMasDivergente);
        verify(documentoService, never()).buscarPorChave(pedido.getChaveNfe());
    }

    @Test
    @DisplayName("situacao: pedido pré-Gate 1 sem nfe_emissao — cai no fallback legado via nfe_documento")
    void consultarSituacao_semCicloNfeEmissao_fallbackNfeDocumento() throws Exception {
        Pedido pedido = pedidoAutorizado(CNPJ_B);
        NfeDocumento doc = documentoComProtocolo();
        doc.setCStat("100");
        doc.setXMotivo("Autorizado o uso da NF-e");

        when(pedidoService.buscarPorIdDoTenanteAtual(50L)).thenReturn(pedido);
        when(nfeEmissaoService.buscarUltimaEmissaoDoPedido(50L)).thenReturn(null);
        when(documentoService.buscarPorChave(pedido.getChaveNfe())).thenReturn(Optional.of(doc));

        Map<String, Object> resp = service.consultarSituacao(50L);

        assertEquals(pedido.getChaveNfe(), resp.get("chaveNfe"));
        assertEquals("100", resp.get("cStat"));
        assertEquals("Autorizado o uso da NF-e", resp.get("xMotivo"));
        assertEquals("135260000001234", resp.get("nProt"));
        assertNull(resp.get("serie"));
        assertNull(resp.get("numeroNFe"));
        assertNull(resp.get("estadoFiscal"));
    }

    @Test
    @DisplayName("situacao: pedido sem nfe_emissao e sem nfe_documento — não quebra, só devolve os campos base")
    void consultarSituacao_semCicloESemDocumento_naoQuebra() throws Exception {
        Pedido pedido = pedidoAutorizado(CNPJ_B);

        when(pedidoService.buscarPorIdDoTenanteAtual(50L)).thenReturn(pedido);
        when(nfeEmissaoService.buscarUltimaEmissaoDoPedido(50L)).thenReturn(null);
        when(documentoService.buscarPorChave(pedido.getChaveNfe())).thenReturn(Optional.empty());

        Map<String, Object> resp = service.consultarSituacao(50L);

        assertEquals(50L, resp.get("pedidoId"));
        assertEquals(pedido.getChaveNfe(), resp.get("chaveNfe"));
        assertFalse(resp.containsKey("cStat"));
        assertFalse(resp.containsKey("serie"));
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
