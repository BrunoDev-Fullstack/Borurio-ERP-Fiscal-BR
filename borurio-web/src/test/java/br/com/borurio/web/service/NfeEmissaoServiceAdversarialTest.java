package br.com.borurio.web.service;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.app.mapper.PedidoMapper;
import br.com.borurio.app.service.EstoqueService;
import br.com.borurio.fiscal.config.SefazReconciliacaoProperties;
import br.com.borurio.fiscal.dto.AtualizacaoSequenciaResultado;
import br.com.borurio.fiscal.entity.NfeEmissao;
import br.com.borurio.fiscal.entity.NfeSequencia;
import br.com.borurio.fiscal.mapper.NfeEmissaoMapper;
import br.com.borurio.fiscal.mapper.NfeSequenciaMapper;
import br.com.borurio.fiscal.service.NfeSequenciaService;
import br.com.borurio.fiscal.service.impl.NfeSequenciaServiceImpl;
import br.com.borurio.web.dto.AberturaCicloResultado;
import br.com.borurio.web.dto.TipoAberturaCiclo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AUDITORIA ADVERSARIAL (Especialista 4 / Gate 1) — 2026-08-07. Complementa
 * NfeEmissaoServiceTest com os cenários da checklist de revisão que a suíte original não
 * exercitava explicitamente: bloqueio de "outro pedido" em TODOS os estados não-terminais
 * (não só os dois já cobertos), isolamento entre empresas (CNPJ) diferentes, o off-by-one da
 * sincronização OMS encadeado de ponta a ponta (atualizarSequencia real → abrirCiclo real), e a
 * invariante central do Gate 1 — número N+1 nunca é alocável enquanto o número N não tiver
 * destino definitivo — testada para as três formas de "número N ainda incerto"
 * (AGUARDANDO_CORRECAO, PENDENTE_CONFIRMACAO, e a sequência real AGUARDANDO_CORRECAO→retry→
 * AUTORIZADO).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NfeEmissaoService — auditoria adversarial (Gate 1)")
class NfeEmissaoServiceAdversarialTest {

    @Mock EmpresaMapper empresaMapper;
    @Mock PedidoMapper pedidoMapper;
    @Mock NfeSequenciaService sequenciaService;
    @Mock NfeEmissaoMapper nfeEmissaoMapper;
    @Mock EstoqueService estoqueService;

    NfeEmissaoService service;

    private static final String CNPJ_A = "22418179000134";
    private static final String CNPJ_B = "54393421000159";
    private static final Long PEDIDO_A = 99L;   // dono legítimo do gate nos testes de bloqueio
    private static final Long PEDIDO_B = 77L;   // pretendente concorrente

    @BeforeEach
    void setUp() {
        service = new NfeEmissaoService(empresaMapper, pedidoMapper, sequenciaService, nfeEmissaoMapper,
                estoqueService, new SefazReconciliacaoProperties());
    }

    private Empresa empresa(Long id, String serie) {
        Empresa e = new Empresa();
        e.setId(id);
        e.setSerieNfePadrao(serie);
        return e;
    }

    private NfeSequencia sequencia(String cnpj, int ultimoNumero, Long emissaoAtivaId) {
        NfeSequencia seq = new NfeSequencia();
        seq.setCnpjEmitente(cnpj);
        seq.setSerie("1");
        seq.setUltimoNumero(ultimoNumero);
        seq.setEmissaoAtivaId(emissaoAtivaId);
        return seq;
    }

    private NfeEmissao emissao(Long id, Long pedidoId, String cnpj, String estado, int numero) {
        NfeEmissao e = new NfeEmissao();
        e.setId(id);
        e.setPedidoId(pedidoId);
        e.setCnpjEmitente(cnpj);
        e.setSerie("1");
        e.setNumeroNfe(numero);
        e.setEstado(estado);
        e.setTentativas(1);
        return e;
    }

