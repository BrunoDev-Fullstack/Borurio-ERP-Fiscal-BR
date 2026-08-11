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

        /** Estados que liberam o gate da sequencia (nfe_sequencia.emissao_ativa_id) ao serem alcancados. */
        public static boolean isTerminal(String estado) {
            return AUTORIZADO.equals(estado) || DENEGADO.equals(estado) || NUMERO_OCUPADO.equals(estado);
        }

        private Estados() {}
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
}
