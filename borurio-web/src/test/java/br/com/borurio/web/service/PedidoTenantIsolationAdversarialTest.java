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
import br.com.borurio.fiscal.dto.NfeSefazRetorno;
import br.com.borurio.fiscal.entity.NfeEmissao;
import br.com.borurio.fiscal.service.NfeSefazRetornoParser;
import br.com.borurio.web.dto.AberturaCicloResultado;
import br.com.borurio.web.dto.TipoAberturaCiclo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P0-2 (07-08-2026) — DEPOIS da correção. Este arquivo começou provando o IDOR/isolamento
 * multiempresa (versão original: 2/2 testes passando comprovando que Empresa A conseguia operar
 * Pedido da Empresa B). Agora prova o oposto: a fronteira central
 * (PedidoService.buscarComItensDoTenanteAtual/buscarPorIdDoTenanteAtual, chamada por
 * PedidoEmissaoService.emitir() e pelos 3 métodos de PedidoOperacaoService) bloqueia o acesso
 * cross-tenant ANTES de qualquer efeito colateral, e que o caminho legítimo (mesma empresa,
 * ou fluxo ADMIN sem contexto) continua funcionando exatamente como antes.
 *
 * A lógica real da fronteira (branch com/sem EmpresaContextHolder, delegação pro mapper correto)
 * é testada à parte, sem mock de PedidoService, em PedidoServiceImplTest (borurio-app) — aqui
 * PedidoService é mockado e devolve exatamente o que a implementação real devolveria em cada
 * cenário (NoSuchElementException quando o pedido é de outra empresa), pra provar que
 * PedidoEmissaoService/PedidoOperacaoService reagem corretamente a essa fronteira.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("P0-2 — isolamento multiempresa em operações de Pedido (depois da correção)")
class PedidoTenantIsolationAdversarialTest {

    private static final Long EMPRESA_A_ID = 10L; // tenant autenticado (contexto do chamador)
    private static final Long EMPRESA_B_ID = 20L; // tenant DONO do pedido em cenários cross-tenant

    @AfterEach
    void tearDown() {
        EmpresaContextHolder.clear();
    }

    private Pedido pedido(Long empresaId) {
        Pedido p = new Pedido();
        p.setId(777L);
        p.setEmpresaId(empresaId);
        p.setStatus("RASCUNHO");
        p.setDestCnpjCpf("12345678000199");
        p.setCnpjEmitente("22418179000134");

        PedidoItem item = new PedidoItem();
        item.setProdutoId(1L);
        item.setQuantidade(new BigDecimal("2"));
        item.setCsosn("400");
        item.setCfop("5102");
        p.setItens(List.of(item));
        return p;
    }

    // -------------------------------------------------------------------------
    // Operação 1: POST /pedidos/{id}/emitir — cenários A a G
    // -------------------------------------------------------------------------

    @Nested
    class EmissaoTenant {

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
            org.mockito.MockitoAnnotations.openMocks(this);
            EmitenteProperties emitente = new EmitenteProperties();
            emitente.setCnpj("11222333000181");
            service = new PedidoEmissaoService(pedidoService, nfeGeracaoService, retornoParser,
                    estoqueService, empresaMapper, nfeEmissaoService, nfeReconciliacaoService, emitente);
        }