    // =========================================================================================
    // 1) "Outro pedido, MESMA série" — cobertura dos DEMAIS estados não-terminais além dos dois
    //    já testados em NfeEmissaoServiceTest (AGUARDANDO_CORRECAO e TRANSMITIDO). O código faz
    //    a checagem de dono ANTES de olhar o estado (retomarCicloAtivo: `if (!ativa.getPedidoId()
    //    .equals(pedidoId)) throw ...` roda primeiro, incondicional ao estado) — então RESERVADO
    //    e PENDENTE_CONFIRMACAO por outro pedido DEVEM bloquear igual. Comprovado abaixo em vez
    //    de assumido pela leitura do código.
    // Veredito: cobre corretamente (a checagem é estado-agnóstica, como o código sugere).
    // =========================================================================================
    @Test
    @DisplayName("Outro pedido dono do gate em RESERVADO (ainda nem transmitiu) também bloqueia — não é 'roubável'")
    void abrirCiclo_outroPedidoEmReservado_bloqueiaIgualAosDemaisEstados() {
        when(empresaMapper.buscarPorCnpjParaAtualizar(CNPJ_A)).thenReturn(empresa(8L, "1"));
        when(sequenciaService.buscarOuCriarParaAtualizar(CNPJ_A, "1")).thenReturn(sequencia(CNPJ_A, 4, 501L));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L))
                .thenReturn(emissao(501L, PEDIDO_A, CNPJ_A, NfeEmissao.Estados.RESERVADO, 5));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.abrirCiclo(PEDIDO_B, CNPJ_A));

        assertEquals("EMISSAO_EM_ANDAMENTO_NA_SERIE", ex.getErrorCode());
        verify(nfeEmissaoMapper, never()).inserir(any());
    }

    @Test
    @DisplayName("Outro pedido dono do gate em PENDENTE_CONFIRMACAO também bloqueia")
    void abrirCiclo_outroPedidoEmPendenteConfirmacao_bloqueiaIgualAosDemaisEstados() {
        when(empresaMapper.buscarPorCnpjParaAtualizar(CNPJ_A)).thenReturn(empresa(8L, "1"));
        when(sequenciaService.buscarOuCriarParaAtualizar(CNPJ_A, "1")).thenReturn(sequencia(CNPJ_A, 4, 501L));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L))
                .thenReturn(emissao(501L, PEDIDO_A, CNPJ_A, NfeEmissao.Estados.PENDENTE_CONFIRMACAO, 5));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.abrirCiclo(PEDIDO_B, CNPJ_A));

        assertEquals("EMISSAO_EM_ANDAMENTO_NA_SERIE", ex.getErrorCode());
        assertTrue(ex.isRetryable());
    }

    // =========================================================================================
    // 2) Empresa (CNPJ) diferente concorrente — não deve ser afetada pelo gate de outra empresa.
    // A chave do gate é o par (cnpjEmitente, serie): buscarOuCriarParaAtualizar/ocuparGate/
    // liberarGate são todos parametrizados por cnpjEmitente. Prova: gate de CNPJ_A ocupado por
    // PEDIDO_A não impede PEDIDO_B (outro pedido, CNPJ_B) de abrir ciclo novo em CNPJ_B na MESMA
    // chamada de teste — sem qualquer interação cruzada entre os dois mocks de sequência.
    // Veredito: cobre corretamente (isolamento por design de chave composta).
    // =========================================================================================
    @Test
    @DisplayName("CNPJ diferente nunca é afetado pelo gate ocupado de outro CNPJ, mesma série")
    void abrirCiclo_empresasDiferentes_saoIndependentes() {
        // CNPJ_A: gate ocupado por PEDIDO_A, ainda em AGUARDANDO_CORRECAO.
        when(empresaMapper.buscarPorCnpjParaAtualizar(CNPJ_A)).thenReturn(empresa(8L, "1"));
        when(sequenciaService.buscarOuCriarParaAtualizar(CNPJ_A, "1")).thenReturn(sequencia(CNPJ_A, 4, 501L));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L))
                .thenReturn(emissao(501L, PEDIDO_A, CNPJ_A, NfeEmissao.Estados.AGUARDANDO_CORRECAO, 5));

        // CNPJ_B: gate livre, sequência independente (ultimoNumero=20).
        when(empresaMapper.buscarPorCnpjParaAtualizar(CNPJ_B)).thenReturn(empresa(9L, "1"));
        when(sequenciaService.buscarOuCriarParaAtualizar(CNPJ_B, "1")).thenReturn(sequencia(CNPJ_B, 20, null));
        doAnswer(inv -> {
            NfeEmissao e = inv.getArgument(0);
            e.setId(900L);
            return 1;
        }).when(nfeEmissaoMapper).inserir(argThat(e -> CNPJ_B.equals(e.getCnpjEmitente())));

        // PEDIDO_B tentando CNPJ_A é bloqueado...
        assertThrows(BusinessException.class, () -> service.abrirCiclo(PEDIDO_B, CNPJ_A));

        // ...mas o MESMO PEDIDO_B em CNPJ_B (empresa diferente) abre normalmente, número 21.
        AberturaCicloResultado resultado = service.abrirCiclo(PEDIDO_B, CNPJ_B);

        assertEquals(TipoAberturaCiclo.NOVA_ABERTURA, resultado.tipo());
        assertEquals(21, resultado.emissao().getNumeroNfe());
        verify(sequenciaService).ocuparGate(CNPJ_B, "1", 900L);
        // Nunca tocou o gate de CNPJ_A a partir da chamada de CNPJ_B.
        verify(sequenciaService, never()).ocuparGate(eq(CNPJ_A), any(), any());
    }

    // =========================================================================================
    // 3) Sequência sincronizada pela OMS começando em 5 — confirma que o PRIMEIRO nNF realmente
    // emitido é 5, não 6, encadeando o comportamento REAL de NfeSequenciaServiceImpl
    // (atualizarSequencia grava ultimoNumero = proximoNumero - 1) com o comportamento REAL de
    // NfeEmissaoService.abrirCicloNovo (candidato = ultimoNumero + 1) — usando uma implementação
    // real de NfeSequenciaService (não mock) para não mascarar o off-by-one em dois testes
    // desconectados.
    // Veredito: cobre corretamente — off-by-one NÃO existe (4+1=5, não 5+1=6).
    // =========================================================================================
    @Test
    @DisplayName("OMS sincroniza proximoNumero=5 → primeiro nNF emitido é 5 (não 6) — sem off-by-one")
    void sequenciaOms_proximoNumeroCinco_primeiroNnfEmitidoE5() {
        NfeSequenciaMapper sequenciaMapper = mock(NfeSequenciaMapper.class);
        NfeSequenciaService sequenciaReal = new NfeSequenciaServiceImpl(sequenciaMapper);

        // Estado em memória simples: primeira leitura null (sequência nova), leituras seguintes
        // devolvem o que foi de fato persistido pelo insert/update anteriores.
        Map<String, NfeSequencia> estado = new HashMap<>();
        when(sequenciaMapper.buscarParaAtualizar(eq(CNPJ_A), eq("1"))).thenAnswer(inv -> estado.get("1"));
        doAnswer(inv -> {
            NfeSequencia s = inv.getArgument(0);
            NfeSequencia copia = new NfeSequencia();
            copia.setCnpjEmitente(s.getCnpjEmitente());
            copia.setSerie(s.getSerie());
            copia.setUltimoNumero(s.getUltimoNumero());
            estado.put("1", copia);
            return null;
        }).when(sequenciaMapper).inserir(any());

        // 1) OMS sincroniza proximoNumero=5.
        AtualizacaoSequenciaResultado resultado = sequenciaReal.atualizarSequencia(CNPJ_A, "1", 5);
        assertEquals(4, estado.get("1").getUltimoNumero(), "ultimoNumero gravado precisa ser proximoNumero-1=4");
        assertEquals(5, resultado.proximoNumeroAtual());

        // 2) NfeEmissaoService.abrirCiclo, usando essa MESMA implementação real de sequenciaService
        //    (não um mock reafirmando o número à mão) — candidato precisa ser 5.
        NfeEmissaoService serviceReal = new NfeEmissaoService(empresaMapper, pedidoMapper, sequenciaReal, nfeEmissaoMapper,
                estoqueService, new SefazReconciliacaoProperties());
        when(empresaMapper.buscarPorCnpjParaAtualizar(CNPJ_A)).thenReturn(empresa(8L, "1"));
        doAnswer(inv -> {
            NfeEmissao e = inv.getArgument(0);
            e.setId(701L);
            return 1;
        }).when(nfeEmissaoMapper).inserir(any(NfeEmissao.class));

        AberturaCicloResultado abertura = serviceReal.abrirCiclo(PEDIDO_A, CNPJ_A);

        assertEquals(5, abertura.emissao().getNumeroNfe(),
                "primeiro nNF real emitido após sync OMS(proximoNumero=5) precisa ser 5, nunca 6");
    }

    // =========================================================================================
    // 4) Invariante central do Gate 1: nenhum caminho permite o número 4 ser transmitido/
    // autorizado antes do número 3 ter resultado definitivo — para as combinações relevantes de
    // rejeição/timeout do número 3. Comprova em três variações consecutivas dentro do MESMO
    // teste (cada uma reconfigurando os mocks para simular uma "rodada" diferente do número 3).
    // =========================================================================================
    @Test
    @DisplayName("Número 4 bloqueado enquanto número 3 está AGUARDANDO_CORRECAO — só libera após resolução terminal")
    void numero4Bloqueado_enquantoNumero3AguardandoCorrecao() {
        // Número 3 (pedido A) foi transmitido e voltou rejeitado (cStat>=200) → AGUARDANDO_CORRECAO.
        when(empresaMapper.buscarPorCnpjParaAtualizar(CNPJ_A)).thenReturn(empresa(8L, "1"));
        when(sequenciaService.buscarOuCriarParaAtualizar(CNPJ_A, "1")).thenReturn(sequencia(CNPJ_A, 2, 501L));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L))
                .thenReturn(emissao(501L, PEDIDO_A, CNPJ_A, NfeEmissao.Estados.AGUARDANDO_CORRECAO, 3));

        // Pedido B tenta abrir para pegar o "próximo" número — deve ser recusado, nunca recebe 4.
        BusinessException ex = assertThrows(BusinessException.class, () -> service.abrirCiclo(PEDIDO_B, CNPJ_A));
        assertEquals("EMISSAO_EM_ANDAMENTO_NA_SERIE", ex.getErrorCode());
        verify(sequenciaService, never()).consumirNumero(any(), any(), anyInt());
        verify(nfeEmissaoMapper, never()).inserir(any());

        // Só depois que o número 3 chega a um estado TERMINAL (AUTORIZADO) o gate libera —
        // resolverCiclo() avança nfe_sequencia.ultimo_numero para 3 e zera emissao_ativa_id.
        NfeEmissao ativa3 = emissao(501L, PEDIDO_A, CNPJ_A, NfeEmissao.Estados.AGUARDANDO_CORRECAO, 3);
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L)).thenReturn(ativa3);
        // P0-3 (07-08-2026): resolverCiclo agora trava nfe_sequencia ANTES de nfe_emissao para
        // transições terminais — precisa da pré-leitura não bloqueante + do gate apontando pra 501L.
        when(nfeEmissaoMapper.buscarPorId(501L)).thenReturn(ativa3);
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ_A, "1")).thenReturn(sequencia(CNPJ_A, 2, 501L));
        service.resolverCiclo(501L, NfeEmissao.Estados.AUTORIZADO, 100, null, "prot-3");

        verify(sequenciaService).consumirNumero(CNPJ_A, "1", 3);
        verify(sequenciaService).liberarGate(CNPJ_A, "1");
    }

    @Test
    @DisplayName("Número 4 bloqueado enquanto número 3 está PENDENTE_CONFIRMACAO (timeout) — só libera após resolução terminal")
    void numero4Bloqueado_enquantoNumero3PendenteConfirmacao() {
        when(empresaMapper.buscarPorCnpjParaAtualizar(CNPJ_A)).thenReturn(empresa(8L, "1"));
        when(sequenciaService.buscarOuCriarParaAtualizar(CNPJ_A, "1")).thenReturn(sequencia(CNPJ_A, 2, 501L));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L))
                .thenReturn(emissao(501L, PEDIDO_A, CNPJ_A, NfeEmissao.Estados.PENDENTE_CONFIRMACAO, 3));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.abrirCiclo(PEDIDO_B, CNPJ_A));
        assertEquals("EMISSAO_EM_ANDAMENTO_NA_SERIE", ex.getErrorCode());

        // Reconciliação (fora do escopo do Gate 1) eventualmente resolve como DENEGADO.
        NfeEmissao ativa3 = emissao(501L, PEDIDO_A, CNPJ_A, NfeEmissao.Estados.PENDENTE_CONFIRMACAO, 3);
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L)).thenReturn(ativa3);
        when(nfeEmissaoMapper.buscarPorId(501L)).thenReturn(ativa3);
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ_A, "1")).thenReturn(sequencia(CNPJ_A, 2, 501L));
        service.resolverCiclo(501L, NfeEmissao.Estados.DENEGADO, 110, "Uso Denegado", null);

        verify(sequenciaService).consumirNumero(CNPJ_A, "1", 3);
        verify(sequenciaService).liberarGate(CNPJ_A, "1");
    }

    @Test
    @DisplayName("Número 4 só é alocável DEPOIS que número 3 libera o gate — sequência completa AGUARDANDO_CORRECAO → retry → AUTORIZADO → abre 4")
    void numero4SoAlocadoAposLiberacaoDoNumero3_sequenciaCompleta() {
        // --- Rodada 1: pedido A abre número 3 (gate livre, ultimoNumero=2). ---
        when(empresaMapper.buscarPorCnpjParaAtualizar(CNPJ_A)).thenReturn(empresa(8L, "1"));
        NfeSequencia seqGateLivre = sequencia(CNPJ_A, 2, null);
        NfeSequencia seqGateOcupado = sequencia(CNPJ_A, 2, 501L);
        when(sequenciaService.buscarOuCriarParaAtualizar(CNPJ_A, "1"))
                .thenReturn(seqGateLivre)   // abertura do pedido A
                .thenReturn(seqGateOcupado) // tentativa concorrente do pedido B (bloqueada)
                .thenReturn(seqGateOcupado); // retomada do próprio pedido A (AGUARDANDO_CORRECAO)
        doAnswer(inv -> {
            NfeEmissao e = inv.getArgument(0);
            e.setId(501L);
            return 1;
        }).when(nfeEmissaoMapper).inserir(any(NfeEmissao.class));

        AberturaCicloResultado abertura3 = service.abrirCiclo(PEDIDO_A, CNPJ_A);
        assertEquals(3, abertura3.emissao().getNumeroNfe());
        verify(sequenciaService).ocuparGate(CNPJ_A, "1", 501L);

        // --- Pedido B tenta pegar o "próximo" enquanto 3 ainda está em voo (RESERVADO). ---
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L))
                .thenReturn(emissao(501L, PEDIDO_A, CNPJ_A, NfeEmissao.Estados.RESERVADO, 3));
        assertThrows(BusinessException.class, () -> service.abrirCiclo(PEDIDO_B, CNPJ_A));

        // --- Número 3 é transmitido e rejeitado (cStat>=200) → AGUARDANDO_CORRECAO. Gate continua ocupado. ---
        service.resolverCiclo(501L, NfeEmissao.Estados.AGUARDANDO_CORRECAO, 225, "Erro de schema", null);
        verify(sequenciaService, never()).consumirNumero(any(), any(), anyInt());
        verify(sequenciaService, never()).liberarGate(any(), any());

        // --- Pedido A tenta de novo (retry) — reaproveita o MESMO número 3, nunca pula pro 4. ---
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L))
                .thenReturn(emissao(501L, PEDIDO_A, CNPJ_A, NfeEmissao.Estados.AGUARDANDO_CORRECAO, 3));
        AberturaCicloResultado retomada = service.abrirCiclo(PEDIDO_A, CNPJ_A);
        assertEquals(TipoAberturaCiclo.RETOMADA_AGUARDANDO_CORRECAO, retomada.tipo());
        assertEquals(3, retomada.emissao().getNumeroNfe(), "retry reaproveita o número 3 — nunca aloca 4 aqui");

        // --- Desta vez a SEFAZ autoriza (cStat=100) → AUTORIZADO, terminal: consome 3 e libera o gate. ---
        NfeEmissao ativa3Retomada = emissao(501L, PEDIDO_A, CNPJ_A, NfeEmissao.Estados.TRANSMITIDO, 3);
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(501L)).thenReturn(ativa3Retomada);
        when(nfeEmissaoMapper.buscarPorId(501L)).thenReturn(ativa3Retomada);
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ_A, "1")).thenReturn(sequencia(CNPJ_A, 2, 501L));
        service.resolverCiclo(501L, NfeEmissao.Estados.AUTORIZADO, 100, null, "prot-3-final");
        verify(sequenciaService).consumirNumero(CNPJ_A, "1", 3);
        verify(sequenciaService).liberarGate(CNPJ_A, "1");

        // --- SÓ AGORA o gate está livre: pedido B abre e recebe o número 4, nunca antes disso. ---
        when(sequenciaService.buscarOuCriarParaAtualizar(CNPJ_A, "1")).thenReturn(sequencia(CNPJ_A, 3, null));
        doAnswer(inv -> {
            NfeEmissao e = inv.getArgument(0);
            e.setId(502L);
            return 1;
        }).when(nfeEmissaoMapper).inserir(any(NfeEmissao.class));

        AberturaCicloResultado abertura4 = service.abrirCiclo(PEDIDO_B, CNPJ_A);
        assertEquals(4, abertura4.emissao().getNumeroNfe(), "número 4 só é alocável depois do número 3 ter destino definitivo");
        assertEquals(TipoAberturaCiclo.NOVA_ABERTURA, abertura4.tipo());
    }
}
