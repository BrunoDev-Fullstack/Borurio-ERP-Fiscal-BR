/* =============================================================================
   MIGRAÇÃO V001 — CRIAÇÃO DA TABELA DE LOG FISCAL (NF-e)
   -----------------------------------------------------------------------------
   Módulo: Borurio Fiscal (NF-e / SEFAZ-SP / Auditoria)
   Finalidade: Tabela de auditoria dos eventos fiscais (NF-e)
   Banco: MySQL 8.4 — Charset utf8mb4
   Padrão: DevSecOps • Observabilidade • Compliance Fiscal
   Autor: Bruno Ribeiro
   Última revisão: 2025-12-02
   ============================================================================= */

/* -----------------------------------------------------------------------------
   1. CRIAÇÃO DA TABELA PRINCIPAL DE LOGS FISCAIS (nfe_log)
   ----------------------------------------------------------------------------- */
CREATE TABLE IF NOT EXISTS `nfe_log` (
                                         `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Identificador único do log',
                                         `chave_nfe` VARCHAR(44) NOT NULL COMMENT 'Chave da NF-e (44 dígitos)',
    `tipo_evento` VARCHAR(100) NOT NULL COMMENT 'Tipo de evento fiscal (Autorização, Cancelamento, Inutilização, etc.)',
    `descricao` TEXT NULL COMMENT 'Descrição detalhada do evento registrado',
    `status` VARCHAR(20) NULL COMMENT 'Status do evento (SUCESSO, ERRO, PROCESSANDO)',
    `xml_envio` LONGTEXT NULL COMMENT 'XML completo enviado à SEFAZ (enviNFe)',
    `xml_retorno` LONGTEXT NULL COMMENT 'XML de resposta da SEFAZ (retEnviNFe)',
    `cnpj_emitente` VARCHAR(14) NOT NULL COMMENT 'CNPJ do emitente sem pontuação',
    `usuario` VARCHAR(100) NULL COMMENT 'Usuário/sistema responsável pelo evento',
    `data_evento` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Momento do registro do evento fiscal',

    -- Índices
    PRIMARY KEY (`id`),
    INDEX `idx_chave_nfe` (`chave_nfe`),
    INDEX `idx_cnpj_emitente` (`cnpj_emitente`),
    INDEX `idx_tipo_evento` (`tipo_evento`)
    )
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_unicode_ci
    COMMENT = 'Tabela de auditoria dos eventos fiscais da NF-e — transmissões, retornos e falhas.';

/* -----------------------------------------------------------------------------
   2. OBSERVAÇÕES DE SEGURANÇA E COMPLIANCE
   -----------------------------------------------------------------------------
   - xml_envio/xml_retorno podem conter dados sensíveis; proteger com roles e logs.
   - Recomenda-se mascarar dados em ambientes de homologação (LGPD).
   - A tabela deve ser mantida indefinidamente por compliance fiscal.
   - Uso recomendado: Prometheus, Loki e Elastic para rastreabilidade.
   ----------------------------------------------------------------------------- */

/* =============================================================================
   FIM DA MIGRAÇÃO V001
   ============================================================================= */
