package br.com.borurio.app.exception;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Exceção de negócio com errorCode identificável pelo consumidor da API.
 * O GlobalExceptionHandler retorna { code, message, data, errorCode, retryable, requestId } no envelope.
 */
public class BusinessException extends RuntimeException {

    private final String errorCode;
    private final int httpStatus;
    private final boolean retryable;
    private final Map<String, Object> data;

    public BusinessException(String errorCode, String message, int httpStatus) {
        this(errorCode, message, httpStatus, false, null);
    }

    public BusinessException(String errorCode, String message, int httpStatus, boolean retryable) {
        this(errorCode, message, httpStatus, retryable, null);
    }

    public BusinessException(String errorCode, String message, int httpStatus,
                              boolean retryable, Map<String, Object> data) {
        super(message);
        this.errorCode  = errorCode;
        this.httpStatus = httpStatus;
        this.retryable  = retryable;
        this.data       = data;
    }

    public String getErrorCode()  { return errorCode; }
    public int    getHttpStatus() { return httpStatus; }

    /** Indica ao consumidor da API se a mesma operação pode ser reenviada sem alterar dados. */
    public boolean isRetryable() { return retryable; }

    /** Dados estruturados adicionais (ex.: cStat/xMotivo de uma rejeição SEFAZ). Pode ser null. */
    public Map<String, Object> getData() { return data; }

    // -------------------------------------------------------------------------
    // Factory methods — mantêm mensagens consistentes com o contrato de integração
    // -------------------------------------------------------------------------

    public static BusinessException productNotFound(Long produtoId) {
        return new BusinessException(
                "PRODUCT_NOT_FOUND",
                "Produto não encontrado: id=" + produtoId,
                422);
    }

    public static BusinessException productInactive(Long produtoId, String codigo) {
        return new BusinessException(
                "PRODUCT_INACTIVE",
                "Produto inativo não pode ser adicionado ao pedido: id=" + produtoId + " código=" + codigo,
                422);
    }

    public static BusinessException insufficientStock(String descricao, BigDecimal disponivel, BigDecimal solicitado) {
        return new BusinessException(
                "INSUFFICIENT_STOCK",
                "Estoque insuficiente para \"" + descricao + "\""
                        + " (disponível: " + disponivel + ", solicitado: " + solicitado + ")",
                422);
    }

    public static BusinessException invalidOrderStatus(String message) {
        return new BusinessException("INVALID_ORDER_STATUS", message, 422);
    }

    /**
     * Outra requisição já reivindicou a emissão deste pedido (claim atômico perdido) — corrida
     * real (retry duplicado, chamada simultânea), não erro de dado. retryable=true: a emissão
     * em andamento deve terminar em instantes; consultar GET /pedidos/{id}/situacao ou tentar
     * de novo depois resolve sem risco de duas NF-e para o mesmo pedido.
     */
    public static BusinessException emissaoEmAndamento(Long pedidoId) {
        return new BusinessException(
                "EMISSAO_EM_ANDAMENTO",
                "Já existe uma emissão em andamento para o pedido " + pedidoId
                        + ". Aguarde a conclusão ou consulte a situação antes de tentar novamente.",
                409,
                true);
    }

    /**
     * O CNPJ embutido na chave de acesso da NF-e não corresponde ao CNPJ da empresa resolvida
     * para a operação. A execução deve falhar explicitamente para impedir o uso de certificado
     * ou contexto fiscal de outra empresa.
     */
    public static BusinessException documentoCnpjDivergente(String cnpjDocumento, String cnpjResolvido) {
        return new BusinessException(
                "DOCUMENTO_CNPJ_DIVERGENTE",
                "O CNPJ da chave de acesso (" + cnpjDocumento + ") não corresponde ao CNPJ "
                        + "da empresa resolvida para esta operação (" + cnpjResolvido + ").",
                422,
                false);
    }

    public static BusinessException batchLimitExceeded() {
        return new BusinessException(
                "BATCH_LIMIT_EXCEEDED",
                "O lote excede o limite máximo de 200 produtos por requisição.",
                422);
    }

    // -------------------------------------------------------------------------
    // OMS — autorização fiscal
    // -------------------------------------------------------------------------

    public static BusinessException companyNotFound(String cnpj) {
        return new BusinessException(
                "COMPANY_NOT_FOUND",
                "Empresa não encontrada ou não pré-cadastrada: CNPJ=" + cnpj,
                422);
    }

    public static BusinessException invalidCertificate(String detail) {
        return new BusinessException(
                "INVALID_CERTIFICATE",
                "Certificado A1 inválido: " + detail,
                422);
    }

