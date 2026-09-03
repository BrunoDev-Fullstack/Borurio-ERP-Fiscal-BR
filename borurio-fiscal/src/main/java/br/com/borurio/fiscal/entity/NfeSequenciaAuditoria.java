package br.com.borurio.fiscal.entity;

import java.time.LocalDateTime;

/**
 * Trilha de auditoria de atualizações de série/numeração via sincronização OMS.
 * Nunca carrega token, certificado, senha ou XML — só metadados de configuração.
 */
public class NfeSequenciaAuditoria {

    private Long id;
    private String cnpjEmitente;
    private String serieAnterior;
    private String serieAtual;
    private Integer proximoNumeroAnterior;
    private int proximoNumeroAtual;
    private String origem;
    private String clienteOms;
    private String requestId;
    private boolean aplicado;
    private LocalDateTime criadoEm;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCnpjEmitente() { return cnpjEmitente; }
    public void setCnpjEmitente(String cnpjEmitente) { this.cnpjEmitente = cnpjEmitente; }

    public String getSerieAnterior() { return serieAnterior; }
    public void setSerieAnterior(String serieAnterior) { this.serieAnterior = serieAnterior; }

    public String getSerieAtual() { return serieAtual; }
    public void setSerieAtual(String serieAtual) { this.serieAtual = serieAtual; }

    public Integer getProximoNumeroAnterior() { return proximoNumeroAnterior; }
    public void setProximoNumeroAnterior(Integer proximoNumeroAnterior) { this.proximoNumeroAnterior = proximoNumeroAnterior; }

    public int getProximoNumeroAtual() { return proximoNumeroAtual; }
    public void setProximoNumeroAtual(int proximoNumeroAtual) { this.proximoNumeroAtual = proximoNumeroAtual; }

    public String getOrigem() { return origem; }
    public void setOrigem(String origem) { this.origem = origem; }

    public String getClienteOms() { return clienteOms; }
    public void setClienteOms(String clienteOms) { this.clienteOms = clienteOms; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public boolean isAplicado() { return aplicado; }
    public void setAplicado(boolean aplicado) { this.aplicado = aplicado; }

    public LocalDateTime getCriadoEm() { return criadoEm; }
    public void setCriadoEm(LocalDateTime criadoEm) { this.criadoEm = criadoEm; }
}