        @Test
        @DisplayName("A) Empresa A autenticada emite Pedido da própria Empresa A — permitido, comportamento homologado preservado")
        void emitir_pedidoDaMesmaEmpresa_permitido() throws Exception {
            EmpresaContextHolder.set(EMPRESA_A_ID);
            Pedido pedidoDaEmpresaA = pedido(EMPRESA_A_ID);
            // A implementação real devolveria o pedido normalmente — mesma empresa.
            when(pedidoService.buscarComItensDoTenanteAtual(777L)).thenReturn(pedidoDaEmpresaA);
            when(pedidoService.reivindicarParaEmissao(777L)).thenReturn(true);
            when(empresaMapper.buscarPorId(EMPRESA_A_ID)).thenReturn(empresaComEstoque(EMPRESA_A_ID, true));
            when(nfeGeracaoService.resolverIdDest(any(), any())).thenReturn("1");
            NfeEmissao emissao = emissaoReservada();
            when(nfeEmissaoService.abrirCiclo(eq(777L), anyString()))
                    .thenReturn(new AberturaCicloResultado(emissao, TipoAberturaCiclo.NOVA_ABERTURA));
            when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                    .thenReturn(new NfeGeracaoResult("chave123", "<soap/>"));
            NfeSefazRetorno autorizado = new NfeSefazRetorno();
            autorizado.setCStat(100);
            when(retornoParser.parse("<soap/>")).thenReturn(autorizado);

            assertDoesNotThrow(() -> service.emitir(777L));

            // Gate 3 (10-08-2026): atualização de Pedido.status passou a acontecer dentro de
            // NfeEmissaoService.resolverCicloComEfeitos (mockado aqui) — prova correta agora é a
            // delegação, com o status "AUTORIZADO" já corretamente calculado.
            verify(nfeEmissaoService).resolverCicloComEfeitos(anyLong(), eq(NfeEmissao.Estados.AUTORIZADO), anyInt(), any(), any(),
                    eq(777L), eq("AUTORIZADO"), anyString(), anyBoolean(), any(), any(), anyString());
        }

        @Test
        @DisplayName("B) Empresa A autenticada tenta emitir Pedido da Empresa B — rejeitado ANTES do claim/gate")
        void emitir_pedidoDeOutraEmpresa_rejeitadoAntesDeQualquerEfeito() {
            EmpresaContextHolder.set(EMPRESA_A_ID);
            // A implementação real (PedidoServiceImpl.buscarComItensDoTenanteAtual, provado em
            // PedidoServiceImplTest) lança NoSuchElementException quando o pedido pertence a
            // outra empresa — reproduzido aqui no mock.
            when(pedidoService.buscarComItensDoTenanteAtual(777L))
                    .thenThrow(new NoSuchElementException("Pedido não encontrado: id=777"));

            assertThrows(NoSuchElementException.class, () -> service.emitir(777L));

            // C) Pedido da Empresa B não foi alterado.
            verify(pedidoService, never()).atualizarStatus(anyLong(), anyString(), any());
            // Claim atômico nunca chega a ser tentado.
            verify(pedidoService, never()).reivindicarParaEmissao(any());
            // D) e E) nenhum ciclo de nNF é aberto — nfe_emissao/nfe_sequencia nunca são tocados.
            verifyNoInteractions(nfeEmissaoService);
            // F) estoque nunca é reservado/baixado.
            verifyNoInteractions(estoqueService);
            // G) SEFAZ nunca é chamada.
            verifyNoInteractions(nfeGeracaoService);
        }

        @Test
        @DisplayName("I/J) Sem contexto de empresa (fluxo ADMIN) — comportamento irrestrito preservado, como já era antes do P0-2")
        void emitir_semContextoDeEmpresa_comportamentoAdminPreservado() throws Exception {
            EmpresaContextHolder.clear(); // nenhum tenant no contexto — mesma condição do fluxo ADMIN
            Pedido pedidoQualquer = pedido(EMPRESA_B_ID);
            // A implementação real, sem contexto, cai no fallback irrestrito (buscarComItens) —
            // reproduzido aqui devolvendo o pedido normalmente, igual ao comportamento anterior
            // ao P0-2 (a distinção de branch em si já é provada em PedidoServiceImplTest).
            when(pedidoService.buscarComItensDoTenanteAtual(777L)).thenReturn(pedidoQualquer);
            when(pedidoService.reivindicarParaEmissao(777L)).thenReturn(true);
            when(empresaMapper.buscarPorId(EMPRESA_B_ID)).thenReturn(empresaComEstoque(EMPRESA_B_ID, true));
            when(nfeGeracaoService.resolverIdDest(any(), any())).thenReturn("1");
            when(nfeEmissaoService.abrirCiclo(eq(777L), anyString()))
                    .thenReturn(new AberturaCicloResultado(emissaoReservada(), TipoAberturaCiclo.NOVA_ABERTURA));
            when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                    .thenReturn(new NfeGeracaoResult("chave123", "<soap/>"));
            NfeSefazRetorno autorizado = new NfeSefazRetorno();
            autorizado.setCStat(100);
            when(retornoParser.parse("<soap/>")).thenReturn(autorizado);

            assertDoesNotThrow(() -> service.emitir(777L));
        }

