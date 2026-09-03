package br.com.borurio.web.service;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.entity.OmsFiscalAuthorization;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.app.mapper.OmsFiscalAuthorizationMapper;
import br.com.borurio.fiscal.dto.AtualizacaoSequenciaResultado;
import br.com.borurio.fiscal.entity.NfeSequencia;
import br.com.borurio.fiscal.entity.NfeSequenciaAuditoria;
import br.com.borurio.fiscal.exception.SequenciaComEmissaoAtivaException;
import br.com.borurio.fiscal.mapper.NfeSequenciaAuditoriaMapper;
import br.com.borurio.fiscal.service.NfeSequenciaService;
import br.com.borurio.web.dto.FiscalNumberingSyncResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Endpoint de sincronização recorrente de série/numeração vindo da OMS (PUT
 * /api/integration/fiscal-numbering/{cnpj}) — substitui o desenho antigo de baseline único no
 * onboarding (inicializarBaseline), que não cobre o requisito confirmado pelo CC de atualização
 * recorrente disparada sempre que o cliente muda série/numeração no lado da OMS.
 *
 * Ordem de lock (mesma de ReservaFiscalService/NfeEmissaoService, documentada para evitar
 * deadlock): Empresa primeiro, depois nfe_sequencia — sempre nessa ordem, nunca a inversa, e
 * nunca envolvendo nfe_emissao diretamente (esta classe só lê emissaoAtivaId como campo de
 * nfe_sequencia, nunca toca a tabela nfe_emissao). Toda a operação — validação de gate e escrita
 * de sequência/série — acontece dentro desta única transação curta, sem nenhuma chamada de rede.
 *
 * P0-1 (07-08-2026, hardening pós-banca): uma sincronização externa nunca pode mutar o baseline
 * fiscal (avançar o número, ou trocar a série ativa) enquanto existe um ciclo do Gate 1 em voo —
 * isso corromperia nfe_sequencia por baixo de uma nfe_emissao que ainda vai tentar consumir um
 * número específico. Dois vetores tratados:
 *   vetor 1 (avanço de número): guardado dentro de NfeSequenciaServiceImpl.aplicarOuValidar() —
 *     nunca escreve ultimo_numero se a linha tiver emissaoAtivaId != null, mas SÓ quando o valor
 *     realmente avançaria (sincronização idempotente continua permitida mesmo com gate ocupado).
 *   vetor 2 (troca de série): guardado aqui — a série sendo abandonada (serieAnterior) não pode
 *     ter emissaoAtivaId != null (senão o ciclo ativo fica órfão, porque abrirCiclo() resolve a
 *     série de novo a cada chamada a partir de Empresa.serieNfePadrao), e a série de destino
 *     (serieNova) também não pode ter uma emissão ativa persistida de antes.
 */
@Service
public class FiscalNumberingService {

    private static final Logger log = LoggerFactory.getLogger(FiscalNumberingService.class);
    private static final String ORIGEM_SYNC = "OMS_SYNC";

    private final EmpresaMapper empresaMapper;
    private final NfeSequenciaService sequenciaService;
    private final NfeSequenciaAuditoriaMapper auditoriaMapper;
    private final OmsCertificadoService omsCertificadoService;
    private final OmsFiscalAuthorizationMapper omsAuthMapper;

