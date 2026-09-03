package br.com.borurio.fiscal.domain.nfe;

public class Emit {

    private String cnpj;
    private String xNome;
    private String xFant;
    private String ie;
    private String crt;

    private EnderEmit enderEmit;

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

    public String getXFant() {
        return xFant;
    }

    public void setXFant(String xFant) {
        this.xFant = xFant;
    }

    public String getIe() {
        return ie;
    }

    public void setIe(String ie) {
        this.ie = ie;
    }

    public String getCrt() {
        return crt;
    }

    public void setCrt(String crt) {
        this.crt = crt;
    }

    public EnderEmit getEnderEmit() {
        return enderEmit;
    }

    public void setEnderEmit(EnderEmit enderEmit) {
        this.enderEmit = enderEmit;
    }
}