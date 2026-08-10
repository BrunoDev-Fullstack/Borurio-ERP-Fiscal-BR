package br.com.borurio.web.service;

import br.com.borurio.app.context.EmpresaContextHolder;
import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.entity.Pedido;
import br.com.borurio.app.entity.PedidoItem;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.app.service.EstoqueService;
import br.com.borurio.app.service.PedidoService;
import br.com.borurio.fiscal.config.EmitenteProperties;
import br.com.borurio.fiscal.dto.NfeGeracaoResult;
import br.com.borurio.fiscal.dto.NfeSefazRetorno;
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
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AUDITORIA ADVERSARIAL (Especialista 4 / Gate 1) — 2026-08-07.
 *
 * Não testa o "caminho feliz" (já coberto por PedidoEmissaoServiceTest). Foco: cenários de
 * crash/retry/classificação de falha que a suíte original não exercitava, procurando
 * especificamente pela divergência entre o que o javadoc de PedidoEmissaoService PROMETE
 * ("falha local pré-rede (XSD/assinatura) → reverte para RESERVADO") e o que
 * falhaOcorreuAntesDaTransmissao() de fato IMPLEMENTA (só reconhece XmlSchemaValidationException).
 *
 * Cada teste documenta o comportamento REAL observado — quando o comportamento é incorreto em
 * relação ao contrato documentado, o teste passa (prova o bug) e o veredito fica no nome/comentário.
 */
