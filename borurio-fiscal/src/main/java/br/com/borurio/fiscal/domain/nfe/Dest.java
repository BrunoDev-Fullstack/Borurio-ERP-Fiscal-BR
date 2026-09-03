package br.com.borurio.fiscal.domain.nfe;

public class Dest {

    private String cpfCnpj;
    private String xNome;
    private String indIEDest;
    private String ie;
    private EnderDest enderDest;

    public String getCpfCnpj() { return cpfCnpj; }
    public void setCpfCnpj(String cpfCnpj) { this.cpfCnpj = cpfCnpj; }

    public String getXNome() { return xNome; }
    public void setXNome(String xNome) { this.xNome = xNome; }

    public String getIndIEDest() { return indIEDest; }
    public void setIndIEDest(String indIEDest) { this.indIEDest = indIEDest; }

    public String getIe() { return ie; }
    public void setIe(String ie) { this.ie = ie; }

    public EnderDest getEnderDest() { return enderDest; }
    public void setEnderDest(EnderDest enderDest) { this.enderDest = enderDest; }
}
