/********************************************************************************************
 * MIGRAÇÃO OFICIAL — TABELA NCM 2025 (PUCOMEX / SISCOMEX)
 * ------------------------------------------------------------------------------------------
 * Arquivo : V005__ncm_schema_oficial_2025.sql
 * Projeto : Borurio ERP Fiscal BR
 * Módulo  : borurio-fiscal
 * Autor   : Bruno Ribeiro — TecGuard Solutions / DevSecOps
 * Data    : 07/11/2025
 *
 * Descrição:
 *   Criação da estrutura completa da tabela NCM e do log de sincronização,
 *   utilizando como base os dados oficiais fornecidos pelo Portal Único
 *   (PUCOMEX / SISCOMEX — Receita Federal do Brasil).
 *
 * Fonte oficial dos dados:
 *   https://portalunico.siscomex.gov.br/classif/api/publico/nomenclatura/download/json
 *
 * Total esperado: 15.144 registros
 *
 * Padrões adotados:
 *   • DevSecOps
 *   • Idempotência
 *   • Auditoria e rastreabilidade
 *   • Observabilidade e histórico de sincronização
 ********************************************************************************************/


/* ==========================================================================================
   TABELA PRINCIPAL — ncm
   ------------------------------------------------------------------------------------------
   Armazena a nomenclatura completa vigente da NCM com base na legislação federal.
   ========================================================================================== */

DROP TABLE IF EXISTS `ncm`;

CREATE TABLE `ncm` (
                       id INT AUTO_INCREMENT PRIMARY KEY,
                       codigo VARCHAR(10) NOT NULL COMMENT 'Código NCM de até 8 dígitos',
                       descricao VARCHAR(500) NOT NULL COMMENT 'Descrição legal da nomenclatura',
                       vigencia_inicio DATE NULL COMMENT 'Data inicial da vigência',
                       vigencia_fim DATE NULL COMMENT 'Data final da vigência',
                       tipo_ato_legal VARCHAR(100) NULL COMMENT 'Tipo do ato legal da vigência',
                       orgao_ato_legal VARCHAR(100) NULL COMMENT 'Órgão emissor do ato legal',
                       numero_ato_legal VARCHAR(50) NULL COMMENT 'Número do ato legal',
                       ano_ato_legal VARCHAR(10) NULL COMMENT 'Ano do ato legal',
                       criado_em TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT 'Data de criação do registro'
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci;

-- Índices para performance fiscal
CREATE INDEX `idx_ncm_codigo`   ON ncm (codigo);
CREATE INDEX `idx_ncm_vigencia` ON ncm (vigencia_inicio, vigencia_fim);



/* ==========================================================================================
   TABELA DE AUDITORIA — ncm_sync_log
   ------------------------------------------------------------------------------------------
   Registro das cargas realizadas (dataset 2025 oficial).
   ========================================================================================== */

DROP TABLE IF EXISTS `ncm_sync_log`;

CREATE TABLE `ncm_sync_log` (
                                id INT AUTO_INCREMENT PRIMARY KEY,
                                arquivo_origem VARCHAR(255) NOT NULL COMMENT 'Nome do arquivo/data source utilizado',
                                data_execucao DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'Momento da importação',
                                total_registros INT NOT NULL COMMENT 'Quantidade total importada',
                                origem VARCHAR(255) DEFAULT 'Pucomex / Siscomex Oficial' COMMENT 'Fonte dos dados',
                                observacao TEXT COMMENT 'Observações técnicas do processo de sincronização'
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci;



/* ==========================================================================================
   REGISTRO INICIAL DE AUDITORIA
   ------------------------------------------------------------------------------------------
   Representa a primeira importação oficial da tabela NCM 2025.
   ========================================================================================== */

INSERT INTO `ncm_sync_log`
(arquivo_origem, total_registros, origem, observacao)
VALUES
    ('Tabela_NCM_Vigente_20251107.json', 15144, 'Pucomex / Siscomex Oficial', 'Carga fiscal automatizada via script PowerShell oficial 2025');