    public static BusinessException cnpjCertificateMismatch(String cnpjEnviado, String cnpjCert) {
        return new BusinessException(
                "CNPJ_CERTIFICATE_MISMATCH",
                "CNPJ enviado (" + cnpjEnviado + ") não corresponde ao CNPJ do certificado (" + cnpjCert + ")",
                422);
    }

    public static BusinessException certificateExpired() {
        return new BusinessException(
                "CERTIFICATE_EXPIRED",
                "O certificado A1 está expirado e não pode ser utilizado para emissão.",
                422);
    }

    public static BusinessException invalidApiKey() {
        return new BusinessException(
                "INVALID_API_KEY",
                "API Key ausente, inválida, expirada ou revogada.",
                401);
    }

    public static BusinessException authorizationRevoked() {
        return new BusinessException(
                "AUTHORIZATION_REVOKED",
                "A autorização fiscal foi revogada. Realize uma nova autorização.",
                401);
    }

    public static BusinessException companyInactive(String cnpj) {
        return new BusinessException(
                "COMPANY_INACTIVE",
                "Empresa com CNPJ=" + cnpj + " está inativa. Contate o administrador para reativação.",
                422);
    }

    public static BusinessException cnpjNotAuthorizedForOmsClient(String cnpj) {
        return new BusinessException(
                "CNPJ_NOT_AUTHORIZED",
                "CNPJ=" + cnpj + " não está autorizado para este cliente OMS. "
                        + "Realize a autorização via POST /api/integration/fiscal-authorizations.",
                403);
    }

    public static BusinessException certNotFoundForCnpj(String cnpj) {
        return new BusinessException(
                "CERT_NOT_FOUND_FOR_CNPJ",
                "Nenhum certificado ativo encontrado para CNPJ=" + cnpj
                        + ". Verifique se a autorização fiscal foi realizada para este CNPJ.",
                422);
    }

    // -------------------------------------------------------------------------
    // Emissão de NF-e — códigos padronizados de retry (Requisito 4)
    // -------------------------------------------------------------------------

    /** Cadastro do emitente sem endereço completo — não adianta retry sem corrigir o dado. */
    public static BusinessException emitterAddressIncomplete() {
        return new BusinessException(
                "EMITTER_ADDRESS_INCOMPLETE",
                "Cadastro do emitente incompleto.",
                422,
                false);
    }

    /**
     * O CFOP de um item não é compatível com o tipo de operação calculado (idDest, derivado
     * da UF do emitente x UF do destinatário). A OMS informa o CFOP; o Borurio só valida a
     * coerência antes de reservar numeração fiscal e transmitir à SEFAZ — nunca corrige o
     * valor recebido. Achado real: Gate 7D (2026-07-20), pedido rejeitado com cStat=732 só
     * depois de já ter consumido um número fiscal, por falta desta validação preventiva.
     */
    public static BusinessException cfopDestinationMismatch(String cfop, String idDest, String prefixoEsperado) {
        String tipoOperacao = "1".equals(idDest) ? "interna" : "interestadual";
        return new BusinessException(
                "CFOP_DESTINATION_MISMATCH",
                "CFOP " + cfop + " incompatível com operação " + tipoOperacao
                        + ". Para idDest=" + idDest + ", o CFOP de saída deve iniciar com " + prefixoEsperado + ".",
                422,
                false);
    }

    /**
     * SEFAZ rejeitou a NF-e (cStat >= 200) — na maioria dos casos é dado incorreto
     * (NCM/CFOP/CSOSN/schema), não falha transitória. retryable=false: reenviar sem corrigir
     * a causa (exposta em data.cStat/data.xMotivo) só repete a mesma rejeição. O pedido pode
     * ser reemitido pelo mesmo /emitir depois de corrigido (ver STATUS_EMISSIVEIS).
     */
    public static BusinessException sefazRejected(int cStat, String xMotivo) {
        return new BusinessException(
                "SEFAZ_REJECTED",
                "NF-e rejeitada pela SEFAZ: " + xMotivo,
                422,
                false,
                Map.of("cStat", cStat, "xMotivo", xMotivo != null ? xMotivo : ""));
    }

    /** Timeout de rede na chamada à SEFAZ — falha transitória, retry é seguro. */
    public static BusinessException sefazTimeout() {
        return new BusinessException(
                "SEFAZ_TIMEOUT",
                "Tempo limite excedido ao transmitir a NF-e para a SEFAZ.",
                503,
                true);
    }

