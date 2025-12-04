/* =============================================================================
   Versão: V002__insert_mock_data.sql
   Módulo : Borurio Fiscal (NF-e / SEFAZ-SP / Auditoria)
   Finalidade: Inserção de registros simulados para testes do módulo fiscal.
   Ambiente: Desenvolvimento / Homologação (NUNCA usar em produção)
   Banco  : MySQL 8.4
   Padrão : DevSecOps • Observabilidade • Mock Controlado
   Autor  : Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
   Revisão: 2025-12-02
   ============================================================================= */

/* -----------------------------------------------------------------------------
   1. INSERÇÃO DE MOCK DATA NA TABELA nfe_log
   -----------------------------------------------------------------------------
   Observações:
   - Chaves fiscais totalmente fictícias (não representam documentos reais).
   - XMLs nulos propositalmente — simulam auditoria parcial.
   - Cada registro possui rastreabilidade por CNPJ e usuário.
   - Essencial para testes dos endpoints:
       • GET  /nfe/status
       • POST /nfe/enviar (modo simulado)
       • GET  /api/fiscal/nfe/logs
   ----------------------------------------------------------------------------- */

INSERT INTO `nfe_log`
(`chave_nfe`, `tipo_evento`, `descricao`, `status`, `xml_envio`, `xml_retorno`, `cnpj_emitente`, `usuario`)
VALUES
    -- Mock #1 — Autorização bem-sucedida
    ('35250112345678000123550010000000011000000010',
     'Autorização',
     'NF-e autorizada com sucesso pela SEFAZ-SP (mock).',
     'SUCESSO',
     NULL,
     NULL,
     '12345678000123',
     'sistema'),

    -- Mock #2 — Cancelamento
    ('35250112345678000123550010000000022000000020',
     'Cancelamento',
     'NF-e cancelada por duplicidade (mock).',
     'SUCESSO',
     NULL,
     NULL,
     '12345678000123',
     'admin'),

    -- Mock #3 — Carta de Correção
    ('35250112345678000123550010000000033000000030',
     'Carta de Correção',
     'Correção de CFOP aplicada com sucesso (mock).',
     'SUCESSO',
     NULL,
     NULL,
     '12345678000123',
     'fiscal');

/* -----------------------------------------------------------------------------
   2. Notas de Auditoria e Observabilidade
   -----------------------------------------------------------------------------
   - Mock controlado e padronizado para garantir repetibilidade dos testes.
   - Usado pelos testes automatizados do módulo NF-e.
   - Mantido simples (sem XML) para não poluir o banco com documentos grandes.
   - Compatível com queries de auditoria e dashboards internos.

   Política:
   • Em produção, apenas logs reais são gerados — nunca inserir dados mock.
   • Alterações futuras devem seguir versionamento Flyway (V003, V004, ...).
   ----------------------------------------------------------------------------- */

/* =============================================================================
   FIM DA MIGRAÇÃO V002
   ============================================================================= */
