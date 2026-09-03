/********************************************************************************************
 * MIGRAÇÃO OFICIAL NCM 2025 – PUCOMEX / SISCOMEX
 *
 * Arquivo: V005__ncm_schema_oficial_2025.sql
 * Projeto: Borurio ERP Fiscal BR
 * Data: 07/11/2025
 * Autor: Bruno Ribeiro / TecGuard Solutions
 *
 * Descrição:
 * Esta migração cria a estrutura oficial de tabelas de controle da Nomenclatura Comum
 * do Mercosul (NCM) vigente conforme dados oficiais da Receita Federal do Brasil e Pucomex.
 *
 * Histórico:
 * - Base JSON obtida de: https://portalunico.siscomex.gov.br/classif/api/publico/nomenclatura/download/json
 * - Total de registros importados: 15.144
 * - Fonte: Sistema Classif (Portal Único do Comércio Exterior – RFB)
 ********************************************************************************************/

-- ==========================================================================================
-- TABELA: ncm
-- ==========================================================================================

DROP TABLE IF EXISTS ncm;

CREATE TABLE ncm (
                     id INT AUTO_INCREMENT PRIMARY KEY,
                     codigo VARCHAR(10) NOT NULL COMMENT 'Código NCM de até 8 dígitos',
                     descricao VARCHAR(500) NOT NULL COMMENT 'Descrição legal da nomenclatura',
                     vigencia_inicio DATE NULL COMMENT 'Data de início da vigência',
                     vigencia_fim DATE NULL COMMENT 'Data de fim da vigência',
                     tipo_ato_legal VARCHAR(100) NULL COMMENT 'Tipo do ato legal que instituiu a vigência',
                     orgao_ato_legal VARCHAR(100) NULL COMMENT 'Órgão emissor do ato legal',
                     numero_ato_legal VARCHAR(50) NULL COMMENT 'Número do ato legal',
                     ano_ato_legal VARCHAR(10) NULL COMMENT 'Ano do ato legal',
                     criado_em TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT 'Timestamp de criação do registro'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_ncm_codigo ON ncm(codigo);
CREATE INDEX idx_ncm_vigencia ON ncm(vigencia_inicio, vigencia_fim);

-- ==========================================================================================
-- TABELA: ncm_sync_log
-- ==========================================================================================

DROP TABLE IF EXISTS ncm_sync_log;

CREATE TABLE ncm_sync_log (
                              id INT AUTO_INCREMENT PRIMARY KEY,
                              arquivo_origem VARCHAR(255) NOT NULL COMMENT 'Nome do arquivo de origem do dataset',
                              data_execucao DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'Data e hora da execução da carga',
                              total_registros INT NOT NULL COMMENT 'Total de registros importados na sincronização',
                              origem VARCHAR(255) DEFAULT 'Pucomex/Siscomex Oficial' COMMENT 'Fonte dos dados',
                              observacao TEXT COMMENT 'Observações ou logs técnicos do processo'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ==========================================================================================
-- INSERT INICIAL DE LOG DE IMPORTAÇÃO
-- ==========================================================================================

INSERT INTO ncm_sync_log (arquivo_origem, total_registros, origem, observacao)
VALUES ('Tabela_NCM_Vigente_20251107.json', 15144, 'Pucomex/Siscomex Oficial', 'Carga fiscal automatizada via PowerShell');
