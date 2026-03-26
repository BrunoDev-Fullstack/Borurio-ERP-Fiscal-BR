package br.com.borurio.fiscal.domain.nfe;

public class Emit {

    // CNPJ do emitente (somente números)
    private String cnpj;

    // Razão social
    private String xNome;

    // Inscrição estadual
    private String ie;

    // getters/setters

    public String getCnpj() {
        return cnpj;
    }

    public void setCnpj(String cnpj) {
        this.cnpj = cnpj;
    }

    public String getXNome() {
        return xNome;
    }

    public void setXNome(String xNome) {
        this.xNome = xNome;
    }

    public String getIe() {
        return ie;
    }

    public void setIe(String ie) {
        this.ie = ie;
    }
}