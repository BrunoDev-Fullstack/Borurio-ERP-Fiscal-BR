-- =====================================================================
-- Versão: V002__insert_mock_data.sql
-- Finalidade: Inserção de dados simulados para validação do módulo fiscal
-- Autor: Bruno Ribeiro (Desenvolvedor Java / DevSecOps)
-- Data: 2025-10-13
-- =====================================================================

INSERT INTO nfe_log (chave_nfe, tipo_evento, descricao, usuario)
VALUES
    ('35250112345678000123550010000000011000000010', 'Autorização', 'NF-e autorizada com sucesso pela SEFAZ-SP', 'sistema'),
    ('35250112345678000123550010000000022000000020', 'Cancelamento', 'NF-e cancelada por duplicidade', 'admin'),
    ('35250112345678000123550010000000033000000030', 'Carta de Correção', 'Correção de CFOP aplicada', 'fiscal');

-- =====================================================================
-- Fim da migração V002__insert_mock_data.sql
-- =====================================================================
