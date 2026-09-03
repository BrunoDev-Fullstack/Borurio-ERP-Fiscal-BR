package br.com.borurio.fiscal.entity;

import java.time.LocalDateTime;

/**
 * Ciclo operacional de um nNF — Gate 1 da maquina de estados fiscal de numeracao. Distinta de
 * NfeDocumento (documento fiscal consolidado, so existe apos resposta real da SEFAZ, usado por
 * DANFE/situacao). Uma linha por (cnpjEmitente, modelo, serie, numeroNfe), atualizada in-place a
 * cada nova tentativa do mesmo pedido — o historico bruto por tentativa continua em NfeLog.
 */
public class NfeEmissao {

    /** Nomes de estado usados em nfe_emissao.estado — String livre, mesmo padrao de Pedido.status. */
    public static final class Estados {
        public static final String RESERVADO = "RESERVADO";
        public static final String TRANSMITIDO = "TRANSMITIDO";
        public static final String AUTORIZADO = "AUTORIZADO";
        public static final String AGUARDANDO_CORRECAO = "AGUARDANDO_CORRECAO";
        public static final String DENEGADO = "DENEGADO";
        public static final String PENDENTE_CONFIRMACAO = "PENDENTE_CONFIRMACAO";
        /**
         * Gate 3 (reconciliacao, 10-08-2026): a reconciliacao provou que o nNF esta definitivamente
         * ocupado/inutilizavel por identidade fiscal alheia (NF-e cancelada/denegada/inutilizada na
         * base da SEFAZ, ou chave de acesso divergente confirmada) — mas a NF-e DESTE pedido nunca
         * foi autorizada. Distinto de DENEGADO: DENEGADO significa "a SEFAZ recusou esta tentativa
         * de transmissao"; NUMERO_OCUPADO significa "esta tentativa nunca teve chance — o numero ja
         * pertencia a outro documento". Terminal: consome o numero (nunca reutilizado) e libera o
         * gate, mas o Pedido correspondente nunca vira AUTORIZADO.
         */
        public static final String NUMERO_OCUPADO = "NUMERO_OCUPADO";

        /**
         * Gate de cancelamento (12-08-2026): projecao do estado fiscal ATUAL apos um evento de
         * cancelamento homologado (nfe_evento.estado=REGISTRADO) -- nunca liberado por
         * {@link #aplicarNovoEstado} do NfeEmissaoService (esse metodo so trata transicoes do
         * ciclo do nNF/Gate 1-3, que ja terminou em AUTORIZADO muito antes do cancelamento
         * existir). cstat/xmotivo/nprot da AUTORIZACAO original NUNCA sao sobrescritos por esta
         * transicao -- a evidencia do cancelamento em si fica em nfe_evento, nao aqui. Ver
         * NfeEmissaoMapper.marcarCancelado.
         */
        public static final String CANCELADO = "CANCELADO";

        /**
         * Recovery administrativo (02-09-2026): encerra um ciclo em AGUARDANDO_CORRECAO cujo dado
         * de origem NAO pode mais ser corrigido (ex.: xProd rejeitado por schema num pedido sem
         * endpoint de edicao de item). Semantica: "este ciclo terminou operacionalmente, mas esta
         * NF-e nunca existiu fiscalmente". Modelo "gap" (revisao de 02-09-2026 pos-incidente):
         *   - libera o gate da serie (nfe_sequencia.emissao_ativa_id -> NULL);
         *   - AVANCA nfe_sequencia.ultimo_numero ate o numero_nfe deste ciclo -- nunca alem, nunca
         *     regredindo. A linha de nfe_emissao NAO e apagada e ocupa permanentemente o slot
         *     (cnpj_emitente, modelo, serie, numero_nfe) via a UNIQUE uk_nfe_emissao_numero, entao
         *     o nNF NAO volta a ser alocavel -- o proximo ciclo pega numero_nfe + 1. Esse nNF fica
         *     como "numero nao autorizado no historico" (a inutilizacao formal junto a SEFAZ, se
         *     desejada, e um passo administrativo separado);
         *   - preserva cstat/xmotivo/nprot da rejeicao; preenche resolvido_em.
         * Diferente dos terminais de {@link #isTerminal}: aqueles consomem o numero porque a SEFAZ
         * deu destino fiscal ao nNF; aqui o numero e apenas "queimado" para nao colidir, sem
         * autorizacao nem denegacao.
         * So alcancavel via {@code NfeEmissaoService.abandonarCiclo} (endpoint /api/admin), nunca
         * por resolverCiclo/reconciliacao. Guard: origem obrigatoriamente AGUARDANDO_CORRECAO e
         * nprot nulo (um ciclo com protocolo teve destino real na SEFAZ, nunca e abandonavel). A
         * chamada idempotente (ciclo ja ABANDONADO) ainda repara o contador se ultimo_numero
         * ficou abaixo de numero_nfe -- nunca e um no-op cego.
         */
        public static final String ABANDONADO = "ABANDONADO";