    /** SEFAZ inacessível (conexão recusada/DNS) — falha transitória, retry é seguro. */
    public static BusinessException sefazUnavailable() {
        return new BusinessException(
                "SEFAZ_UNAVAILABLE",
                "SEFAZ temporariamente indisponível.",
                503,
                true);
    }

    /** XML gerado não passou na validação de schema local — retry só ajuda se os dados forem corrigidos. */
    public static BusinessException xmlSchemaInvalid(String detail) {
        return new BusinessException(
                "XML_SCHEMA_INVALID",
                "XML da NF-e não passou na validação de schema: " + detail,
                422,
                false);
    }

    /**
     * PROPOSTO 10-08-2026 (Gate 1.2, correção do P1 de classificação pré-transmissão) — falha
     * comprovadamente local, ocorrida antes de qualquer I/O de rede com a SEFAZ (assinatura
     * digital, persistência local como colisão de chave em marcarTransmitido, ou qualquer outra
     * etapa anterior à chamada de transmissão). Nunca retryable automaticamente: ao contrário de
     * timeout/indisponibilidade, a causa não se resolve sozinha com um novo envio — precisa de
     * correção de configuração/certificado ou investigação (ex.: colisão de chave). O ciclo do
     * nNF volta para RESERVADO (NfeEmissaoService.reverterParaReservadoPorFalhaLocal) — número não
     * é consumido nem perdido, só aguarda uma nova tentativa deliberada.
     */
    public static BusinessException localProcessingFailure(String detail) {
        return new BusinessException(
                "LOCAL_PROCESSING_FAILURE",
                "Falha local antes da transmissão à SEFAZ: " + detail,
                422,
                false);
    }

    /**
     * Empresa.indFinalPadrao contém um valor fora do domínio válido ("0" ou "1") — falha explícita
     * antes de montar/transmitir o XML, em vez de normalizar silenciosamente para um padrão.
     */
    public static BusinessException indFinalPadraoInvalido(Long empresaId, String valor) {
        return new BusinessException(
                "IND_FINAL_PADRAO_INVALIDO",
                "Empresa id=" + empresaId + " possui indFinalPadrao inválido (\"" + valor
                        + "\"). Valores aceitos: \"0\" ou \"1\".",
                422,
                false);
    }

    // -------------------------------------------------------------------------
    // Ciclo do nNF (Gate 1 — NfeEmissaoService)
    // -------------------------------------------------------------------------

    /**
     * Outro pedido é dono do gate fiscal desta série (nfe_sequencia.emissao_ativa_id aponta para
     * uma nfe_emissao de outro pedido, ainda sem resultado definitivo). retryable=true: o gate
     * libera assim que o ciclo ativo chegar a AUTORIZADO ou DENEGADO — não é erro de dado.
     */
    public static BusinessException emissaoEmAndamentoNaSerie(String cnpjEmitente, String serie) {
        return new BusinessException(
                "EMISSAO_EM_ANDAMENTO_NA_SERIE",
                "Já existe uma emissão em andamento para CNPJ=" + cnpjEmitente + " série=" + serie
                        + ". Aguarde o ciclo ativo chegar a um resultado definitivo.",
                409,
                true);
    }

    /**
     * O pedido tem uma nfe_emissao em TRANSMITIDO/PENDENTE_CONFIRMACAO — resultado ainda incerto
     * (timeout, ou JVM interrompida entre marcar TRANSMITIDO e receber a resposta da SEFAZ).
     * Nunca dispara nova transmissão às cegas. Lançada em dois momentos (Gate 3, 10-08-2026):
     * (1) claim de reconciliação não vencido (backoff ainda não liberou a janela, ou outra
     * chamada concorrente já está reconciliando) — nenhuma consulta à SEFAZ ocorreu nesta
     * chamada; (2) a reconciliação rodou (consultou a SEFAZ ou resolveu localmente) mas o
     * resultado continua inconclusivo (217/635/falha de transporte/falha de parse) — o ciclo
     * seguirá pendente até uma próxima tentativa, respeitando o mesmo backoff.
     */
    public static BusinessException emissaoAguardandoReconciliacao(Long pedidoId) {
        return new BusinessException(
                "EMISSAO_AGUARDANDO_RECONCILIACAO",
                "O pedido " + pedidoId + " tem uma tentativa de emissão com resultado ainda incerto. "
                        + "É necessário reconciliar com a SEFAZ pela chave já transmitida antes de "
                        + "qualquer nova tentativa.",
                409,
                true);
    }

