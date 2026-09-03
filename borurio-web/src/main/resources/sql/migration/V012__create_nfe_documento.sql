-- =============================================================================
-- V012__create_nfe_documento.sql
-- Tabela de estado do documento fiscal NF-e 4.00
-- -----------------------------------------------------------------------------
-- Diferença em relação a nfe_log:
--   nfe_log      → diário de eventos (uma NF-e pode ter N eventos)
--   nfe_documento → estado atual da NF-e (uma linha por chave, atualizado)
--
-- Campos fiscais obrigatórios pela legislação brasileira:
--   chave_nfe  → chave de acesso (44 dígitos) — identificador único nacional
--   n_prot     → número do protocolo SEFAZ (só presente quando autorizada/cancelada)
--   c_stat     → código de status SEFAZ (100=autorizada, 101=cancelada, 225=rejeitada…)
--   xml_nfe    → XML assinado enviado (para reemissão de DANFE e consulta)
--   xml_protocolo → nfeProc completo com protocolo embutido (padrão SEFAZ para arquivamento)
-- =============================================================================

CREATE TABLE IF NOT EXISTS nfe_documento (
    id                BIGINT         AUTO_INCREMENT PRIMARY KEY,
    chave_nfe         VARCHAR(44)    NOT NULL                         COMMENT 'Chave de acesso NF-e (44 dígitos)',
    n_nf              VARCHAR(9)     NOT NULL                         COMMENT 'Número da NF-e',
    serie             VARCHAR(3)     NOT NULL                         COMMENT 'Série da NF-e',
    cnpj_emitente     VARCHAR(14)    NOT NULL                         COMMENT 'CNPJ do emitente (somente dígitos)',
    dest_cnpj_cpf     VARCHAR(14)                                     COMMENT 'CNPJ ou CPF do destinatário',
    dest_razao_social VARCHAR(60)                                     COMMENT 'Razão social do destinatário',
    valor_total       DECIMAL(15,2)                                   COMMENT 'Valor total da NF-e',
    tp_amb            TINYINT(1)     NOT NULL DEFAULT 2               COMMENT '1=Produção 2=Homologação',
    c_stat            VARCHAR(3)                                      COMMENT 'Último cStat SEFAZ (100, 101, 225…)',
    x_motivo          VARCHAR(255)                                    COMMENT 'Descrição do status SEFAZ',
    n_prot            VARCHAR(15)                                     COMMENT 'Número do protocolo de autorização/cancelamento',
    dh_recbto         DATETIME                                        COMMENT 'Data/hora do recebimento pelo SEFAZ',
    xml_nfe           LONGTEXT                                        COMMENT 'XML assinado da NF-e (sem protocolo)',
    xml_protocolo     LONGTEXT                                        COMMENT 'nfeProc: XML + protocolo embutido (arquivamento fiscal)',
    danfe_path        VARCHAR(500)                                    COMMENT 'Caminho/URL do DANFE gerado',
    data_emissao      DATETIME       NOT NULL                         COMMENT 'dhEmi da NF-e',
    data_criacao      DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    data_atualizacao  DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uq_nfe_doc_chave    (chave_nfe),
    INDEX idx_nfe_doc_emitente     (cnpj_emitente),
    INDEX idx_nfe_doc_cstat        (c_stat),
    INDEX idx_nfe_doc_serie_num    (cnpj_emitente, serie, n_nf),
    INDEX idx_nfe_doc_dest         (dest_cnpj_cpf)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Estado atual de cada documento NF-e: protocolo, status SEFAZ e XMLs para arquivamento fiscal.';