        /**
         * Recovery administrativo (02-09-2026): a tentativa de transmissao foi COMPROVADAMENTE
         * rejeitada no transporte/gateway ANTES de chegar ao autorizador da SEFAZ (ex.: HTTP 403
         * do proxy por certificado cliente invalido, resposta HTML em vez de SOAP) -- nenhuma NF-e
         * existe fiscalmente: sem retEnviNFe, sem recibo, sem protNFe, sem nProt, sem dhRecbto.
         * Distinto de ABANDONADO (aquele e AGUARDANDO_CORRECAO / cStat 225 -- o lote FOI recebido
         * e rejeitado por schema); aqui o lote NUNCA chegou. Efeito na numeracao identico ao
         * ABANDONADO (modelo "gap"): encerra o ciclo, libera o gate, AVANCA
         * nfe_sequencia.ultimo_numero ate o numero_nfe deste ciclo (nunca alem, nunca regredindo)
         * porque a linha de nfe_emissao ocupa o slot uk_nfe_emissao_numero para sempre, preenche
         * resolvido_em, preserva cstat/xmotivo/chave como evidencia. Adicionalmente devolve o
         * Pedido de origem a ERRO (emissivel), limpa a chave espuria e desfaz a reserva de estoque
         * se o emit a fez. So alcancavel via
         * {@code NfeEmissaoService.marcarTransporteNaoEntregue} (endpoint /api/admin) e apenas
         * quando NAO ha nprot, nem tentativas_consulta, nem evidencia de processamento em
         * nfe_documento (n_prot/dh_recbto/xml_protocolo). A chamada idempotente ainda repara o
         * contador se ultimo_numero ficou abaixo de numero_nfe.
         */
        public static final String TRANSPORTE_NAO_ENTREGUE = "TRANSPORTE_NAO_ENTREGUE";

        /**
         * Estados terminais "fiscais" -- a SEFAZ deu destino ao nNF: AUTORIZADO/DENEGADO (recusou
         * ou autorizou esta tentativa), NUMERO_OCUPADO (o numero pertencia a outro documento).
         * ABANDONADO e TRANSPORTE_NAO_ENTREGUE NAO entram aqui: encerram o ciclo e tambem fazem
         * ultimo_numero alcancar o nNF (modelo "gap", para nao colidir na uk_nfe_emissao_numero),
         * mas por decisao administrativa de recovery, nunca por resposta da SEFAZ -- essa distincao
         * e o motivo de {@code abandonarCiclo}/{@code marcarTransporteNaoEntregue} serem operacoes
         * separadas, nunca um {@code novoEstado} de {@code aplicarNovoEstado}.
         */
        public static boolean isTerminal(String estado) {
            return AUTORIZADO.equals(estado) || DENEGADO.equals(estado) || NUMERO_OCUPADO.equals(estado);
        }

        /**
         * Estados em que o ciclo esta encerrado e o gate da serie NAO deve mais apontar para ele:
         * os terminais de {@link #isTerminal} mais os recovery administrativos ABANDONADO e
         * TRANSPORTE_NAO_ENTREGUE. Todos avancam nfe_sequencia.ultimo_numero ate o nNF do ciclo
         * (os terminais como consumo fiscal; os recovery pelo modelo "gap"). Usado por quem
         * precisa saber "este ciclo ainda esta em voo?".
         */
        public static boolean encerraCiclo(String estado) {
            return isTerminal(estado)
                    || ABANDONADO.equals(estado)
                    || TRANSPORTE_NAO_ENTREGUE.equals(estado);
        }

        private Estados() {}
    }

    /**
     * Fase 1 SVC (17-08-2026, persistencia/ciclo, sem transporte) -- valores de {@link #tpEmis}.
     * Tabela MOC confirmada em banca: 1=Normal, 4=EPEC, 6=SVC-AN, 7=SVC-RS. EPEC fora do escopo
     * desta fase -- so NORMAL/SVC_AN/SVC_RS existem aqui.
     */
    public static final class TpEmis {
        public static final String NORMAL = "1";
        public static final String SVC_AN = "6";
        public static final String SVC_RS = "7";

        private TpEmis() {}
    }

