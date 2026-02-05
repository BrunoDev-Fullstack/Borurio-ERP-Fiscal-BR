CRONOGRAMA & CHECKLIST — ENTREGA BORURIO ERP FISCAL BR EM PRODUÇÃO

Marco atual:
v3.3.0-hom — Homologação NF-e 4.00 SEFAZ-SP concluída

VISÃO GERAL DE TEMPO
Fase	Descrição	Tempo estimado
Fase 1	Testes fiscais em homologação	3 a 5 dias
Fase 2	Hardening técnico + ajustes fiscais	2 a 3 dias
Fase 3	Preparação produção (PRD)	2 dias
Fase 4	Teste em produção assistida	1 a 2 dias
TOTAL ESTIMADO		8 a 12 dias úteis
🔹 FASE 1 — TESTES FISCAIS EM HOMOLOGAÇÃO (SEFAZ-SP)

Objetivo: garantir que o ERP emite NF-e válida e autorizada.

1.1 Preparação de Dados Fiscais

 Definir CNPJ emitente homologado

 Definir IE válida (homologação)

 Configurar CNAE, CRT, regime tributário

 Conferir município, UF, cMunFG

 Conferir série e numeração inicial

⏱️ Tempo: 0,5 dia

1.2 Geração e Assinatura da NF-e

 Gerar XML NF-e 4.00 completo

 Validar contra XSD oficial

 Assinar XML com certificado A1

 Validar namespace, Id, digestValue e Signature

⏱️ Tempo: 1 dia

1.3 Envio para SEFAZ-SP (HOM)

 Login JWT (/auth/login)

 Enviar NF-e (/nfe/enviar)

 Analisar retorno SOAP

 Tratar rejeições iniciais

 Persistir protocolo (protNFe)

⏱️ Tempo: 1 dia

1.4 Fluxos Fiscais Obrigatórios

 Consulta de protocolo

 Cancelamento NF-e

 Inutilização de numeração

 Testar eventos SEFAZ

⏱️ Tempo: 1 a 2 dias

✔️ CRITÉRIO DE SAÍDA DA FASE 1

Pelo menos 1 NF-e AUTORIZADA em homologação

Eventos fiscais funcionando

Logs e rastreabilidade ok

🔹 FASE 2 — HARDENING TÉCNICO E FISCAL

Objetivo: deixar o sistema seguro e estável para PRD.

2.1 Segurança

 Revisar expiração JWT

 Garantir que /actuator/** esteja restrito em PRD

 Confirmar que apenas /auth/login é público

 Garantir secrets apenas via env

⏱️ Tempo: 0,5 dia

2.2 Fiscal

 Validar schemas em runtime

 Validar rejeições mais comuns (cStat)

 Ajustar mensagens de erro para usuário

 Garantir não persistência de XML sensível sem criptografia (se aplicável)

⏱️ Tempo: 1 dia

2.3 Observabilidade

 Revisar logback-prd

 Garantir logs fiscais completos

 Ajustar nível de log para produção

 Testar falhas simuladas

⏱️ Tempo: 0,5 dia

✔️ CRITÉRIO DE SAÍDA DA FASE 2

Sistema fiscal robusto

Segurança adequada para produção

Logs auditáveis

🔹 FASE 3 — PREPARAÇÃO DE PRODUÇÃO (PRD)

Objetivo: espelhar PRD sem risco.

3.1 Infraestrutura

 Criar application-prd.yml

 Criar docker-compose.prd.yml

 Configurar banco PRD

 Configurar Redis PRD

 Configurar MinIO PRD

⏱️ Tempo: 1 dia

3.2 Certificados

 Certificado A1 produção

 Cadeia ICP-Brasil validada

 Truststore SEFAZ validado

 Teste mTLS em PRD

⏱️ Tempo: 0,5 dia

3.3 Build e Release

 Build limpo Maven

 Tag PRD criada (ex: v3.3.0-prd)

 Imagem Docker PRD

 Deploy controlado

⏱️ Tempo: 0,5 dia

✔️ CRITÉRIO DE SAÍDA DA FASE 3

Ambiente PRD no ar

API respondendo

Segurança ativa

🔹 FASE 4 — TESTE EM PRODUÇÃO ASSISTIDA

Objetivo: primeira NF-e real sem impacto.

4.1 Smoke Tests

 Healthcheck

 Login

 Ping fiscal

 Status SEFAZ

⏱️ Tempo: 0,25 dia

4.2 Primeira NF-e Produção

 Emitir NF-e real (baixo valor)

 Validar autorização

 Armazenar protocolo

 Conferir no portal SEFAZ

⏱️ Tempo: 0,5 a 1 dia

✔️ CRITÉRIO FINAL

NF-e AUTORIZADA EM PRODUÇÃO

Sistema operacional

Projeto entregue

📦 ENTREGA FINAL DO PROJETO
Artefatos finais:

Código versionado

Tags hom + prd

Docker PRD

Documentação técnica

Evidência de NF-e autorizada

⏱️ TEMPO TOTAL ESTIMADO

Mínimo: 8 dias úteis
Realista: 10 dias úteis
Com folga: 12 dias úteis