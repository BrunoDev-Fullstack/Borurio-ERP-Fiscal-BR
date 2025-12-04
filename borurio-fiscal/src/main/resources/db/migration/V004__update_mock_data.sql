/* =============================================================================
   MIGRATION: V004__update_mock_data.sql
   MÓDULO   : borurio-fiscal
   FINALIDADE:
       Atualizar registros simulados (mock) para testes de NF-e com SEFAZ-SP.

   CARACTERÍSTICAS:
       • Usada exclusivamente em DEV / HOM
       • Sem dependência de arquivos externos (compatível com Docker/Flyway)
       • Idempotente e segura
       • Padronizada com V001, V002 e V003

   AUTOR    : Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
   REVISÃO  : 2025-12-02
   COMPAT   : MySQL 8.4 / Flyway 10.x
   ============================================================================= */


/* -----------------------------------------------------------------------------
   1. LIMPEZA DE MOCKS ANTERIORES
   ----------------------------------------------------------------------------- */
DELETE FROM nfe_log
WHERE tipo_evento IN ('AUTORIZACAO', 'CANCELAMENTO', 'CARTA_CORRECAO');


/* -----------------------------------------------------------------------------
   2. XMLS FISCAIS SIMULADOS (mock)
      Observação:
      • Conteúdos reduzidos propositalmente.
      • Padrão seguro para ambiente DEV.
   ----------------------------------------------------------------------------- */

SET @xml_envio_mock = '<enviNFe versao="4.00"><NFe><infNFe Id="NFeMock" /></NFe></enviNFe>';
SET @xml_retorno_mock = '<retEnviNFe versao="4.00"><cStat>100</cStat><xMotivo>Autorizado o uso da NF-e</xMotivo></retEnviNFe>';


/* -----------------------------------------------------------------------------
   3. INSERÇÃO DOS MOCKS DE NF-e / SEFAZ-SP
   ----------------------------------------------------------------------------- */
INSERT INTO nfe_log (
    chave_nfe,
    tipo_evento,
    descricao,
    status,
    xml_envio,
    xml_retorno,
    cnpj_emitente,
    usuario,
    data_evento
) VALUES
      (
          '35251012345678000123550010000000011000000010',
          'AUTORIZACAO',
          'Envio de NF-e autorizado (mock)',
          'AUTORIZADA',
          @xml_envio_mock,
          @xml_retorno_mock,
          '12345678000123',
          'system-dev',
          NOW()
      ),
      (
          '35251012345678000123550010000000022000000020',
          'CANCELAMENTO',
          'NF-e cancelada pela SEFAZ-SP (mock)',
          'CANCELADA',
          @xml_envio_mock,
          @xml_retorno_mock,
          '12345678000123',
          'system-dev',
          NOW()
      ),
      (
          '35251012345678000123550010000000033000000030',
          'CARTA_CORRECAO',
          'Carta de correção aplicada (mock)',
          'CORRIGIDA',
          @xml_envio_mock,
          @xml_retorno_mock,
          '12345678000123',
          'system-dev',
          NOW()
      );


/* -----------------------------------------------------------------------------
   4. CRIAÇÃO DE ÍNDICES (com idempotência segura)
   ----------------------------------------------------------------------------- */

-- Índice: tipo_evento
SET @idx_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_NAME = 'nfe_log'
      AND INDEX_NAME = 'idx_nfe_log_tipo_evento'
      AND TABLE_SCHEMA = DATABASE()
);

SET @stmt := IF(
    @idx_exists = 0,
    'CREATE INDEX idx_nfe_log_tipo_evento ON nfe_log (tipo_evento);',
    'SELECT "Índice idx_nfe_log_tipo_evento já existe";'
);
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Índice: data_evento
SET @idx_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_NAME = 'nfe_log'
      AND INDEX_NAME = 'idx_nfe_log_data_evento'
      AND TABLE_SCHEMA = DATABASE()
);

SET @stmt := IF(
    @idx_exists = 0,
    'CREATE INDEX idx_nfe_log_data_evento ON nfe_log (data_evento);',
    'SELECT "Índice idx_nfe_log_data_evento já existe";'
);
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;


/* -----------------------------------------------------------------------------
   5. STATUS FINAL DA MIGRATION
   ----------------------------------------------------------------------------- */
SELECT '✔ V004__update_mock_data.sql aplicada com sucesso (Mock SEFAZ-SP)' AS status;

