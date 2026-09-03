package br.com.borurio.fiscal.dto;

public class NfeCancelamentoRequest {

    /** Chave de acesso da NF-e (44 dígitos, sem prefixo NFe). */
    private String chaveNfe;

    /** Número do protocolo de autorização retornado pela SEFAZ. */
    private String nProtocolo;

    /** Justificativa do cancelamento (mínimo 15, máximo 255 caracteres). */
    private String justificativa;

    public String getChaveNfe()          { return chaveNfe; }
    public void setChaveNfe(String v)    { this.chaveNfe = v; }

    public String getNProtocolo()        { return nProtocolo; }
    public void setNProtocolo(String v)  { this.nProtocolo = v; }

    public String getJustificativa()     { return justificativa; }
    public void setJustificativa(String v){ this.justificativa = v; }
}