        private NfeEmissao emissaoReservada() {
            NfeEmissao e = new NfeEmissao();
            e.setId(1L);
            e.setSerie("1");
            e.setNumeroNfe(1);
            e.setEstado(NfeEmissao.Estados.RESERVADO);
            return e;
        }

        private Empresa empresaComEstoque(Long id, boolean controla) {
            Empresa e = new Empresa();
            e.setId(id);
            e.setControleEstoqueAtivo(controla);
            return e;
        }
    }

    // -------------------------------------------------------------------------
    // Operação 2 (prova sistêmica — H): GET /pedidos/{id}/situacao
    // -------------------------------------------------------------------------

    @Nested
    class SituacaoTenant {

        @Mock PedidoService pedidoService;
        @Mock br.com.borurio.fiscal.service.NfeDocumentoService documentoService;
        @Mock br.com.borurio.fiscal.service.NfeCancelamentoService cancelamentoService;
        @Mock br.com.borurio.fiscal.service.NfeCceService cceService;
        @Mock EstoqueService estoqueService;
        @Mock EmpresaMapper empresaMapper;
        @Mock FiscalContextoResolver contextoResolver;
        @Mock NfeEmissaoService nfeEmissaoService;

        PedidoOperacaoService service;

        @BeforeEach
        void setUp() {
            org.mockito.MockitoAnnotations.openMocks(this);
            service = new PedidoOperacaoService(pedidoService, documentoService,
                    cancelamentoService, cceService, estoqueService, empresaMapper, contextoResolver,
                    nfeEmissaoService);
        }

        @Test
        @DisplayName("H) Empresa A autenticada tenta consultar situação de Pedido da Empresa B — bloqueado, nenhum dado vaza")
        void consultarSituacao_pedidoDeOutraEmpresa_bloqueadoSemVazarDados() {
            EmpresaContextHolder.set(EMPRESA_A_ID);
            when(pedidoService.buscarPorIdDoTenanteAtual(777L))
                    .thenThrow(new NoSuchElementException("Pedido não encontrado: id=777"));

            assertThrows(NoSuchElementException.class, () -> service.consultarSituacao(777L));

            verifyNoInteractions(contextoResolver, documentoService, nfeEmissaoService);
        }

        @Test
        @DisplayName("H) Empresa A autenticada consulta situação do próprio Pedido — permitido")
        void consultarSituacao_pedidoDaMesmaEmpresa_permitido() throws Exception {
            EmpresaContextHolder.set(EMPRESA_A_ID);
            Pedido pedidoDaEmpresaA = pedido(EMPRESA_A_ID);
            pedidoDaEmpresaA.setChaveNfe("35260722418179000134550010000000018147502559");
            pedidoDaEmpresaA.setStatus("AUTORIZADO");
            when(pedidoService.buscarPorIdDoTenanteAtual(777L)).thenReturn(pedidoDaEmpresaA);
            when(documentoService.buscarPorChave(anyString())).thenReturn(java.util.Optional.empty());

            var resultado = assertDoesNotThrow(() -> service.consultarSituacao(777L));

            assertEquals("AUTORIZADO", resultado.get("status"));
            // Gate de contrato OMS (11-08-2026) — P0 de segurança: /situacao nunca deve resolver
            // contexto fiscal/certificado nem tocar a SEFAZ, mesmo no caminho permitido.
            verifyNoInteractions(contextoResolver);
        }
    }
}
