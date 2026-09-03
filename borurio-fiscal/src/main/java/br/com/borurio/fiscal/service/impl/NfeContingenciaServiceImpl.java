package br.com.borurio.fiscal.service.impl;

import br.com.borurio.fiscal.entity.NfeEmissao;
import br.com.borurio.fiscal.entity.NfeSequencia;
import br.com.borurio.fiscal.exception.ContingenciaInvalidaException;
import br.com.borurio.fiscal.mapper.NfeEmissaoMapper;
import br.com.borurio.fiscal.service.NfeContingenciaService;
import br.com.borurio.fiscal.service.NfeSequenciaService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Fase 1 SVC (17-08-2026) — ver {@link NfeContingenciaService}. Construtor recebe só
 * {@link NfeSequenciaService}/{@link NfeEmissaoMapper}: impossível receber {@code PedidoMapper}/
 * {@code EstoqueService} (não existem em {@code borurio-fiscal}) — impossibilidade estrutural de
 * este serviço tocar Pedido/Estoque, não convenção.
 */
@Service
public class NfeContingenciaServiceImpl implements NfeContingenciaService {

    // Mesmo padrão já em produção para o mesmo tipo XSD (TDateTimeUTC) — DH_EVENTO_FMT de
    // NfeCancelamentoServiceImpl. Precisão de segundos (nunca frações — tamanho da coluna
    // dh_cont VARCHAR(30) fica determinístico); offset sempre calculado a partir do
    // OffsetDateTime recebido pelo chamador, nunca de uma config global — o motor é multi-UF
    // desde a Fase 0, uma property única não pode ser a fonte do offset de qualquer empresa.
    private static final DateTimeFormatter DH_CONT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final NfeSequenciaService sequenciaService;
    private final NfeEmissaoMapper nfeEmissaoMapper;

    public NfeContingenciaServiceImpl(NfeSequenciaService sequenciaService, NfeEmissaoMapper nfeEmissaoMapper) {
        this.sequenciaService = sequenciaService;
        this.nfeEmissaoMapper = nfeEmissaoMapper;
    }

