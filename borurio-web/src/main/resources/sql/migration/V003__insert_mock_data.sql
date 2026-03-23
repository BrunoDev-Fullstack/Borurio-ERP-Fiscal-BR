-- =====================================================================
-- Versão: V002__insert_mock_data.sql
-- Módulo: Borurio Fiscal (NF-e / SEFAZ-SP / Auditoria)
-- Finalidade: Inserção de dados simulados para validação do módulo fiscal e testes de integração SEFAZ (Mock)
-- Banco de Dados: MySQL 8.4
-- Padrão: DevSecOps / Observabilidade / Mock Data Controlado
-- Data de criação: 2025-10-13
-- Autor: Bruno Ribeiro — Desenvolvedor Java / DevSecOps
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Inserção de registros simulados na tabela nfe_log
-- ---------------------------------------------------------------------
-- Observações:
-- • Os dados abaixo são de uso exclusivo em ambiente de desenvolvimento/homologação.
-- • Não representam chaves fiscais reais.
-- • Todos os campos XML permanecem nulos propositalmente para simulação de auditoria parcial.
-- • Cada linha é auditável e mantém rastreabilidade por CNPJ e usuário.

INSERT INTO `nfe_log`
(`chave_nfe`, `tipo_evento`, `descricao`, `status`, `xml_envio`, `xml_retorno`, `cnpj_emitente`, `usuario`)
VALUES
    ('35250112345678000123550010000000011000000010',
     'Autorização',
     'NF-e autorizada com sucesso pela SEFAZ-SP (mock)',
     'SUCESSO',
     NULL,
     NULL,
     '12345678000123',
     'sistema'),

    ('35250112345678000123550010000000022000000020',
     'Cancelamento',
     'NF-e cancelada por duplicidade (mock)',
     'SUCESSO',
     NULL,
     NULL,
     '12345678000123',
     'admin'),

    ('35250112345678000123550010000000033000000030',
     'Carta de Correção',
     'Correção de CFOP aplicada (mock)',
     'SUCESSO',
     NULL,
     NULL,
     '12345678000123',
     'fiscal');

-- ---------------------------------------------------------------------
-- 2. Auditoria de Mock Data
-- ---------------------------------------------------------------------
-- • Cada inserção possui status "SUCESSO" para garantir compatibilidade
--   com os testes de integração e consultas simuladas do NfeLogServiceTest.
-- • Esses registros são utilizados para validação dos endpoints /nfe/status
--   e /nfe/enviar durante os testes do Mock SEFAZ-SP.
-- • Alterações posteriores devem respeitar o versionamento Flyway.

-- =====================================================================
-- Fim da migração V002__insert_mock_data.sql
-- =====================================================================
