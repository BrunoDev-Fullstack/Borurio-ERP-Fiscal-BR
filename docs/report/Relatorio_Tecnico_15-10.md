# Relatório Técnico – 15/10/2025
## Sprint Fiscal 2.6 — Docker Compose, CertificadoServiceImpl e QRCode XML

### 🧭 Resumo Técnico
Aprimorado o ambiente Docker Compose e finalizado o serviço de manipulação de certificados.  
Foram realizados testes com QR Code e assinatura XML.

### ⚙️ Atividades Realizadas
- Implementação da classe `CertificadoServiceImpl`.
- Adição do container `mailpit` no `docker-compose.dev.yml`.
- Configuração dos serviços Redis e MinIO.
- Teste de validação de QR Code da NF-e (regex e padrão SEFAZ).
- Build completo de todos os módulos (core, app, fiscal, web) — **BUILD SUCCESS**.

### ✅ Resultados
- Sistema 100% containerizado e funcional.
- Certificados A1 carregados corretamente.
- Pipeline Maven compilando e testando todos os módulos.
- Tag criada: `v2.6.0` – ambiente DEV estável.

### 🔜 Próximos Passos
- Revisar validador XML (`XsdValidator`).
- Preparar ajuste dos XSDs duplicados.
- Iniciar Sprint Fiscal 3.0 focando na consolidação do schema.