    // REPEATABLE_READ, não SERIALIZABLE — mesmo motivo do P0-3 em NfeEmissaoService.resolverCiclo:
    // a pré-leitura não-bloqueante abaixo, sob SERIALIZABLE, promoveria a um shared lock e
    // arriscaria o mesmo deadlock já comprovado contra MySQL real naquele caso. Única fronteira
    // transacional deste fluxo — consolidarNumeroParaContingencia/substituirGateParaContingencia
    // são Propagation.MANDATORY e sempre participam desta mesma transação (nunca a própria raiz).
    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public NfeEmissao abrirContingencia(Long emissaoNormalId, String tpEmisContingencia, String xJust, OffsetDateTime dhCont) {
        if (emissaoNormalId == null) {
            throw new IllegalArgumentException("emissaoNormalId é obrigatório.");
        }
        String autorizadorDestino = derivarAutorizadorDestino(tpEmisContingencia);
        String xJustNormalizado = normalizarXJust(xJust);
        if (dhCont == null) {
            throw new IllegalArgumentException("dhCont é obrigatório.");
        }

        // Ordem canônica do projeto: pré-leitura NÃO bloqueante só pra localizar cnpj/série
        // (nunca decide a transição) -> trava nfe_sequencia FOR UPDATE -> só então trava
        // nfe_emissao (NORMAL) FOR UPDATE -> revalida contra as linhas já travadas -> aplica.
        NfeEmissao preRead = nfeEmissaoMapper.buscarPorId(emissaoNormalId);
        if (preRead == null) {
            throw new IllegalStateException("nfe_emissao id=" + emissaoNormalId + " não encontrada ao abrir contingência.");
        }
        String cnpjEmitente = preRead.getCnpjEmitente();
        String serie = preRead.getSerie();

        NfeSequencia seq = sequenciaService.buscarSeExistirParaAtualizar(cnpjEmitente, serie);
        NfeEmissao normal = nfeEmissaoMapper.buscarPorIdParaAtualizar(emissaoNormalId);
        if (normal == null) {
            throw new IllegalStateException("nfe_emissao id=" + emissaoNormalId + " não encontrada ao abrir contingência.");
        }

        // Um único guard cobre "não é o ciclo ativo" E "já foi substituída antes" — Caminho B
        // sempre move o gate NORMAL->filha, então uma 2ª tentativa de contingência sobre a mesma
        // NORMAL encontra o gate apontando pra outro lugar, não mais pra ela.
        if (seq == null || seq.getEmissaoAtivaId() == null || !seq.getEmissaoAtivaId().equals(emissaoNormalId)) {
            throw new ContingenciaInvalidaException("Emissão id=" + emissaoNormalId + " não é o ciclo ativo da série "
                    + "CNPJ=" + cnpjEmitente + " série=" + serie + " — contingência recusada (gate não corresponde; "
                    + "pode já ter sido substituída, resolvida, ou pertencer a outro ciclo).");
        }

        String estadoNormal = normal.getEstado();
        if (NfeEmissao.Estados.RESERVADO.equals(estadoNormal)) {
            throw new ContingenciaInvalidaException("Emissão id=" + emissaoNormalId + " ainda está RESERVADO (chave "
                    + "nunca transmitida) — Caminho A (troca de tpEmis na mesma linha) não é implementado nesta "
                    + "fase, ver Fase 2.");
        }
        if (!NfeEmissao.Estados.TRANSMITIDO.equals(estadoNormal)
                && !NfeEmissao.Estados.PENDENTE_CONFIRMACAO.equals(estadoNormal)) {
            throw new ContingenciaInvalidaException("Emissão id=" + emissaoNormalId + " está em estado '" + estadoNormal
                    + "' — contingência só se aplica a ciclo TRANSMITIDO/PENDENTE_CONFIRMACAO (ciclo já resolvido "
                    + "ou aguardando correção, contingência não se aplica).");
        }

        // Caminho B — consolida + reserva nNF da filha + insere filha + troca o gate, tudo na
        // mesma transação (MANDATORY nos dois métodos abaixo impede fragmentação).
        int ultimoNumeroConsolidado = sequenciaService.consolidarNumeroParaContingencia(
                cnpjEmitente, serie, normal.getNumeroNfe());
        int numeroFilha = ultimoNumeroConsolidado + 1; // derivado do RETORNO da consolidação, nunca de normal.numeroNfe

        NfeEmissao filha = new NfeEmissao();
        filha.setPedidoId(normal.getPedidoId());
        filha.setEmpresaId(normal.getEmpresaId());
        filha.setCnpjEmitente(cnpjEmitente);
        filha.setModelo(normal.getModelo());
        filha.setSerie(serie);
        filha.setNumeroNfe(numeroFilha);
        filha.setEstado(NfeEmissao.Estados.RESERVADO);
        filha.setTentativas(1);
        filha.setTpEmis(tpEmisContingencia);
        filha.setAutorizadorDestino(autorizadorDestino);
        filha.setEmissaoOrigemId(normal.getId());
        filha.setDhCont(formatarDhCont(dhCont));
        filha.setXJustContingencia(xJustNormalizado);

        try {
            nfeEmissaoMapper.inserirContingencia(filha);
        } catch (DuplicateKeyException e) {
            // Defesa em profundidade (uk_nfe_emissao_origem) — sob o lock já seguro acima, nunca
            // deveria disparar; mantido explicitamente contra qualquer corrida não prevista.
            throw new ContingenciaInvalidaException("Emissão id=" + emissaoNormalId + " já foi substituída por "
                    + "contingência anteriormente (uk_nfe_emissao_origem) — double-substitute recusado.", e);
        }

        sequenciaService.substituirGateParaContingencia(cnpjEmitente, serie, normal.getId(), filha.getId());

        return filha;
    }

    private String derivarAutorizadorDestino(String tpEmisContingencia) {
        if (NfeEmissao.TpEmis.SVC_AN.equals(tpEmisContingencia)) {
            return NfeEmissao.AutorizadorDestino.SVC_AN;
        }
        if (NfeEmissao.TpEmis.SVC_RS.equals(tpEmisContingencia)) {
            return NfeEmissao.AutorizadorDestino.SVC_RS;
        }
        throw new IllegalArgumentException("tpEmisContingencia inválido: '" + tpEmisContingencia
                + "' — só SVC_AN(" + NfeEmissao.TpEmis.SVC_AN + ")/SVC_RS(" + NfeEmissao.TpEmis.SVC_RS
                + ") são suportados nesta fase (EPEC fora de escopo).");
    }

    private String normalizarXJust(String xJust) {
        String trimmed = xJust == null ? "" : xJust.trim();
        if (trimmed.length() < 15 || trimmed.length() > 256) {
            throw new IllegalArgumentException("xJust deve ter entre 15 e 256 caracteres após trim (recebido "
                    + trimmed.length() + ").");
        }
        return trimmed;
    }

    private String formatarDhCont(OffsetDateTime dhCont) {
        return dhCont.truncatedTo(ChronoUnit.SECONDS).format(DH_CONT_FMT);
    }
}
