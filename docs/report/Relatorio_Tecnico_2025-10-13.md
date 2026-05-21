# Relatório Técnico – 13/10/2025
## Sprint Fiscal 3.0 — Início do ambiente DEV e Flyway

### 🧭 Resumo Técnico
Iniciada a Sprint Fiscal 3.0 com foco na padronização do ambiente de desenvolvimento e integração de migrations automáticas via Flyway.  
Foi estabelecido o pipeline inicial para validação de schemas e estrutura base do banco de dados.

### ⚙️ Atividades Realizadas
- Criação dos bancos `borurio_fiscal_dev`, `borurio_core_dev`, `borurio_app_dev`.
- Adição do `flyway.conf` no módulo `borurio-fiscal`.
- Criação dos scripts `V001__create_nfe_log.sql` e `V002__insert_mock_data.sql`.
- Configuração inicial de Docker Compose com MySQL e Redis.
- Teste de conexão do módulo `borurio-fiscal` ao container MySQL (porta 3307).

### ✅ Resultados
- Migrações executadas com sucesso.
- Schema `nfe_log` criado automaticamente.
- Ambiente DEV operacional via Docker Compose.

### 🔜 Próximos Passos
- Adicionar migrations 003 e 004 (campos de status e mock data).
- Iniciar integração do validador de XML com XSD SEFAZ.
- Testes unitários iniciais com `XsdValidatorTest`.
