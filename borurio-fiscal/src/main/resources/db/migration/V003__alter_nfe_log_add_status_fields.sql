/* =============================================================================
   VERSÃO: V003__alter_nfe_log_add_status_fields.sql
   MÓDULO : Borurio Fiscal (NF-e / SEFAZ-SP / Auditoria)
   FINALIDADE:
       Complementar a tabela nfe_log com campos adicionais de auditoria,
       controle fiscal, XML técnico e rastreabilidade do emitente.

   CARACTERÍSTICAS:
       • Idempotente (seguro para execuções repetidas)
       • Compatível com MySQL 8.4
       • Padrão DevSecOps / Observabilidade / Compliance Fiscal

   AUTOR  : Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
   REVISÃO: 2025-12-02
   ============================================================================= */


/* -----------------------------------------------------------------------------
   1. COLUNA STATUS — Situação do processamento do evento SEFAZ
   ----------------------------------------------------------------------------- */
SET @col_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'nfe_log'
      AND COLUMN_NAME = 'status'
);

SET @stmt := IF(
    @col_exists = 0,
    'ALTER TABLE nfe_log ADD COLUMN status VARCHAR(20) NULL COMMENT "Status do evento (SUCESSO, ERRO, PROCESSANDO, AUTORIZADA, CANCELADA, CORRIGIDA)" AFTER descricao;',
    'SELECT "Coluna status já existe";'
);

PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;



/* -----------------------------------------------------------------------------
   2. CAMPOS XML — XML técnico enviado/retornado da SEFAZ
   ----------------------------------------------------------------------------- */
SET @col_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'nfe_log'
      AND COLUMN_NAME = 'xml_envio'
);

SET @stmt := IF(
    @col_exists = 0,
    'ALTER TABLE nfe_log ADD COLUMN xml_envio LONGTEXT NULL COMMENT "XML enviado à SEFAZ (enviNFe)" AFTER status;',
    'SELECT "Coluna xml_envio já existe";'
);

PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;


SET @col_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'nfe_log'
      AND COLUMN_NAME = 'xml_retorno'
);

SET @stmt := IF(
    @col_exists = 0,
    'ALTER TABLE nfe_log ADD COLUMN xml_retorno LONGTEXT NULL COMMENT "XML de retorno da SEFAZ (retEnviNFe)" AFTER xml_envio;',
    'SELECT "Coluna xml_retorno já existe";'
);

PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;



/* -----------------------------------------------------------------------------
   3. CNPJ EMITENTE — Identificação de origem do registro fiscal
   ----------------------------------------------------------------------------- */
SET @col_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'nfe_log'
      AND COLUMN_NAME = 'cnpj_emitente'
);

SET @stmt := IF(
    @col_exists = 0,
    'ALTER TABLE nfe_log ADD COLUMN cnpj_emitente VARCHAR(20) NULL COMMENT "CNPJ do emitente responsável pelo documento" AFTER xml_retorno;',
    'SELECT "Coluna cnpj_emitente já existe";'
);

PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;



/* -----------------------------------------------------------------------------
   4. ÍNDICES — Otimização das consultas: status e cnpj_emitente
   ----------------------------------------------------------------------------- */
SET @idx_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'nfe_log'
      AND INDEX_NAME = 'idx_nfe_log_status'
);

SET @stmt := IF(
    @idx_exists = 0,
    'CREATE INDEX idx_nfe_log_status ON nfe_log (status);',
    'SELECT "Índice idx_nfe_log_status já existe";'
);

PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;


SET @idx_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'nfe_log'
      AND INDEX_NAME = 'idx_nfe_log_cnpj_emitente'
);

SET @stmt := IF(
    @idx_exists = 0,
    'CREATE INDEX idx_nfe_log_cnpj_emitente ON nfe_log (cnpj_emitente);',
    'SELECT "Índice idx_nfe_log_cnpj_emitente já existe";'
);

PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;



/* -----------------------------------------------------------------------------
   5. ATUALIZAÇÃO DE REGISTROS EXISTENTES — Normalização dos status
   ----------------------------------------------------------------------------- */
UPDATE nfe_log
SET status = CASE
                 WHEN tipo_evento = 'Autorização'        THEN 'AUTORIZADA'
                 WHEN tipo_evento = 'Cancelamento'       THEN 'CANCELADA'
                 WHEN tipo_evento = 'Carta de Correção'  THEN 'CORRIGIDA'
                 ELSE COALESCE(status, 'PROCESSANDO')
    END
WHERE status IS NULL;



/* =============================================================================
   FIM DA MIGRAÇÃO V003
   ============================================================================= */