    /**
     * Fase 1 SVC -- valores de {@link #autorizadorDestino}: autoridade que detem o ciclo agora.
     * Sempre derivado de {@link #tpEmis} pelo chamador (NfeContingenciaService), nunca recebido
     * independente -- elimina estruturalmente a combinacao invalida tpEmis=SVC_AN com
     * autorizadorDestino=SVC_RS (ou vice-versa). Distinto da rota por UF da Fase 0
     * (SefazRotaResolver/SefazRotasProperties), que resolve o ENDPOINT dentro do modo NORMAL --
     * duas dimensoes diferentes, nomes sem colisao semantica de proposito.
     */
    public static final class AutorizadorDestino {
        public static final String NORMAL = "NORMAL";
        public static final String SVC_AN = "SVC_AN";
        public static final String SVC_RS = "SVC_RS";

        private AutorizadorDestino() {}
    }

    private Long id;
    private Long pedidoId;
    private Long empresaId;
    private String cnpjEmitente;
    private String modelo;
    private String serie;
    private int numeroNfe;
    private String chaveNfe;
    private String estado;
    private Integer cstat;
    private String xmotivo;
    private String nprot;
    private String requestId;
    private int tentativas;
    private LocalDateTime transmitidoEm;
    private LocalDateTime resolvidoEm;
    private LocalDateTime ultimaConsultaEm;
    private int tentativasConsulta;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Fase 1 SVC (17-08-2026) -- ver TpEmis/AutorizadorDestino acima. dhCont e String (nao
    // LocalDateTime/OffsetDateTime) de proposito: ISO-8601 com offset formatado uma unica vez no
    // momento real da abertura de contingencia e persistido literalmente, mesmo padrao ja usado
    // por nfe_evento_idempotencia.dh_reg_evento_resultado (V037) -- MySQL/JDBC nunca veem um tipo
    // com fuso, entao nao ha reinterpretacao de fuso em nenhum ponto do caminho.
    private String tpEmis;
    private String autorizadorDestino;
    private Long emissaoOrigemId;
    private String dhCont;
    private String xJustContingencia;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getPedidoId() { return pedidoId; }
    public void setPedidoId(Long pedidoId) { this.pedidoId = pedidoId; }

    public Long getEmpresaId() { return empresaId; }
    public void setEmpresaId(Long empresaId) { this.empresaId = empresaId; }

    public String getCnpjEmitente() { return cnpjEmitente; }
    public void setCnpjEmitente(String cnpjEmitente) { this.cnpjEmitente = cnpjEmitente; }

    public String getModelo() { return modelo; }
    public void setModelo(String modelo) { this.modelo = modelo; }

    public String getSerie() { return serie; }
    public void setSerie(String serie) { this.serie = serie; }

    public int getNumeroNfe() { return numeroNfe; }
    public void setNumeroNfe(int numeroNfe) { this.numeroNfe = numeroNfe; }

    public String getChaveNfe() { return chaveNfe; }
    public void setChaveNfe(String chaveNfe) { this.chaveNfe = chaveNfe; }

    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }

    public Integer getCstat() { return cstat; }
    public void setCstat(Integer cstat) { this.cstat = cstat; }

    public String getXmotivo() { return xmotivo; }
    public void setXmotivo(String xmotivo) { this.xmotivo = xmotivo; }

    public String getNprot() { return nprot; }
    public void setNprot(String nprot) { this.nprot = nprot; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public int getTentativas() { return tentativas; }
    public void setTentativas(int tentativas) { this.tentativas = tentativas; }

    public LocalDateTime getTransmitidoEm() { return transmitidoEm; }
    public void setTransmitidoEm(LocalDateTime transmitidoEm) { this.transmitidoEm = transmitidoEm; }

    public LocalDateTime getResolvidoEm() { return resolvidoEm; }
    public void setResolvidoEm(LocalDateTime resolvidoEm) { this.resolvidoEm = resolvidoEm; }

    public LocalDateTime getUltimaConsultaEm() { return ultimaConsultaEm; }
    public void setUltimaConsultaEm(LocalDateTime ultimaConsultaEm) { this.ultimaConsultaEm = ultimaConsultaEm; }

    public int getTentativasConsulta() { return tentativasConsulta; }
    public void setTentativasConsulta(int tentativasConsulta) { this.tentativasConsulta = tentativasConsulta; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getTpEmis() { return tpEmis; }
    public void setTpEmis(String tpEmis) { this.tpEmis = tpEmis; }

    public String getAutorizadorDestino() { return autorizadorDestino; }
    public void setAutorizadorDestino(String autorizadorDestino) { this.autorizadorDestino = autorizadorDestino; }

    public Long getEmissaoOrigemId() { return emissaoOrigemId; }
    public void setEmissaoOrigemId(Long emissaoOrigemId) { this.emissaoOrigemId = emissaoOrigemId; }

    public String getDhCont() { return dhCont; }
    public void setDhCont(String dhCont) { this.dhCont = dhCont; }

    public String getXJustContingencia() { return xJustContingencia; }
    public void setXJustContingencia(String xJustContingencia) { this.xJustContingencia = xJustContingencia; }
}
