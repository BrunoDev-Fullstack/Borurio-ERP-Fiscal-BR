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

    /**
     * Mesmo desfecho de {@link #sefazRejected(int, String)}, acrescentando serie/numeroNfe/
     * estadoFiscal (Gate de contrato OMS, 11-08-2026) — o número fiscal continua ocupado por este
     * pedido em AGUARDANDO_CORRECAO (não é consumido nem liberado), então a OMS precisa saber qual
     * número está pendente de correção antes de reemitir. cStat é {@code Integer} (nullable) —
     * nunca um valor sintético como -1 quando não há código real da SEFAZ; mesma regra já aplicada
     * a {@link #numeroFiscalOcupado(Long, Integer, String, String, Integer)}.
     */
    public static BusinessException sefazRejected(Integer cStat, String xMotivo, String serie, Integer numeroNfe) {
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("cStat", cStat);
        data.put("xMotivo", xMotivo != null ? xMotivo : "");
        data.put("serie", serie);
        data.put("numeroNFe", numeroNfe);
        data.put("estadoFiscal", "AGUARDANDO_CORRECAO");
        return new BusinessException(
                "SEFAZ_REJECTED",
                "NF-e rejeitada pela SEFAZ: " + xMotivo,
                422,
                false,
                data);
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
     * Reconciliação (Gate 3) não conseguiu consultar a SEFAZ por falta de rota configurada para a
     * UF da empresa emitente — {@code SefazRotaNaoConfiguradaException}, erro de configuração/
     * deployment do servidor, nunca falha transitória de rede. Banca 14-08-2026 (3ª rodada):
     * antes desta exceção existir, esse caso caía no mesmo {@code EMISSAO_AGUARDANDO_RECONCILIACAO}
     * (409, retryable=true) de qualquer timeout — a própria justificativa escrita no código dizia
     * "retry sozinho nunca resolve" mas a resposta ao chamador continuava dizendo o contrário.
     * O estado fiscal da emissão permanece intocado (nenhum {@code resolverCicloComEfeitos} é
     * chamado) — não sabemos o resultado real da NF-e, só que não dá pra descobrir agora.
     * retryable=false: a OMS não deve reenviar automaticamente até a configuração ser corrigida.
     */
    public static BusinessException reconciliacaoErroConfiguracao(Long pedidoId) {
        return new BusinessException(
                "RECONCILIACAO_ERRO_CONFIGURACAO",
                "Não foi possível reconciliar a situação fiscal do pedido " + pedidoId + " por erro de "
                        + "configuração no servidor. O estado fiscal permanece pendente — não repita "
                        + "automaticamente antes da configuração ser corrigida.",
                500,
                false);
    }

    /**
     * Reconciliação (Gate 3, 10-08-2026) provou que o número fiscal está definitivamente ocupado
     * por identidade fiscal alheia (NF-e cancelada/denegada/inutilizada na base da SEFAZ, ou
     * chave de acesso divergente confirmada) — a NF-e DESTE pedido nunca foi autorizada. O ciclo
     * já foi resolvido como NUMERO_OCUPADO (número consumido, gate liberado) antes desta exceção
     * ser lançada — retryable=true porque uma nova chamada a /emitir já abre um ciclo NOVO, com
     * número seguinte; nunca reaproveita o número ocupado. cStat/xMotivo/serie/numeroNFe/
     * estadoFiscal em `data` documentam a causa fiscal real e qual número foi queimado para quem
     * integra (Gate de contrato OMS, 11-08-2026). cStat fica `null` quando não há código SEFAZ
     * real — nunca um valor sintético como -1, que não é um cStat válido.
     */
    public static BusinessException numeroFiscalOcupado(Long pedidoId, Integer cStat, String xMotivo,
                                                          String serie, Integer numeroNfe) {
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("cStat", cStat);
        data.put("xMotivo", xMotivo != null ? xMotivo : "");
        data.put("serie", serie);
        data.put("numeroNFe", numeroNfe);
        data.put("estadoFiscal", "NUMERO_OCUPADO");
        return new BusinessException(
                "NUMERO_FISCAL_OCUPADO",
                "O número fiscal do pedido " + pedidoId + " está ocupado por outra identidade fiscal na SEFAZ "
                        + "e não pôde ser autorizado. Uma nova tentativa de emissão usará o próximo número.",
                409,
                true,
                data);
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

    // -------------------------------------------------------------------------
    // Gate de cancelamento (nfe_evento, 12-08-2026)
    // -------------------------------------------------------------------------

    /**
     * Outra requisicao ja reivindicou o evento de cancelamento deste pedido (colisao na UNIQUE
     * KEY chave_nfe+tipo_evento+n_seq_evento, ou linha ja em TRANSMITIDO/PENDENTE_CONFIRMACAO) --
     * corrida real, nunca erro de dado. retryable=true: consultar a situacao ou tentar de novo
     * depois resolve sem risco de dois eventos de cancelamento para a mesma NF-e.
     */
    public static BusinessException cancelamentoEmAndamento(Long pedidoId) {
        return new BusinessException(
                "CANCELAMENTO_EM_ANDAMENTO",
                "Já existe um cancelamento em andamento para o pedido " + pedidoId
                        + ". Aguarde a conclusão ou consulte a situação antes de tentar novamente.",
                409,
                true);
    }

    /**
     * O evento de cancelamento tem resultado ainda incerto -- timeout de transporte, cStat=136
     * (evento registrado mas nao vinculado, anomalo) ou cStat=573 (duplicidade de evento, nao
     * prova sozinho o desfecho do evento original). Nunca retransmite as cegas; a reconciliacao
     * via Consulta Situacao (procEventoNFe) e o unico caminho para sair deste estado.
     */
    public static BusinessException cancelamentoAguardandoReconciliacao(Long pedidoId) {
        return new BusinessException(
                "CANCELAMENTO_AGUARDANDO_RECONCILIACAO",
                "O pedido " + pedidoId + " tem um evento de cancelamento com resultado ainda "
                        + "incerto. É necessário reconciliar com a SEFAZ pela mesma identidade do "
                        + "evento antes de qualquer nova tentativa.",
                409,
                true);
    }

    /**
     * A SEFAZ rejeitou o evento de cancelamento (cStat fora da matriz de sucesso/incerto) -- sem
     * efeito em Pedido/NfeEmissao/estoque. retryable=false: uma nova tentativa so faz sentido
     * apos corrigir a causa (data/xJust/nProt); reabre a MESMA identidade fiscal (nSeqEvento=1),
     * nunca cria um evento novo.
     */
    public static BusinessException cancelamentoRejeitado(Integer cStat, String xMotivo) {
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("cStat", cStat);
        data.put("xMotivo", xMotivo != null ? xMotivo : "");
        return new BusinessException(
                "CANCELAMENTO_REJEITADO",
                "Cancelamento rejeitado pela SEFAZ: " + xMotivo,
                422,
                false,
                data);
    }

    public static BusinessException motivoDetalheObrigatorio() {
        return new BusinessException(
                "MOTIVO_DETALHE_OBRIGATORIO",
                "motivoDetalhe é obrigatório quando motivoCodigo = OUTRO.",
                422,
                false);
    }

    // -------------------------------------------------------------------------
    // Gate CC-e (nfe_evento_sequencia / nfe_evento_idempotencia, 12-08-2026)
    // -------------------------------------------------------------------------

    /**
     * O endpoint cru legado (/api/fiscal/nfe/cce) nunca teve contexto de pedido — não tem como
     * participar do gate de sequência/idempotência que protege 110110 depois deste gate existir.
     * Decisão explícita (12-08-2026): a rota continua existindo (nunca 404 silencioso, pra
     * detectar consumidor interno antigo), mas nunca mais toca SEFAZ/nfe_evento/nfe_evento_sequencia.
     * HTTP 410 (Gone): rota operacionalmente retirada, não é erro de dado nem de autenticação.
     */
    public static BusinessException cceEndpointLegadoDesabilitado() {
        return new BusinessException(
                "CCE_ENDPOINT_LEGADO_DESABILITADO",
                "Este endpoint não transmite mais CC-e para a SEFAZ. Use POST "
                        + "/api/app/pedidos/{id}/cce, que participa do gate de sequência/idempotência.",
                410,
                false);
    }

    /** Outra Idempotency-Key já reivindicou a próxima sequência de CC-e desta NF-e — nunca reserva duas ao mesmo tempo. */
    public static BusinessException cceEmAndamento(Long pedidoId) {
        return new BusinessException(
                "CCE_EM_ANDAMENTO",
                "Já existe uma CC-e em andamento para o pedido " + pedidoId
                        + ". Aguarde a conclusão ou consulte a situação antes de tentar novamente.",
                409,
                true);
    }

    /** Resultado da CC-e ainda incerto (timeout, 136 ou 573) — nunca retransmite às cegas. */
    public static BusinessException cceAguardandoReconciliacao(Long pedidoId) {
        return new BusinessException(
                "CCE_AGUARDANDO_RECONCILIACAO",
                "O pedido " + pedidoId + " tem uma CC-e com resultado ainda incerto. É necessário "
                        + "reconciliar com a SEFAZ pela mesma identidade do evento antes de qualquer nova tentativa.",
                409,
                true);
    }

    /** A SEFAZ rejeitou a CC-e — sem efeito em NfeEmissao/Pedido/estoque. */
    public static BusinessException cceRejeitada(Integer cStat, String xMotivo) {
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("cStat", cStat);
        data.put("xMotivo", xMotivo != null ? xMotivo : "");
        return new BusinessException(
                "CCE_REJEITADA",
                "CC-e rejeitada pela SEFAZ: " + xMotivo,
                422,
                false,
                data);
    }

    /** nSeqEvento excederia o limite de 20 (MOC 7.0 / rejeição 594) — terminal, não corrigível por retry. */
    public static BusinessException cceLimiteSequenciaAtingido(String chaveNfe, int ultimoNSeqRegistrado) {
        return new BusinessException(
                "CCE_LIMITE_SEQUENCIA_ATINGIDO",
                "Limite de 20 CC-e por NF-e atingido para a chave " + chaveNfe
                        + " (última sequência registrada: " + ultimoNSeqRegistrado + ").",
                422,
                false);
    }

    /**
     * Reconciliação (573) encontrou a identidade fiscal (chave+110110+nSeq) já registrada, mas com
     * xCorrecao diferente do que esta operação tentou transmitir — a sequência pertence a outro
     * conteúdo (ex.: transmitido por outra via). Nunca finge sucesso para a operação atual;
     * retryable=false porque esta IDENTIDADE está definitivamente ocupada — uma nova tentativa
     * precisa de uma nova Idempotency-Key, que vai reservar a PRÓXIMA sequência.
     */
    public static BusinessException cceEventoDivergente(String chaveNfe, int nSeqEvento) {
        return new BusinessException(
                "CCE_EVENTO_DIVERGENTE",
                "A sequência " + nSeqEvento + " da CC-e para a chave " + chaveNfe + " já está "
                        + "registrada na SEFAZ com conteúdo diferente do enviado nesta operação. "
                        + "Envie uma nova solicitação (nova Idempotency-Key) para a próxima sequência.",
                409,
                false);
    }

    /**
     * A Idempotency-Key informada já foi usada, mas para um pedido/empresa/tipo de evento ou
     * conteúdo diferente do solicitado agora. Nunca decide por analogia — mesma chave só pode
     * representar UMA intenção de negócio.
     */
    public static BusinessException cceIdempotencyKeyConflict(String idempotencyKey) {
        return new BusinessException(
                "CCE_IDEMPOTENCY_KEY_CONFLICT",
                "A Idempotency-Key " + idempotencyKey + " já foi usada para uma operação diferente "
                        + "(pedido, empresa ou conteúdo divergente). Gere uma nova Idempotency-Key.",
                409,
                false);
    }

    /**
     * Bootstrap de sequência histórica (12-08-2026): a chave nunca foi vista localmente por este
     * gate, e não foi possível determinar com certeza se já existe CC-e registrada por um caminho
     * anterior (falha de transporte ou resposta ilegível da Consulta Situação). Nunca assume
     * ultimo_nseq_registrado=0 sem essa certeza — falha fechada, exige investigação/retry.
     */
    public static BusinessException cceBootstrapIndeterminado(String chaveNfe) {
        return new BusinessException(
                "CCE_BOOTSTRAP_INDETERMINADO",
                "Não foi possível determinar com segurança o histórico de CC-e da chave " + chaveNfe
                        + " antes da primeira reserva de sequência. Tente novamente; se persistir, "
                        + "requer investigação manual antes de emitir CC-e para esta NF-e.",
                503,
                true);
    }

    // -------------------------------------------------------------------------
    // PUT /api/app/empresas/{id} — atualização parcial (Rota B, 13-08-2026)
    // -------------------------------------------------------------------------

    /**
     * Campo obrigatório do estado persistido enviado como {@code null} explícito no JSON — omitir
     * o campo preserva o valor atual; enviá-lo como null pede pra removê-lo, o que nunca é seguro
     * pra um campo NOT NULL. Distinto de "campo ausente" (nunca chega aqui) e de "valor vazio"
     * (ver {@link #campoObrigatorioInvalido}).
     */
    public static BusinessException campoObrigatorioNaoPodeSerRemovido(String campo) {
        return new BusinessException(
                "EMPRESA_CAMPO_OBRIGATORIO_NULO",
                campo + " não pode ser removido (enviado como null) — omita o campo no JSON "
                        + "para preservar o valor atual, ou envie um valor válido para atualizá-lo.",
                422,
                false);
    }

    /** Campo obrigatório presente no JSON, mas com valor vazio/inválido — mesma semântica de validação já usada em POST/criação. */
    public static BusinessException campoObrigatorioInvalido(String campo, String motivo) {
        return new BusinessException(
                "EMPRESA_CAMPO_OBRIGATORIO_INVALIDO",
                campo + " " + motivo,
                422,
                false);
    }

    /**
     * CNPJ é a identidade fiscal usada por {@code nfe_sequencia}/{@code nfe_emissao}
     * (chaveadas por {@code cnpj_emitente} em texto, não por {@code empresa_id}) — trocar o CNPJ
     * de uma empresa existente por este endpoint deixaria ciclos/numeração órfãos. O valor pode
     * ser reenviado igual (idempotente) ou omitido (preserva), nunca alterado.
     */
    public static BusinessException cnpjImutavelNaAtualizacao(String cnpjAtual, String cnpjSolicitado) {
        return new BusinessException(
                "EMPRESA_CNPJ_IMUTAVEL",
                "CNPJ não pode ser alterado por este endpoint (atual=" + cnpjAtual
                        + ", solicitado=" + cnpjSolicitado + "). A numeração e os ciclos fiscais são "
                        + "vinculados ao CNPJ da empresa; omita o campo para preservar o valor atual.",
                422,
                false);
    }

    // -------------------------------------------------------------------------
    // Recovery administrativo do ciclo do nNF (02-09-2026) — estado ABANDONADO.
    // -------------------------------------------------------------------------

    /**
     * Texto destinado a um campo NF-e do tipo {@code TString} (xProd, natOp, xNome, xLgr, nro,
     * xBairro, xMun, infCpl) contém caractere fora do conjunto oficial ({@code U+0020}–{@code U+00FF},
     * pattern da NT 2023.002), começa/termina com espaço, ou excede o {@code maxLength} do XSD.
     * Detectado ANTES de persistir o pedido (POST /pedidos) e, como defesa em profundidade, ANTES
     * de {@code abrirCiclo()} no {@code /emitir}. {@code retryable=false}: exige corrigir a origem
     * no OMS. O texto ofensor NUNCA é ecoado — só o nome do campo (e {@code itemIndex} quando item).
     */
    public static BusinessException fiscalTextInvalidChars(String field, Integer itemIndex,
                                                          String reason, String detalhe) {
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("field", field);
        if (itemIndex != null) {
            data.put("itemIndex", itemIndex);
        }
        // reason: código estável (nome do enum Motivo do ValidadorTextoFiscalNfe) para o OMS
        // ramificar sem parsear a mensagem — CARACTERE_NAO_PERMITIDO / ESPACO_NA_BORDA /
        // ACIMA_DO_MAX_LENGTH. O errorCode continua único (FISCAL_TEXT_INVALID_CHARS): é um
        // envelope de validação de texto fiscal, o motivo específico vem aqui.
        if (reason != null) {
            data.put("reason", reason);
        }
        String alvo = itemIndex != null
                ? "A descrição do item " + (itemIndex + 1)
                : "O campo '" + field + "'";
        return new BusinessException(
                "FISCAL_TEXT_INVALID_CHARS",
                alvo + " " + detalhe + ".",
                422,
                false,
                data);
    }

    /** nfe_emissao inexistente no abandono administrativo. */
    public static BusinessException emissaoNaoEncontrada(Long emissaoId) {
        return new BusinessException(
                "EMISSAO_NOT_FOUND",
                "Ciclo de emissão não encontrado: id=" + emissaoId,
                404);
    }

    /**
     * Abandono só é permitido para um ciclo em AGUARDANDO_CORRECAO (rejeição corrigível cujo dado
     * de origem não pode mais ser corrigido). Qualquer outro estado — RESERVADO/TRANSMITIDO/
     * PENDENTE_CONFIRMACAO (ainda em voo), AUTORIZADO/DENEGADO/NUMERO_OCUPADO (terminais reais),
     * CANCELADO, ABANDONADO — é recusado.
     */
    public static BusinessException cicloNaoAbandonavel(Long emissaoId, String estadoAtual) {
        return new BusinessException(
                "CICLO_NAO_ABANDONAVEL",
                "Ciclo de emissão id=" + emissaoId + " está em '" + estadoAtual + "' — o abandono só "
                        + "é permitido a partir de AGUARDANDO_CORRECAO.",
                422,
                false);
    }

    /**
     * Ciclo com protocolo SEFAZ (nprot preenchido) teve destino real — autorizado ou denegado —
     * e nunca é abandonável, independentemente do estado local.
     */
    public static BusinessException cicloComProtocolo(Long emissaoId) {
        return new BusinessException(
                "CICLO_COM_PROTOCOLO",
                "Ciclo de emissão id=" + emissaoId + " tem protocolo SEFAZ registrado — teve destino "
                        + "definitivo e não pode ser abandonado.",
                422,
                false);
    }

    /**
     * Ciclo NORMAL já substituído por uma emissão em contingência (existe uma linha filha com
     * emissao_origem_id apontando para ele). Liberar o gate da série aqui deixaria a filha órfã.
     */
    public static BusinessException cicloSubstituido(Long emissaoId, Long filhaId) {
        return new BusinessException(
                "CICLO_SUBSTITUIDO",
                "Ciclo de emissão id=" + emissaoId + " já foi substituído por contingência (emissão "
                        + "filha id=" + filhaId + ") — resolva o ciclo ativo, não abandone a origem.",
                422,
                false);
    }

    /**
     * "Transporte não entregue" só é aplicável a um ciclo em TRANSMITIDO ou PENDENTE_CONFIRMACAO —
     * ou seja, que saiu do Borurio mas não obteve resultado fiscal. Qualquer outro estado tem
     * resultado (AUTORIZADO/DENEGADO/NUMERO_OCUPADO), nunca saiu (RESERVADO), ou já foi tratado
     * por outro recovery (ABANDONADO/AGUARDANDO_CORRECAO/CANCELADO/TRANSPORTE_NAO_ENTREGUE).
     */
    public static BusinessException cicloNaoElegivelTransporte(Long emissaoId, String estadoAtual) {
        return new BusinessException(
                "CICLO_NAO_ELEGIVEL_TRANSPORTE",
                "Ciclo de emissão id=" + emissaoId + " está em '" + estadoAtual + "' — 'transporte não "
                        + "entregue' só se aplica a TRANSMITIDO ou PENDENTE_CONFIRMACAO.",
                422,
                false);
    }

    /**
     * O ciclo já passou por pelo menos uma consulta de reconciliação à SEFAZ
     * ({@code tentativas_consulta > 0}) — existe um resultado real da consulta que este recovery
     * de transporte não pode sobrepor. Use a reconciliação normal.
     */
    public static BusinessException cicloJaReconciliado(Long emissaoId, int tentativasConsulta) {
        return new BusinessException(
                "CICLO_JA_RECONCILIADO",
                "Ciclo de emissão id=" + emissaoId + " já teve " + tentativasConsulta + " consulta(s) de "
                        + "reconciliação à SEFAZ — resolva pela reconciliação, não pelo recovery de transporte.",
                422,
                false);
    }

    /**
     * Existe evidência persistida de que a SEFAZ recebeu/processou o lote (nfe_documento com
     * n_prot, dh_recbto ou xml_protocolo preenchidos) — o recovery de "transporte não entregue"
     * é proibido nesse caso: a NF-e pode existir fiscalmente.
     */
    public static BusinessException evidenciaDeProcessamento(Long emissaoId, String chaveNfe) {
        return new BusinessException(
                "EVIDENCIA_DE_PROCESSAMENTO",
                "Ciclo de emissão id=" + emissaoId + " (chave " + chaveNfe + ") tem evidência de "
                        + "recepção/processamento pela SEFAZ — não pode ser marcado como transporte não "
                        + "entregue. Resolva por consulta/reconciliação.",
                422,
                false);
    }
}
