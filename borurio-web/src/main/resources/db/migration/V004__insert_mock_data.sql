-- =============================================================================
-- Versão: V003__insert_mock_data.sql
-- Módulo: Borurio Fiscal (NF-e / SEFAZ-SP / Auditoria)
-- Finalidade: Inserção de dados simulados para validação do módulo fiscal
-- Banco de Dados: MySQL 8.4
-- Ambiente: DEV / HOM
-- Padrão: DevSecOps / Observabilidade / Mock Data Controlado
-- Data de criação: 2025-10-13
-- Autor: Bruno Ribeiro — Desenvolvedor Java / DevSecOps
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Inserção de registros simulados na tabela nfe_log
-- -----------------------------------------------------------------------------
-- Observações:
-- • Dados EXCLUSIVOS para ambiente de desenvolvimento/homologação
-- • Não representam documentos fiscais reais
-- • Campos XML permanecem NULL propositalmente
-- • Compatível com schema até a V002 (SEM coluna status)

INSERT INTO `nfe_log`
(
    `chave_nfe`,
    `tipo_evento`,
    `descricao`,
    `xml_envio`,
    `xml_retorno`,
    `cnpj_emitente`,
    `usuario`
)
VALUES
    (
        '35250112345678000123550010000000011000000010',
        'Autorizacao',
        'NF-e autorizada com sucesso pela SEFAZ-SP (mock)',
        NULL,
        NULL,
        '12345678000123',
        'sistema'
    ),
    (
        '35250112345678000123550010000000022000000020',
        'Cancelamento',
        'NF-e cancelada por duplicidade (mock)',
        NULL,
        NULL,
        '12345678000123',
        'admin'
    ),
    (
        '35250112345678000123550010000000033000000030',
        'CartaCorrecao',
        'Carta de correção aplicada (mock)',
        NULL,
        NULL,
        '12345678000123',
        'fiscal'
    );

-- -----------------------------------------------------------------------------
-- Auditoria
-- -----------------------------------------------------------------------------
-- • O campo STATUS será introduzido corretamente na migration V004
-- • Esta migration respeita o versionamento Flyway
-- • Qualquer alteração deve gerar nova versão (V00X__)

-- =============================================================================
-- Fim da migration V003__insert_mock_data.sql
-- =============================================================================
