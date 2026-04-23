package br.com.borurio.fiscal.domain.nfe;

public class EnderEmit {

    private String xLgr;     // Logradouro
    private String nro;      // Número
    private String xBairro;  // Bairro
    private String cMun;     // Código IBGE do município
    private String xMun;     // Nome do município
    private String UF;       // UF
    private String CEP;      // CEP
    private String cPais;    // Código do país (1058 = Brasil)
    private String xPais;    // Nome do país

    public String getXLgr() {
        return xLgr;
    }

    public void setXLgr(String xLgr) {
        this.xLgr = xLgr;
    }

    public String getNro() {
        return nro;
    }

    public void setNro(String nro) {
        this.nro = nro;
    }

    public String getXBairro() {
        return xBairro;
    }

    public void setXBairro(String xBairro) {
        this.xBairro = xBairro;
    }

    public String getCMun() {
        return cMun;
    }

    public void setCMun(String cMun) {
        this.cMun = cMun;
    }

    public String getXMun() {
        return xMun;
    }

    public void setXMun(String xMun) {
        this.xMun = xMun;
    }

    public String getUF() {
        return UF;
    }

    public void setUF(String UF) {
        this.UF = UF;
    }

    public String getCEP() {
        return CEP;
    }

    public void setCEP(String CEP) {
        this.CEP = CEP;
    }

    public String getCPais() {
        return cPais;
    }

    public void setCPais(String cPais) {
        this.cPais = cPais;
    }

    public String getXPais() {
        return xPais;
    }

    public void setXPais(String xPais) {
        this.xPais = xPais;
    }
}