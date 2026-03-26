package br.com.borurio.fiscal.domain.nfe;

public class Ide {

    // Código da UF (ex: 35 = SP)
    private String cUF;

    // Natureza da operação
    private String natOp;

    // Modelo da NF-e (sempre 55)
    private String mod = "55";

    // Série da nota
    private String serie;

    // Número da nota
    private String nNF;

    // Data/hora emissão (ISO 8601)
    private String dhEmi;

    // Tipo operação (0 = entrada, 1 = saída)
    private String tpNF;

    // Destino operação (1= interna, 2= interestadual, 3= exterior)
    private String idDest;

    // Ambiente (1=produção, 2=homologação)
    private String tpAmb;

    // Finalidade (1=normal)
    private String finNFe;

    // getters/setters

    public String getCUF() { return cUF; }
    public void setCUF(String cUF) { this.cUF = cUF; }

    public String getNatOp() { return natOp; }
    public void setNatOp(String natOp) { this.natOp = natOp; }

    public String getMod() { return mod; }

    public String getSerie() { return serie; }
    public void setSerie(String serie) { this.serie = serie; }

    public String getNNF() { return nNF; }
    public void setNNF(String nNF) { this.nNF = nNF; }

    public String getDhEmi() { return dhEmi; }
    public void setDhEmi(String dhEmi) { this.dhEmi = dhEmi; }

    public String getTpNF() { return tpNF; }
    public void setTpNF(String tpNF) { this.tpNF = tpNF; }

    public String getIdDest() { return idDest; }
    public void setIdDest(String idDest) { this.idDest = idDest; }

    public String getTpAmb() { return tpAmb; }
    public void setTpAmb(String tpAmb) { this.tpAmb = tpAmb; }

    public String getFinNFe() { return finNFe; }
    public void setFinNFe(String finNFe) { this.finNFe = finNFe; }
}