    public FiscalNumberingService(EmpresaMapper empresaMapper,
                                   NfeSequenciaService sequenciaService,
                                   NfeSequenciaAuditoriaMapper auditoriaMapper,
                                   OmsCertificadoService omsCertificadoService,
                                   OmsFiscalAuthorizationMapper omsAuthMapper) {
        this.empresaMapper = empresaMapper;
        this.sequenciaService = sequenciaService;
        this.auditoriaMapper = auditoriaMapper;
        this.omsCertificadoService = omsCertificadoService;
        this.omsAuthMapper = omsAuthMapper;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public FiscalNumberingSyncResponse sincronizar(String cnpjPathParam, String serieNova,
                                                     Integer proximoNumero, String jtiOms,
                                                     String requestId) {
        String cnpj = normalizarCnpj(cnpjPathParam);
        validarSerie(serieNova);
        validarProximoNumero(proximoNumero);

        // Identidade e escopo (CNPJ) vêm SEMPRE da autorização autenticada — nunca do body.
        if (jtiOms == null || !omsCertificadoService.cnpjAutorizadoParaJti(jtiOms, cnpj)) {
            throw BusinessException.cnpjNotAuthorizedForOmsClient(cnpj);
        }

        // Lock 1: Empresa — sempre primeiro, mesma ordem de ReservaFiscalService/NfeEmissaoService.
        Empresa empresa = empresaMapper.buscarPorCnpjParaAtualizar(cnpj);
        if (empresa == null) {
            throw BusinessException.companyNotFound(cnpj);
        }
        String serieAnterior = empresa.getSerieNfePadrao();
        boolean serieMudou = serieAnterior == null || !serieAnterior.equals(serieNova);

        // Vetor 2 — só entra em jogo quando a série está de fato mudando. Nenhuma escrita
        // acontece antes dos dois lados terem sido confirmados livres.
        if (serieMudou) {
            // Lock 2a (somente leitura): a série sendo abandonada não pode ter ciclo ativo —
            // senão abrirCiclo() (que resolve a série de novo a cada chamada a partir de
            // Empresa.serieNfePadrao) nunca mais encontraria esse ciclo numa retomada.
            if (serieAnterior != null) {
                NfeSequencia seqAnterior = sequenciaService.buscarSeExistirParaAtualizar(cnpj, serieAnterior);
                if (seqAnterior != null && seqAnterior.getEmissaoAtivaId() != null) {
                    log.warn("[FiscalNumbering] Sincronização bloqueada — série anterior com emissão ativa | "
                                    + "cnpj={} | serieAnterior={} | emissaoAtivaId={}",
                            cnpj, serieAnterior, seqAnterior.getEmissaoAtivaId());
                    throw BusinessException.numeracaoComEmissaoEmAndamento(cnpj, serieAnterior);
                }
            }
            // Lock 2b (somente leitura): a série de destino não pode ter uma emissão ativa
            // persistida de antes — mesmo que a numeração em si seja idempotente para ela.
            NfeSequencia seqDestino = sequenciaService.buscarSeExistirParaAtualizar(cnpj, serieNova);
            if (seqDestino != null && seqDestino.getEmissaoAtivaId() != null) {
                log.warn("[FiscalNumbering] Sincronização bloqueada — série destino com emissão ativa | "
                                + "cnpj={} | serieNova={} | emissaoAtivaId={}",
                        cnpj, serieNova, seqDestino.getEmissaoAtivaId());
                throw BusinessException.numeracaoComEmissaoEmAndamento(cnpj, serieNova);
            }
        }

        // Lock 3: nfe_sequencia(serieNova) — aplica de fato. Vetor 1 é guardado dentro deste
        // método (aplicarOuValidar), como defesa em profundidade mesmo fora de troca de série.
        AtualizacaoSequenciaResultado resultadoSequencia;
        try {
            resultadoSequencia = sequenciaService.atualizarSequencia(cnpj, serieNova, proximoNumero);
        } catch (IllegalStateException e) {
            throw BusinessException.numeracaoInferiorAtual(cnpj, serieNova, e.getMessage());
        } catch (SequenciaComEmissaoAtivaException e) {
            log.warn("[FiscalNumbering] Sincronização bloqueada — avanço de número com emissão ativa | "
                            + "cnpj={} | serie={} | emissaoAtivaId={}",
                    cnpj, serieNova, e.getEmissaoAtivaId());
            throw BusinessException.numeracaoComEmissaoEmAndamento(cnpj, serieNova);
        }

        if (serieMudou) {
            empresaMapper.atualizarSerieNfePadrao(empresa.getId(), serieNova);
        }

        boolean aplicado = resultadoSequencia.aplicado() || serieMudou;
        registrarAuditoria(cnpj, serieAnterior, serieNova, resultadoSequencia, jtiOms, requestId, aplicado);

        return new FiscalNumberingSyncResponse(
                cnpj, serieAnterior, serieNova,
                resultadoSequencia.proximoNumeroAnterior(), resultadoSequencia.proximoNumeroAtual(),
                aplicado, LocalDateTime.now());
    }

    private void registrarAuditoria(String cnpj, String serieAnterior, String serieNova,
                                     AtualizacaoSequenciaResultado resultadoSequencia,
                                     String jtiOms, String requestId, boolean aplicado) {
        NfeSequenciaAuditoria auditoria = new NfeSequenciaAuditoria();
        auditoria.setCnpjEmitente(cnpj);
        auditoria.setSerieAnterior(serieAnterior);
        auditoria.setSerieAtual(serieNova);
        auditoria.setProximoNumeroAnterior(resultadoSequencia.proximoNumeroAnterior());
        auditoria.setProximoNumeroAtual(resultadoSequencia.proximoNumeroAtual());
        auditoria.setOrigem(ORIGEM_SYNC);
        auditoria.setClienteOms(resolverClienteOmsId(jtiOms));
        auditoria.setRequestId(requestId);
        auditoria.setAplicado(aplicado);
        auditoriaMapper.inserir(auditoria);
    }

    /** Grava só o id interno da autorização — nunca o jti completo. */
    private String resolverClienteOmsId(String jtiOms) {
        OmsFiscalAuthorization auth = omsAuthMapper.buscarPorJti(jtiOms);
        return auth != null ? String.valueOf(auth.getId()) : null;
    }

    private String normalizarCnpj(String cnpj) {
        if (cnpj == null) return null;
        return cnpj.replaceAll("\\D", "");
    }

    // Mesmo padrão do tipo TSerie no XSD oficial da NF-e (tiposBasico_v4.00.xsd) — "0" sozinho,
    // ou 1 a 3 dígitos sem zero à esquerda. Rejeitar aqui evita que uma série alfanumérica ou
    // mal formatada fique presa em Empresa.serieNfePadrao até a próxima sincronização corrigir.
    private static final java.util.regex.Pattern SERIE_VALIDA = java.util.regex.Pattern.compile("0|[1-9][0-9]{0,2}");

    private void validarSerie(String serie) {
        if (serie == null || serie.isBlank()) {
            throw BusinessException.serieInvalida("série é obrigatória.");
        }
        if (!SERIE_VALIDA.matcher(serie).matches()) {
            throw BusinessException.serieInvalida(
                    "\"" + serie + "\" não corresponde ao formato TSerie da NF-e (\"0\" ou 1 a 3 dígitos sem zero à esquerda).");
        }
    }

    private void validarProximoNumero(Integer proximoNumero) {
        if (proximoNumero == null || proximoNumero < 1) {
            throw BusinessException.numeracaoInvalida(
                    "proximoNumero deve ser maior ou igual a 1: " + proximoNumero);
        }
    }
}