@ExtendWith(MockitoExtension.class)
class PedidoEmissaoServiceAdversarialTest {

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
        emitente.setCnpj("11222333000181");
        service = new PedidoEmissaoService(
                pedidoService, nfeGeracaoService, retornoParser, estoqueService, empresaMapper,
                nfeEmissaoService, emitente);
        EmpresaContextHolder.clear();
        lenient().when(pedidoService.reivindicarParaEmissao(anyLong())).thenReturn(true);
        lenient().when(nfeEmissaoService.abrirCiclo(anyLong(), anyString()))
                .thenReturn(new AberturaCicloResultado(emissaoReservada(501L, "1", 101), TipoAberturaCiclo.NOVA_ABERTURA));
        lenient().when(nfeGeracaoService.resolverIdDest(any(), any())).thenReturn("1");
    }

    @AfterEach
    void tearDown() {
        EmpresaContextHolder.clear();
    }

    private NfeEmissao emissaoReservada(long id, String serie, int numero) {
        NfeEmissao e = new NfeEmissao();
        e.setId(id);
        e.setSerie(serie);
        e.setNumeroNfe(numero);
        e.setEstado(NfeEmissao.Estados.RESERVADO);
        e.setTentativas(1);
        return e;
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
        item.setCfop("5102");
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

    // =========================================================================================
    // 1) Falha de ASSINATURA digital antes da rede.
    //
    // CORRIGIDO (P1, 10-08-2026): a fronteira local/transmissão deixou de ser inferida por tipo
    // de exceção (a lista anterior só reconhecia XmlSchemaValidationException) e passou a ser
    // comprovada pela FASE — NfeOrquestradorService.processar() agora envolve SOMENTE a chamada
    // real de transmissão (NfeTransmitService.transmitirXml) e relança qualquer falha dali como
    // SefazTransmissaoIncertaException. Uma falha de assinatura (IllegalArgumentException, mesmo
    // tipo real lançado por AssinaturaXmlService) ocorre ANTES dessa chamada, então nunca é
    // envolvida por essa marca — chega aqui como IllegalArgumentException crua, exatamente como
    // se comportaria na produção real (NfeGeracaoService.gerar() só relança o que processar()
    // lançou, sem tradução).
    // =========================================================================================
    @Test
    @DisplayName("CORRIGIDO: falha de assinatura (IllegalArgumentException, mesmo tipo real lançado por AssinaturaXmlService) " +
            "é reconhecida como falha local — reverte para RESERVADO, nunca PENDENTE_CONFIRMACAO, retorna erro estruturado")
    void falhaAssinatura_classificadaComoLocal_reverteParaReservadoComErroEstruturado() throws Exception {
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        // Mesma exceção que AssinaturaXmlService.assinar()/localizarElementoPorTag() lança de
        // verdade — ocorre dentro de NfeOrquestradorService.processar() ANTES da chamada de
        // transmissão, nunca envolvida por SefazTransmissaoIncertaException.
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("InfNFe não encontrado no XML"));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.emitir(99L));

        assertEquals("LOCAL_PROCESSING_FAILURE", ex.getErrorCode());
        assertFalse(ex.isRetryable(), "falha local não se resolve sozinha com novo envio — precisa de correção");
        assertTrue(ex.getMessage().contains("InfNFe não encontrado no XML"));

        // Zero chamada SEFAZ (nfeGeracaoService já é o próprio mock que "falhou" — a prova real
        // de zero I/O está em NfeOrquestradorService, coberta pela própria estrutura do código:
        // a exceção nasce antes do try que envolve transmitirXml).
        verify(nfeEmissaoService).reverterParaReservadoPorFalhaLocal(501L);
        verify(nfeEmissaoService, never()).resolverCiclo(anyLong(), anyString(), any(), any(), any());
    }

    // =========================================================================================
    // 2) Timeout de rede depois que o estado já é TRANSMITIDO — simula exatamente o que
    //    NfeOrquestradorService.processar() agora produz de verdade: SefazTransmissaoIncertaException
    //    envolvendo o SocketTimeoutException/ConnectException real (ver ponto 4 do prompt de
    //    correção — comportamento conservador de timeout/conexão precisa continuar idêntico).
    // Veredito: continua cobrindo corretamente — PENDENTE_CONFIRMACAO + SEFAZ_TIMEOUT (retryable=true).
    // =========================================================================================
    @Test
    @DisplayName("Timeout real (SocketTimeoutException, envolvido por SefazTransmissaoIncertaException como a produção faz) " +
            "após TRANSMITIDO: PENDENTE_CONFIRMACAO + SEFAZ_TIMEOUT retryable")
    void timeoutRealDeRede_marcaPendenteConfirmacaoELancaSefazTimeout() throws Exception {
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenThrow(new SefazTransmissaoIncertaException(new java.net.SocketTimeoutException("Read timed out")));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.emitir(99L));

        assertEquals("SEFAZ_TIMEOUT", ex.getErrorCode());
        assertTrue(ex.isRetryable());
        verify(nfeEmissaoService).resolverCiclo(501L, NfeEmissao.Estados.PENDENTE_CONFIRMACAO, null, null, null);
        verify(nfeEmissaoService, never()).reverterParaReservadoPorFalhaLocal(any());
    }

    @Test
    @DisplayName("ConnectException (SEFAZ indisponível), envolvido por SefazTransmissaoIncertaException, após TRANSMITIDO: " +
            "PENDENTE_CONFIRMACAO + SEFAZ_UNAVAILABLE retryable")
    void connectExceptionReal_marcaPendenteConfirmacaoELancaSefazUnavailable() throws Exception {
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenThrow(new SefazTransmissaoIncertaException(new java.net.ConnectException("Connection refused")));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.emitir(99L));

        assertEquals("SEFAZ_UNAVAILABLE", ex.getErrorCode());
        assertTrue(ex.isRetryable());
        verify(nfeEmissaoService).resolverCiclo(501L, NfeEmissao.Estados.PENDENTE_CONFIRMACAO, null, null, null);
    }

    // Timeout envelopado (causa aninhada dentro da marca de transmissão incerta) — traduzirFalhaTransmissao percorre a cadeia de causas.
    @Test
    @DisplayName("SocketTimeoutException envelopada em RuntimeException, dentro de SefazTransmissaoIncertaException, ainda é reconhecida via cadeia de causas")
    void timeoutEnvelopado_aindaTraduzidoComoSefazTimeout() throws Exception {
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        RuntimeException envelope = new RuntimeException("Falha ao transmitir",
                new SocketTimeoutException("Read timed out"));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenThrow(new SefazTransmissaoIncertaException(envelope));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.emitir(99L));

        assertEquals("SEFAZ_TIMEOUT", ex.getErrorCode());
    }

    @Test
    @DisplayName("Exceção de rede desconhecida (nem timeout nem connect), mas comprovadamente dentro de SefazTransmissaoIncertaException: " +
            "continua fail-safe conservador — PENDENTE_CONFIRMACAO + SEFAZ_UNAVAILABLE retryable, nunca vira falha local")
    void excecaoDeRedeNaoClassificada_continuaFailSafeConservador() throws Exception {
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenThrow(new SefazTransmissaoIncertaException(new javax.net.ssl.SSLException("handshake falhou no meio da transmissão")));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.emitir(99L));

        assertEquals("SEFAZ_UNAVAILABLE", ex.getErrorCode());
        assertTrue(ex.isRetryable());
        verify(nfeEmissaoService).resolverCiclo(501L, NfeEmissao.Estados.PENDENTE_CONFIRMACAO, null, null, null);
        verify(nfeEmissaoService, never()).reverterParaReservadoPorFalhaLocal(any());
    }

    // =========================================================================================
    // 3) Duplicidade de chave_nfe (uk_nfe_emissao_chave) — o que acontece no nível de aplicação?
    //
    // CORRIGIDO (P1, 10-08-2026): NfeGeracaoService.gerar() chama
    // nfeEmissaoService.marcarTransmitido(emissaoId, chave) ANTES até de NfeOrquestradorService
    // .processar() ser chamado — muito antes da fronteira de rede. Uma DuplicateKeyException aqui
    // nunca é (e nunca poderia ser) envolvida por SefazTransmissaoIncertaException, então a nova
    // fronteira baseada em fase a reconhece corretamente como local, sem precisar adicionar
    // DuplicateKeyException a lista nenhuma. Colisão de chave é rara (cNF é aleatório de 8
    // dígitos) mas, quando ocorre, exige investigação — por isso retryable=false, mesmo revertendo
    // o ciclo pra RESERVADO (número não é consumido nem perdido, só não é auto-retentado às cegas).
    // =========================================================================================
    @Test
    @DisplayName("CORRIGIDO: violação de UNIQUE KEY em marcarTransmitido (falha 100% local, pré-rede) " +
            "reverte para RESERVADO, não consome nNF, não avança sequência, retorna erro estruturado não-retryable")
    void duplicateKeyEmMarcarTransmitido_classificadaComoLocal_reverteParaReservadoSemConsumirNumero() throws Exception {
        Pedido pedido = pedidoRascunho();
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        when(nfeGeracaoService.gerar(any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("uk_nfe_emissao_chave"));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.emitir(99L));

        assertEquals("LOCAL_PROCESSING_FAILURE", ex.getErrorCode());
        assertFalse(ex.isRetryable(), "colisão de chave não deve ser assumida como retryable — exige investigação");

        // Número não consumido, sequência não avançada: resolverCiclo (o único caminho que chama
        // sequenciaService.consumirNumero/liberarGate) nunca é chamado neste fluxo.
        verify(nfeEmissaoService, never()).resolverCiclo(anyLong(), anyString(), any(), any(), any());
        // Gate volta pra RESERVADO — nem perdido, nem travado esperando reconciliação inexistente.
        verify(nfeEmissaoService).reverterParaReservadoPorFalhaLocal(501L);
    }

    // =========================================================================================
    // 4) Retry do MESMO pedido em EMITINDO com ciclo em TRANSMITIDO/PENDENTE_CONFIRMACAO —
    //    NfeEmissaoService.abrirCiclo() (mockado aqui, comportamento real coberto em
    //    NfeEmissaoServiceTest) lança EMISSAO_AGUARDANDO_RECONCILIACAO. O que PedidoEmissaoService
    //    faz com isso?
    //
    // ACHADO (P2 — observação, não necessariamente bug): o catch genérico ao redor de
    // abrirCiclo() marca Pedido.status = "ERRO" mesmo quando a causa é
    // EMISSAO_AGUARDANDO_RECONCILIACAO (retryable=true, resultado real ainda desconhecido/pode
    // vir a ser AUTORIZADO). Do ponto de vista de suporte/observabilidade, "ERRO" é enganoso
    // aqui — sugere falha definitiva quando na verdade a NF-e pode ainda ser autorizada do lado
    // da SEFAZ. Não é inseguro (o gate real continua em nfe_emissao/nfe_sequencia, não em
    // Pedido.status — uma nova chamada de /emitir vai bater no mesmo bloqueio de novo), mas é
    // uma superfície de confusão operacional.
    // =========================================================================================
    @Test
    @DisplayName("Retry do mesmo pedido (EMITINDO) com ciclo TRANSMITIDO: propaga EMISSAO_AGUARDANDO_RECONCILIACAO " +
            "e marca Pedido.status=ERRO (observação: rótulo enganoso para um resultado ainda incerto)")
    void retomadaEmitindoComCicloTransmitido_propagaReconciliacaoEMarcaErro() throws Exception {
        Pedido pedido = pedidoComStatus("EMITINDO");
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        NfeEmissao ultimaEmissao = emissaoReservada(501L, "1", 101);
        ultimaEmissao.setEstado(NfeEmissao.Estados.TRANSMITIDO); // não-terminal
        when(nfeEmissaoService.buscarUltimaEmissaoDoPedido(99L)).thenReturn(ultimaEmissao);
        when(nfeEmissaoService.abrirCiclo(eq(99L), anyString()))
                .thenThrow(BusinessException.emissaoAguardandoReconciliacao(99L));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.emitir(99L));

        assertEquals("EMISSAO_AGUARDANDO_RECONCILIACAO", ex.getErrorCode());
        assertTrue(ex.isRetryable());
        verify(pedidoService).atualizarStatus(99L, "ERRO", pedido.getChaveNfe());
        verify(nfeGeracaoService, never()).gerar(any(), any(), any(), any());
        // Claim atômico não é repetido nesta retomada — igual ao caminho feliz de retomada.
        verify(pedidoService, never()).reivindicarParaEmissao(any());
    }

    @Test
    @DisplayName("Retry do mesmo pedido (EMITINDO) com ciclo PENDENTE_CONFIRMACAO: mesmo bloqueio de reconciliação")
    void retomadaEmitindoComCicloPendenteConfirmacao_propagaReconciliacao() throws Exception {
        Pedido pedido = pedidoComStatus("EMITINDO");
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(empresaMapper.buscarPorId(10L)).thenReturn(empresa(10L, true));
        NfeEmissao ultimaEmissao = emissaoReservada(501L, "1", 101);
        ultimaEmissao.setEstado(NfeEmissao.Estados.PENDENTE_CONFIRMACAO);
        when(nfeEmissaoService.buscarUltimaEmissaoDoPedido(99L)).thenReturn(ultimaEmissao);
        when(nfeEmissaoService.abrirCiclo(eq(99L), anyString()))
                .thenThrow(BusinessException.emissaoAguardandoReconciliacao(99L));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.emitir(99L));

        assertEquals("EMISSAO_AGUARDANDO_RECONCILIACAO", ex.getErrorCode());
        verify(nfeGeracaoService, never()).gerar(any(), any(), any(), any());
    }

    // =========================================================================================
    // 5) Retry do mesmo pedido com ciclo já DENEGADO (estado terminal, mas hoje inalcançável
    //    pelo fluxo automático — resolverEstadoEmissao() nunca produz DENEGADO; só existiria via
    //    Gate 2, futuro, ou correção manual de dado).
    //
    // ACHADO (P2 — landmine documentada para Gate 2): mapearStatusPedido() só reconhece
    // AUTORIZADO e AGUARDANDO_CORRECAO explicitamente; qualquer outro valor (inclusive DENEGADO)
    // cai no default "AGUARDANDO". Se/quando Gate 2 passar a produzir DENEGADO de verdade, um
    // pedido cujo ciclo já está definitivamente DENEGADO vai ter Pedido.status corrigido para
    // "AGUARDANDO" — o MESMO status usado para "ainda não temos certeza" — dentro de uma
    // exceção chamada justamente PEDIDO_JA_RESOLVIDO. Ou seja: o texto da exceção diz "já
    // resolvido" mas o status gravado diz "aguardando". Confuso para quem consome a API/OMS.
    // =========================================================================================
    @Test
    @DisplayName("BUG LATENTE P2: ciclo terminal DENEGADO mapeia Pedido.status para \"AGUARDANDO\" (não \"REJEITADO\" nem outro rótulo terminal) " +
            "— inconsistente com PEDIDO_JA_RESOLVIDO alegar que o resultado é definitivo")
    void retomadaComCicloDenegado_mapeiaStatusParaAguardando_documentaLandmine() {
        Pedido pedido = pedidoComStatus("EMITINDO");
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        NfeEmissao emissaoDenegada = emissaoReservada(501L, "1", 101);
        emissaoDenegada.setEstado(NfeEmissao.Estados.DENEGADO);
        when(nfeEmissaoService.buscarUltimaEmissaoDoPedido(99L)).thenReturn(emissaoDenegada);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.emitir(99L));

        assertEquals("PEDIDO_JA_RESOLVIDO", ex.getErrorCode());
        // Comportamento real observado — status gravado é "AGUARDANDO", não algo como "DENEGADO"/"REJEITADO".
        verify(pedidoService).atualizarStatus(99L, "AGUARDANDO", pedido.getChaveNfe());
        verifyNoInteractions(nfeGeracaoService);
    }

    // =========================================================================================
    // 6) Crash simulado depois que Pedido.status vira EMITINDO e ANTES de abrirCiclo() ser
    //    chamado (nenhuma nfe_emissao existe ainda) — a suíte original já cobre isso
    //    (emitir_emitindoSemCiclo_lancaPedidoEmissaoInconsistente em PedidoEmissaoServiceTest).
    //    Reproduzido aqui de forma adversarial extra: garante que NENHUM efeito colateral roda,
    //    nem mesmo a leitura de empresa/estoque.
    // Veredito: cobre corretamente.
    // =========================================================================================
    @Test
    @DisplayName("Crash antes de abrirCiclo (EMITINDO sem ciclo nenhum): nenhuma leitura de empresa, nenhum efeito colateral")
    void crashAntesDeAbrirCiclo_nenhumEfeitoColateral() {
        Pedido pedido = pedidoComStatus("EMITINDO");
        when(pedidoService.buscarComItensDoTenanteAtual(99L)).thenReturn(pedido);
        when(nfeEmissaoService.buscarUltimaEmissaoDoPedido(99L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.emitir(99L));

        assertEquals("PEDIDO_EMISSAO_INCONSISTENTE", ex.getErrorCode());
        assertFalse(ex.isRetryable(), "exige verificação manual — não deveria ser retryable automático");
        verifyNoInteractions(empresaMapper, estoqueService, nfeGeracaoService);
        verify(pedidoService, never()).atualizarStatus(anyLong(), anyString(), any());
    }
}
