package br.com.borurio.fiscal.dto;

public class NfeGeracaoResult {

    private final String chaveNfe;
    private final String soapRetorno;

    public NfeGeracaoResult(String chaveNfe, String soapRetorno) {
        this.chaveNfe    = chaveNfe;
        this.soapRetorno = soapRetorno;
    }

    public String getChaveNfe()    { return chaveNfe; }
    public String getSoapRetorno() { return soapRetorno; }
}