    /**
     * Reconciliação (Gate 3, 10-08-2026) provou que o número fiscal está definitivamente ocupado
     * por identidade fiscal alheia (NF-e cancelada/denegada/inutilizada na base da SEFAZ, ou
     * chave de acesso divergente confirmada) — a NF-e DESTE pedido nunca foi autorizada. O ciclo
     * já foi resolvido como NUMERO_OCUPADO (número consumido, gate liberado) antes desta exceção
     * ser lançada — retryable=true porque uma nova chamada a /emitir já abre um ciclo NOVO, com
     * número seguinte; nunca reaproveita o número ocupado. cStat/xMotivo em `data` documentam a
     * causa fiscal real para quem integra.
     */
    public static BusinessException numeroFiscalOcupado(Long pedidoId, Integer cStat, String xMotivo) {
        return new BusinessException(
                "NUMERO_FISCAL_OCUPADO",
                "O número fiscal do pedido " + pedidoId + " está ocupado por outra identidade fiscal na SEFAZ "
                        + "e não pôde ser autorizado. Uma nova tentativa de emissão usará o próximo número.",
                409,
                true,
                Map.of("cStat", cStat != null ? cStat : -1, "xMotivo", xMotivo != null ? xMotivo : ""));
    }

    /**
     * Pedido em EMITINDO sem nenhuma nfe_emissao correspondente — a JVM foi interrompida antes
     * mesmo de abrir o ciclo do nNF (ex.: entre o claim de emissão e a reserva de estoque/gate).
     * Não há como saber com segurança se algum efeito colateral (reserva de estoque) já ocorreu;
     * não tenta adivinhar. retryable=false: exige intervenção manual, não simples nova tentativa.
     */
    /**
     * Pedido em EMITINDO cujo ciclo de emissão fiscal mais recente já chegou a um resultado
     * definitivo (AUTORIZADO/DENEGADO), mas a atualização de Pedido.status foi interrompida antes
     * de refletir isso — janela estreita entre a transação que resolve o ciclo (libera o gate) e
     * a que atualiza o status do pedido. O status já foi corrigido nesta mesma chamada para
     * refletir o resultado real; não há nova tentativa de transmissão. retryable=false: repetir
     * a chamada não muda nada, o resultado já está definitivo.
     */
    public static BusinessException pedidoJaResolvido(Long pedidoId, String statusResolvido) {
        return new BusinessException(
                "PEDIDO_JA_RESOLVIDO",
                "Pedido " + pedidoId + " já tinha um resultado fiscal definitivo (" + statusResolvido
                        + ") de uma tentativa anterior interrompida antes de atualizar o status. "
                        + "O status foi corrigido; nenhuma nova tentativa de transmissão foi feita.",
                409,
                false);
    }

    public static BusinessException pedidoEmissaoInconsistente(Long pedidoId) {
        return new BusinessException(
                "PEDIDO_EMISSAO_INCONSISTENTE",
                "Pedido " + pedidoId + " está em EMITINDO sem nenhum ciclo de emissão fiscal "
                        + "registrado — provável interrupção antes da reserva do número. Requer "
                        + "verificação manual antes de qualquer nova tentativa.",
                409,
                false);
    }

    // -------------------------------------------------------------------------
    // Sincronização de série/numeração (OMS → Borurio)
    // -------------------------------------------------------------------------

    public static BusinessException serieInvalida(String detalhe) {
        return new BusinessException("SERIE_INVALIDA", "Série inválida: " + detalhe, 422, false);
    }

    public static BusinessException numeracaoInvalida(String detalhe) {
        return new BusinessException("NUMERACAO_INVALIDA", "Próximo número inválido: " + detalhe, 422, false);
    }

    /**
     * A OMS tentou sincronizar série/numeração de um CNPJ+série que tem um ciclo fiscal ativo
     * (Gate 1 — número reservado, transmitido ou aguardando confirmação). Nunca expõe o id
     * interno da emissão/pedido — só CNPJ e série, que já é o padrão dos demais erros desta
     * família (ver numeracaoInferiorAtual). retryable=true: assim que o ciclo ativo chegar a um
     * resultado definitivo (autorizado ou denegado), a mesma sincronização pode ser reenviada e
     * será aplicada normalmente.
     */
    public static BusinessException numeracaoComEmissaoEmAndamento(String cnpj, String serie) {
        return new BusinessException(
                "NUMERACAO_COM_EMISSAO_EM_ANDAMENTO",
                "Não é possível sincronizar numeração/série de CNPJ=" + cnpj + " série=" + serie
                        + ": existe uma emissão fiscal em andamento para esta série. Aguarde o "
                        + "ciclo ativo chegar a um resultado definitivo (autorizado ou denegado) "
                        + "e tente novamente.",
                409,
                true);
    }

