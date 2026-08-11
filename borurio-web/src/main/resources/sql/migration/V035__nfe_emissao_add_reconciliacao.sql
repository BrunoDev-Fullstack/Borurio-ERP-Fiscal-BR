-- V035: Suporte a reconciliacao (Gate 3 da maquina de estados fiscal de numeracao).
-- ultima_consulta_em/tentativas_consulta sustentam o claim atomico da janela de backoff da
-- Consulta Situacao SEFAZ (consSitNFe) sobre um ciclo TRANSMITIDO/PENDENTE_CONFIRMACAO -- nunca
-- duas chamadas concorrentes devem consultar a SEFAZ pela mesma emissao ao mesmo tempo.

ALTER TABLE nfe_emissao
    ADD COLUMN ultima_consulta_em DATETIME NULL
        COMMENT 'Timestamp da ultima consulta de reconciliacao (Gate 3); NULL = nunca consultado' AFTER resolvido_em,
    ADD COLUMN tentativas_consulta INT NOT NULL DEFAULT 0
        COMMENT 'Quantidade de consultas de reconciliacao ja realizadas para este ciclo (Gate 3)' AFTER ultima_consulta_em;
