Relatório Técnico – 24/10/2025
Projeto: Borurio ERP Fiscal BR
Sprint Fiscal 3.4 — Homologação Real SEFAZ-SP e Consolidação de Ambientes

Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps

🧭 Resumo Geral

O dia 24/10/2025 marcou a consolidação técnica dos ambientes DEV e HOM, com foco total na homologação real com a SEFAZ-SP, um marco essencial para garantir a estabilidade e conformidade fiscal antes da virada para o ambiente de produção.

Foram validados todos os componentes da infraestrutura (MySQL, Redis, MinIO, Mailpit e Web), instalada a cadeia completa de certificados ICP-Brasil e SERPRO, e estabelecida comunicação segura e autenticada (TLS 1.2) com o endpoint oficial de homologação da SEFAZ-SP.

O ambiente HOM agora encontra-se 100% funcional e pronto para execução dos testes de envio real de NF-e.
A fase de homologação seguirá até o fim de outubro, com previsão de migração para produção em novembro, atendendo ao cronograma final de entrega do projeto.

⚙️ Atividades Técnicas Realizadas
1. Carga e Validação da Tabela NCM 2025

Importação concluída com 1.291 registros oficiais da tabela Mercosul 2025.

Estrutura validada no banco borurio_fiscal_dev e replicada para borurio_fiscal_hom.

Testes de consistência realizados (valores únicos, vigência e integridade de códigos).

Resultado: base tributária nacional atualizada e integrada ao módulo fiscal.

2. Consolidação do Ambiente de Desenvolvimento (DEV)

Containers revisados e estáveis via docker-compose.dev.yml.

Healthcheck validado em /actuator/health.

Swagger operacional em http://localhost:8080/swagger-ui/index.html.

Perfil ativo: dev.

Stack totalmente integrada entre os módulos:

core | app | fiscal | web


Testes de comunicação com MySQL e Redis concluídos com sucesso.

3. Estruturação e Estabilização do Ambiente de Homologação (HOM)

Criação do ambiente dedicado via docker-compose.hom.yml.

Containers operacionais e isolados por rede borurio-net-hom.

Perfis e variáveis validadas no container:

SPRING_PROFILES_ACTIVE=hom
SPRING_DATASOURCE_URL=jdbc:mysql://borurio-mysql-hom:3306/borurio_fiscal_hom
SPRING_REDIS_HOST=borurio-redis-hom


Log de inicialização confirmando:

✅ SISTEMA ERP FISCAL BORURIO BRASIL INICIADO
🌐 Perfil ativo: hom
📦 Módulos carregados: core | app | fiscal | web
🧩 Padrão DevSecOps: segurança • automação • observabilidade

4. Cadeia de Certificados ICP-Brasil e SERPRO

Implementação completa da cadeia de confiança exigida pela SEFAZ-SP.

Certificados válidos e instalados:

ICP-Brasilv10.crt
Autoridade_Certificadora_Serpro_v4.crt
Autoridade_Certificadora_Serpro_SSLv1.crt
certificado-hom.pfx


Importação e verificação no container:

docker exec -u 0 -it borurio-web-hom update-ca-certificates
docker exec -it borurio-web-hom keytool -list -cacerts -storepass changeit


Resultado da verificação TLS:

SSL certificate verify ok
SSL connection using TLSv1.2 / ECDHE-RSA-AES256-GCM-SHA384


Endpoint SEFAZ validado:
https://homologacao.nfe.fazenda.sp.gov.br/ws/NFeAutorizacao4.asmx
→ Conexão segura, com handshake e CA reconhecida pelo container (ICP-Brasil v10).

5. Infraestrutura Docker e Observabilidade

Dockerfile raiz revisado (multi-stage, user non-root).

Logs padronizados e persistentes em /var/log/borurio.

Configurações de timezone, codificação UTF-8 e variáveis seguras (TZ, DB_PASS, REDIS_HOST).

Healthcheck dos serviços confirmados:

{
"status": "UP",
"components": {
"db": {"status": "UP"},
"redis": {"status": "UP"},
"ping": {"status": "UP"},
"diskSpace": {"status": "UP"}
}
}

📂 Estrutura Atualizada do Projeto
borurio-web/
├── certificados/
│   ├── ICP-Brasilv10.crt
│   ├── Autoridade_Certificadora_Serpro_v4.crt
│   ├── Autoridade_Certificadora_Serpro_SSLv1.crt
│   └── certificado-hom.pfx
├── docker/
│   ├── docker-compose.dev.yml
│   └── docker-compose.hom.yml
└── docs/
└── report/
└── Relatorio_Tecnico_24-10-2025.md

✅ Status Final do Dia
Componente	Status	Observação
Ambiente DEV	✅ Estável	Tabela NCM 2025 importada e validada
Ambiente HOM (SEFAZ-SP)	✅ Operacional	TLS e handshake homologados com sucesso
Módulo Fiscal (NF-e 4.00)	✅ Pronto	Schema consolidado e integração ativa
Infraestrutura Docker	✅ Validada	Containers íntegros e monitorados
Certificados ICP-Brasil	✅ OK	Cadeia completa e reconhecida
Swagger / API REST	✅ Acessível	Endpoints ativos e testados
🚀 Próximos Passos

Semana de 27 a 31 de outubro (fase final de homologação):

Testar o envio real de NF-e via endpoint /api/fiscal/nfe/enviar.

Validar respostas SEFAZ (103, 104, 100) e protocolo XML retEnviNFe.

Revisar schemas XSD e logs de assinatura digital.

Documentar integração real e ajustar application-prd.yml.

Preparar migração e implantação do ambiente de produção (PRD).

🏁 Cronograma Final
Etapa	Período	Status
Estabilização DEV	Concluído (22/10)	✅
Homologação Real SEFAZ-SP	23 a 31/10	🔄 Em andamento
Implantação em Produção	Início de novembro	🚀 Previsto
Entrega Final do Projeto	Novembro/2025	📦 Meta confirmada
💬 Conclusão

O Borurio ERP Fiscal BR atingiu maturidade técnica nos ambientes DEV e HOM, com infraestrutura segura, TLS validado e base fiscal atualizada.
A fase final de homologação real com a SEFAZ-SP será executada a partir de segunda-feira (27/10), validando a transmissão oficial de notas fiscais eletrônicas antes da migração definitiva para produção em novembro.

O sistema encontra-se estável, confiável e tecnicamente pronto para o ciclo final de entrega.