-- V037: Infraestrutura de sequenciamento e idempotencia de negocio para eventos com multiplas
-- ocorrencias fiscais legitimas (CC-e 110110 hoje). Gate de cancelamento (V036) tem identidade
-- fixa (nSeqEvento=1 sempre); CC-e tem identidade CRESCENTE legitima (1..20) -- exige um gate de
-- reserva atomica proprio (mesmo padrao ja comprovado de nfe_sequencia/Gate 1) e uma camada de
-- idempotencia de OPERACAO separada da identidade fiscal (achado de banca, 12-08-2026): duas
-- Idempotency-Key diferentes podem legitimamente reutilizar a MESMA identidade fiscal
-- (chave+tipo+nSeq) ao longo do tempo (tentativa rejeitada -> nova tentativa corrigida), e cada
-- uma precisa reproduzir o PRÓPRIO desfecho no replay -- nunca o desfecho de uma operacao alheia
-- que por acaso reutilizou a mesma linha de nfe_evento depois.

CREATE TABLE nfe_evento_sequencia (
    id                      BIGINT      NOT NULL AUTO_INCREMENT,
    chave_nfe               VARCHAR(44) NOT NULL,
    tipo_evento             VARCHAR(6)  NOT NULL,
    ultimo_nseq_registrado  INT         NOT NULL DEFAULT 0 COMMENT 'so avanca quando o candidato ativo vira REGISTRADO (ou e confirmado ocupado por terceiro -- ver CCE_EVENTO_DIVERGENTE)',
    evento_ativo_id         BIGINT      NULL COMMENT 'FK logica p/ nfe_evento em voo/pendente/rejeitado-reaberto -- NULL = gate livre',
    created_at              DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_nfe_evento_sequencia (chave_nfe, tipo_evento)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='Gate de reserva atomica da proxima sequencia de evento (CC-e) -- mesmo padrao de nfe_sequencia (Gate 1). Linha criada via bootstrap/lazy-reconciliation na primeira chamada pra cada chave, nunca assume ultimo_nseq_registrado=0 sem checar CC-e historica.';

CREATE TABLE nfe_evento_idempotencia (
    id                        BIGINT       NOT NULL AUTO_INCREMENT,
    idempotency_key           VARCHAR(36)  NOT NULL COMMENT 'header Idempotency-Key (UUID) do pedido',
    empresa_id                BIGINT       NOT NULL,
    pedido_id                 BIGINT       NOT NULL,
    tipo_evento                VARCHAR(6)   NOT NULL,
    conteudo_hash              VARCHAR(64)  NOT NULL COMMENT 'SHA-256 de chaveNfe+conteudoEvento normalizado (mesmo trim usado no XML)',
    nfe_evento_id              BIGINT       NOT NULL COMMENT 'nasce na MESMA transacao do nfe_evento -- nunca null',
    -- snapshot da OPERACAO -- nunca dereferenciado a partir do estado atual de nfe_evento; uma
    -- identidade fiscal pode ser reaberta por OUTRA Idempotency-Key depois, e este snapshot
    -- precisa continuar refletindo o desfecho desta operacao especificamente.
    estado_resultado           VARCHAR(30)  NOT NULL DEFAULT 'PREPARADO' COMMENT 'PREPARADO ANTES de qualquer rede -- nunca PENDENTE_CONFIRMACAO por omissao',
    cstat_resultado            INT          NULL,
    xmotivo_resultado          VARCHAR(255) NULL,
    nprot_resultado            VARCHAR(20)  NULL,
    dh_reg_evento_resultado    VARCHAR(30)  NULL,
    resolvido_em               DATETIME     NULL,
    created_at                 DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                 DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_nfe_evento_idempotencia_key (idempotency_key),
    KEY idx_nfe_evento_idempotencia_pedido (pedido_id),
    KEY idx_nfe_evento_idempotencia_empresa (empresa_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='Idempotencia de OPERACAO (identidade da intencao OMS) -- distinta da identidade fiscal em nfe_evento. Replay sempre confere empresa_id+pedido_id+tipo_evento antes de devolver qualquer dado (nunca cruza tenant).';

ALTER TABLE nfe_evento
    ADD COLUMN conteudo_evento VARCHAR(1000) NULL
        COMMENT 'xCorrecao da CC-e ou conteudo textual equivalente de eventos futuros -- nunca reaproveita justificativa (semantica de cancelamento), coluna nova pra nao alterar V036 ja commitada'
        AFTER justificativa;