    /**
     * A OMS tentou sincronizar um `proximoNumero` menor que o já registrado no Borurio.
     * retryable=false: reenviar sem corrigir o valor só repete a mesma rejeição.
     */
    public static BusinessException numeracaoInferiorAtual(String cnpj, String serie, String detalhe) {
        return new BusinessException(
                "NUMERACAO_INFERIOR_A_ATUAL",
                "Não é possível atualizar a numeração de CNPJ=" + cnpj + " série=" + serie + ": " + detalhe,
                422,
                false);
    }

    // -------------------------------------------------------------------------
    // OMS — revogação e rotação administrativa (Gate 7H)
    // -------------------------------------------------------------------------

    public static BusinessException omsAuthorizationNotFound(Long id) {
        return new BusinessException(
                "OMS_AUTHORIZATION_NOT_FOUND",
                "Autorização OMS não encontrada: id=" + id,
                404);
    }

    /**
     * A versao informada (expectedVersion) não corresponde à versao atual — outra rotação ou
     * revogação já alterou o estado. retryable=true: buscar a versao atual e tentar novamente
     * é seguro (não há efeito colateral duplicado).
     */
    public static BusinessException authorizationChanged(Long id) {
        return new BusinessException(
                "AUTHORIZATION_CHANGED",
                "A autorização id=" + id + " foi alterada por outra operação. Releia a versao atual e tente novamente.",
                409,
                true);
    }

    /**
     * O resultado de uma rotação já registrada (Idempotency-Key) não corresponde mais ao estado
     * atual da autorização — foi superada por uma rotação ou revogação posterior. Não é seguro
     * reemitir o token antigo. retryable=false: repetir com a mesma chave nunca vai suceder;
     * é preciso decidir uma nova ação com uma nova Idempotency-Key.
     */
    public static BusinessException rotationResultSuperseded(Long id) {
        return new BusinessException(
                "ROTATION_RESULT_SUPERSEDED",
                "O resultado da rotação da autorização id=" + id + " foi superado por uma operação posterior.",
                409,
                false);
    }

    public static BusinessException certificateValidityInsufficient(Long id) {
        return new BusinessException(
                "CERTIFICATE_VALIDITY_INSUFFICIENT",
                "A validade restante do certificado da autorização id=" + id
                        + " é insuficiente para rotacionar o token.",
                422,
                false);
    }

    /** O usuário autenticado não corresponde a um db_user válido — nunca prosseguir sem identificar o executor. */
    public static BusinessException adminContextInvalid() {
        return new BusinessException(
                "ADMIN_CONTEXT_INVALID",
                "Não foi possível identificar o usuário ADMIN autenticado para registrar a auditoria.",
                403);
    }

    /** Falha de acesso ao banco ao resolver o contexto do ADMIN — falha fechada, retry é seguro. */
    public static BusinessException authorizationServiceUnavailable() {
        return new BusinessException(
                "AUTHORIZATION_SERVICE_UNAVAILABLE",
                "Serviço de autorização OMS temporariamente indisponível.",
                503,
                true);
    }

    /** Header Idempotency-Key ausente ou não é um UUID válido. */
    public static BusinessException invalidIdempotencyKey() {
        return new BusinessException(
                "INVALID_IDEMPOTENCY_KEY",
                "Header Idempotency-Key é obrigatório e deve ser um UUID válido.",
                400,
                false);
    }

    /**
     * A Idempotency-Key informada já foi usada para uma operação em OUTRA autorização (authId
     * diferente) ou em um evento diferente (ex.: chave de revogação reusada em rotação). Nunca
     * deve devolver token/estado de uma autorização diferente da solicitada — a chave deve ser
     * única por operação lógica; gere uma nova.
     */
    public static BusinessException idempotencyKeyConflict(Long authId) {
        return new BusinessException(
                "IDEMPOTENCY_KEY_CONFLICT",
                "A Idempotency-Key informada já foi usada para outra autorização ou operação, "
                        + "diferente da solicitada para id=" + authId + ". Gere uma nova Idempotency-Key.",
                409,
                false);
    }

    public static BusinessException motivoDetalheObrigatorio() {
        return new BusinessException(
                "MOTIVO_DETALHE_OBRIGATORIO",
                "motivoDetalhe é obrigatório quando motivoCodigo = OUTRO.",
                422,
                false);
    }
}
