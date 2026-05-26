package br.com.borurio.fiscal.dto;

public class NfeManifestacaoRequest {

    /** Chave de acesso da NF-e (44 dígitos). */
    private String chaveNfe;

    /** Tipo do evento: 210200, 210210, 210220 ou 210240. */
    private String tipoEvento;

    /** CNPJ do destinatário que manifesta (14 dígitos, sem formatação). */
    private String cnpjDestinatario;

    /** Justificativa — obrigatória apenas para 210240, mín 15 / máx 255 chars. */
    private String xJust;

    public String getChaveNfe()                  { return chaveNfe; }
    public void setChaveNfe(String chaveNfe)     { this.chaveNfe = chaveNfe; }

    public String getTipoEvento()                { return tipoEvento; }
    public void setTipoEvento(String tipoEvento) { this.tipoEvento = tipoEvento; }

    public String getCnpjDestinatario()                      { return cnpjDestinatario; }
    public void setCnpjDestinatario(String cnpjDestinatario) { this.cnpjDestinatario = cnpjDestinatario; }

    public String getXJust()             { return xJust; }
    public void setXJust(String xJust)   { this.xJust = xJust; }
}
