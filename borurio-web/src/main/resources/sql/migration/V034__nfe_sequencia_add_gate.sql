-- V034: Gate fiscal por sequencia (Gate 1 / Gate 4 da maquina de estados fiscal de numeracao).
-- emissao_ativa_id aponta para nfe_emissao.id enquanto existir um ciclo de nNF nao-terminal
-- (RESERVADO, TRANSMITIDO, AGUARDANDO_CORRECAO ou PENDENTE_CONFIRMACAO) para o par
-- (cnpj_emitente, serie). So e liberado (volta a NULL) quando o ciclo chega a AUTORIZADO ou
-- DENEGADO. Nenhuma FK fisica: nfe_emissao ainda nao existe quando nfe_sequencia e criada pela
-- primeira vez em alguns fluxos (sync via /api/integration/fiscal-numbering), e o acoplamento
-- fica melhor resolvido na camada de servico (NfeEmissaoService), nao no schema.

ALTER TABLE nfe_sequencia
    ADD COLUMN emissao_ativa_id BIGINT NULL
        COMMENT 'id da nfe_emissao ativa nesta serie; NULL = gate livre' AFTER ultimo_numero;
