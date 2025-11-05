/* =============================================================================
   MIGRAÇÃO V001 — CRIAÇÃO DA TABELA DE LOG FISCAL (NF-e)
   -----------------------------------------------------------------------------
   Módulo: Borurio Fiscal (NF-e / SEFAZ-SP / Auditoria)
   Finalidade: Criação da tabela de auditoria e rastreabilidade dos eventos fiscais (NF-e)
   Banco de Dados: MySQL 8.4
   Padrão: DevSecOps / Observabilidade / Compliance Fiscal
   Data de criação: 2025-10-10
   Última revisão: 2025-11-05
   Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
   ============================================================================= */

/* -----------------------------------------------------------------------------
   1. CRIAÇÃO DA TABELA PRINCIPAL DE LOGS FISCAIS (nfe_log)
   ----------------------------------------------------------------------------- */
CREATE TABLE IF NOT EXISTS `nfe_log` (
                                         `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'Identificador único do log',
                                         `chave_nfe` VARCHAR(44) NOT NULL COMMENT 'Chave da NF-e (44 dígitos)',
    `tipo_evento` VARCHAR(100) NOT NULL COMMENT 'Tipo de evento fiscal (Autorização, Cancelamento, Inutilização, etc.)',
    `descricao` TEXT COMMENT 'Descrição detalhada do evento registrado',
    `status` VARCHAR(20) COMMENT 'Status do evento (SUCESSO, ERRO, PROCESSANDO)',
    `xml_envio` LONGTEXT COMMENT 'XML completo enviado à SEFAZ (enviNFe)',
    `xml_retorno` LONGTEXT COMMENT 'XML de resposta da SEFAZ (retEnviNFe)',
    `cnpj_emitente` VARCHAR(20) COMMENT 'CNPJ do emitente responsável pelo envio',
    `usuario` VARCHAR(100) COMMENT 'Usuário ou sistema responsável pelo evento',
    `data_evento` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Data e hora do registro do evento fiscal',

    -- Índices para performance e rastreabilidade
    INDEX `idx_chave_nfe` (`chave_nfe`),
    INDEX `idx_cnpj_emitente` (`cnpj_emitente`),
    INDEX `idx_tipo_evento` (`tipo_evento`)
    )
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_unicode_ci
    COMMENT = 'Tabela de auditoria dos eventos fiscais da NF-e. Controla transmissões, retornos e falhas.';

/* -----------------------------------------------------------------------------
   2. CONSIDERAÇÕES DE SEGURANÇA E OBSERVABILIDADE
   -----------------------------------------------------------------------------
   • Campos xml_envio/xml_retorno devem conter apenas dados técnicos (sem PII).
   • Recomenda-se anonimização de dados sensíveis em ambientes de teste/homologação.
   • Todos os acessos devem ser rastreados via roles do sistema e logs de aplicação.
   • O controle de integridade deve ser garantido por transações ACID e auditoria de eventos.
   • Monitoramento contínuo via Prometheus + Loki recomendado para rastrear falhas fiscais.
   ----------------------------------------------------------------------------- */

/* =============================================================================
   FIM DA MIGRAÇÃO V001__create_nfe_log.sql
   ============================================================================= */
