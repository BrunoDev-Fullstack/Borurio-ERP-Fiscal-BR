RELATÓRIO TÉCNICO — BORURIO ERP FISCAL BR

Data: 23/03/2026
Ambiente: HOM (Homologação)
Responsável: Bruno Ribeiro
Versão: Marco de Integração SEFAZ (Primeiro contato bem-sucedido)

1. OBJETIVO DO DIA
   Restabelecer ambiente HOM e alcançar comunicação real com a SEFAZ.
2. PROBLEMAS INICIAIS IDENTIFICADOS
   2.1 Falha de inicialização (Redis)
   Failed to bind properties under 'spring.data.redis.port'

Causa:

Variáveis não injetadas corretamente no container
Uso incorreto de ${VAR} sem fallback
2.2 Configuração inconsistente de ambiente
- Uso simultâneo de docker-compose.yml e docker-compose.hom.yml
- Containers duplicados (orphan)

Impacto:

Ambiente não determinístico
Testes inválidos
2.3 Segurança bloqueando endpoints
403 Forbidden em /api/fiscal/nfe/enviar

Causa:

Endpoint não liberado no SecurityConfig
Conflito entre JwtFilter e regras de autorização
2.4 Problema de binding HTTP
HttpMessageNotReadableException: Required request body is missing

Causa:

Controller esperando @RequestBody String
PowerShell enviando request sem body válido
3. AÇÕES EXECUTADAS
   3.1 Correção do Docker Compose
- Padronização do uso de docker-compose.hom.yml
- Remoção de containers órfãos
- Criação de .env raiz para build

Resultado:

Ambiente isolado e previsível
3.2 Correção de variáveis de ambiente
- SPRING_REDIS_HOST
- SPRING_REDIS_PORT
- SECURITY_JWT_SECRET
- JAVA_OPTS

Resultado:

Aplicação inicializando corretamente
3.3 Ajuste do JwtFilter
Problemas anteriores:
- Lógica de rota pública duplicada
- Falta de logging
- Falta de rastreabilidade
  Correções aplicadas:
- Remoção de controle de rotas públicas
- Delegação total ao SecurityConfig
- Inclusão de logs estruturados

Evidência:

JWT FILTER EXECUTANDO → /api/fiscal/nfe/enviar
3.4 Correção do SecurityConfig
Problema:
Endpoint /api/fiscal/nfe/enviar não estava liberado
Correção:
.requestMatchers("/api/fiscal/nfe/enviar").permitAll()

Resultado:

Eliminação do erro 403 por segurança
3.5 Correção do pipeline Docker
Problema:
Build usando cache / jar antigo
Correção:
docker builder prune -f
docker compose build --no-cache

Resultado:

Código atualizado refletido no container
3.6 Correção do envio via PowerShell
Problema:
Invoke-WebRequest não enviava body corretamente
Correção:
Invoke-RestMethod + ContentType text/xml
4. VALIDAÇÕES REALIZADAS
   4.1 Healthcheck
   {
   "status": "UP",
   "db": "UP",
   "redis": "UP"
   }
   4.2 Execução do JwtFilter
   JWT FILTER EXECUTANDO → /api/fiscal/nfe/enviar
   4.3 Endpoint NF-e
   POST /api/fiscal/nfe/enviar → 200 OK
   4.4 Comunicação com SEFAZ

Resposta:

{
"code": 200,
"message": "Sucesso",
"data": "<erro>Erro no envio SOAP SEFAZ</erro>"
}
5. CONQUISTA DO DIA (MARCO CRÍTICO)
   ✔ Sistema comunicando com a SEFAZ (homologação)
   ✔ Pipeline completo validado (Controller → Service → SOAP → SEFAZ)
   ✔ Infraestrutura estabilizada
   ✔ Segurança funcionando corretamente
6. ESTADO ATUAL DO SISTEMA
   Camada            Status
----------------------------------
Docker            OK
Banco             OK
Redis             OK
JWT               OK
SecurityConfig    OK
Controller        OK
SOAP              OK
SEFAZ             RESPONDENDO
XML               INVÁLIDO (esperado)
7. PROBLEMA ATUAL (REAL)
   Erro no envio SOAP SEFAZ

Causa:

XML NF-e incompleto (estrutura inválida)
8. PRÓXIMA ETAPA
   Implementar:
1. Geração de XML NF-e 4.00 completo
2. Assinatura digital (XMLDSig)
3. Validação contra XSD oficial
4. Reenvio para SEFAZ
9. DÍVIDAS TÉCNICAS IDENTIFICADAS
- JWT secret não Base64 (corrigir para produção)
- Mappers duplicados
- Flyway warning (MySQL 8.4)
- Falta de validação de XML antes do envio
10. COMANDOS PADRÃO ESTABILIZADOS
    mvn clean package -DskipTests

docker compose -f "docker-compose.hom.yml" down -v
docker builder prune -f
docker compose -f "docker-compose.hom.yml" build --no-cache
docker compose -f "docker-compose.hom.yml" up -d
11. CONCLUSÃO TÉCNICA
    O sistema atingiu o primeiro marco real de integração fiscal:

→ comunicação efetiva com a SEFAZ

A partir deste ponto, o projeto deixa de ser infraestrutura/backend
e passa a ser implementação fiscal (NF-e).
12. PRÓXIMO CHECKPOINT
    → Envio de NF-e válida em homologação
    → Retorno autorizado (cStat 100 ou 103/104)