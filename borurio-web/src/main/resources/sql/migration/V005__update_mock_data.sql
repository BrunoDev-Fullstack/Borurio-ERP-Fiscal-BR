-- =====================================================================
-- Migration: V004__update_mock_data.sql
-- Módulo: borurio-fiscal
-- Função: Atualização dos registros simulados da SEFAZ-SP (Mock)
-- Autor: Bruno Ribeiro da Silva
-- Data: 2025-10-15
-- Versão: 1.0.0
-- Compatível com: MySQL 8.4 / Flyway 10.18
-- =====================================================================
-- Observação:
-- Esta migration atualiza o conteúdo da tabela nfe_log
-- com XMLs simulados para o ambiente de homologação SEFAZ-SP.
-- =====================================================================

DELETE FROM nfe_log WHERE tipo_evento IN ('AUTORIZACAO', 'CANCELAMENTO', 'CARTA_CORRECAO');

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
      ('35251012345678000123550010000000011000000010',
       'AUTORIZACAO',
       'Envio de NF-e autorizada com sucesso pela SEFAZ-SP (mock)',
       'AUTORIZADA',
       LOAD_FILE('C:/Projetos/borurio-erp-br/borurio-fiscal/src/test/resources/xml/mockEnviNFe.xml'),
       LOAD_FILE('C:/Projetos/borurio-erp-br/borurio-fiscal/src/test/resources/xml/mockRetEnviNFe.xml'),
       '12345678000123',
       'system-dev',
       NOW()),
      ('35251012345678000123550010000000022000000020',
       'CANCELAMENTO',
       'Cancelamento de NF-e processado pela SEFAZ-SP (mock)',
       'CANCELADA',
       LOAD_FILE('C:/Projetos/borurio-erp-br/borurio-fiscal/src/test/resources/xml/mockEnviNFe.xml'),
       LOAD_FILE('C:/Projetos/borurio-erp-br/borurio-fiscal/src/test/resources/xml/mockRetEnviNFe.xml'),
       '12345678000123',
       'system-dev',
       NOW()),
      ('35251012345678000123550010000000033000000030',
       'CARTA_CORRECAO',
       'Carta de correção eletrônica registrada com sucesso (mock)',
       'CORRIGIDA',
       LOAD_FILE('C:/Projetos/borurio-erp-br/borurio-fiscal/src/test/resources/xml/mockEnviNFe.xml'),
       LOAD_FILE('C:/Projetos/borurio-erp-br/borurio-fiscal/src/test/resources/xml/mockRetEnviNFe.xml'),
       '12345678000123',
       'system-dev',
       NOW());

ALTER TABLE nfe_log
    ADD INDEX idx_nfe_log_tipo_evento (tipo_evento),
  ADD INDEX idx_nfe_log_data_evento (data_evento);

SELECT 'V004__update_mock_data.sql aplicada com sucesso (Mock SEFAZ-SP)' AS status;
