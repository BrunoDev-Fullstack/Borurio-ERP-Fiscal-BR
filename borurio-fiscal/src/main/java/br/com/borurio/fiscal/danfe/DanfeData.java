package br.com.borurio.fiscal.danfe;

import java.util.List;

/** DTO intermediário: dados extraídos do XML NF-e para renderização do DANFE. */
public class DanfeData {

    // IDE
    public String natOp;
    public String dhEmi;
    public String nNF;
    public String serie;
    public String tpAmb;   // "1"=PRD, "2"=HOM

    // EMITENTE
    public String emitXNome;
    public String emitXFant;
    public String emitCnpj;
    public String emitIe;
    public String emitCrt;
    public String emitXLgr;
    public String emitNro;
    public String emitXBairro;
    public String emitXMun;
    public String emitUf;
    public String emitCep;

    // DESTINATÁRIO
    public String destXNome;
    public String destCpfCnpj;
    public String destIe;
    public String destXLgr;
    public String destNro;
    public String destXBairro;
    public String destXMun;
    public String destUf;
    public String destCep;

    // ITENS
    public List<Item> itens;

    // BLOCO E — Cálculo do Imposto
    public String vProd;
    public String vNF;
    public String vBC;
    public String vICMS;
    public String vBCST;
    public String vST;
    public String vIPI;
    public String vFrete;
    public String vSeg;
    public String vDesc;
    public String vOutro;

    // BLOCO F — Transportador
    public String transpModFrete;
    public String transpXNome;
    public String transpCnpjCpf;
    public String transpIe;
    public String transpXEnder;
    public String transpXMun;
    public String transpUf;
    public String volQVol;
    public String volEsp;
    public String volPesoL;
    public String volPesoB;

    // ADICIONAIS
    public String infCpl;

    // PROTOCOLO (de nfe_documento)
    public String chaveNfe;
    public String nProt;
    public String dhRecbto;
    public String cStat;

    public static class Item {
        public int    nItem;
        public String cProd;
        public String xProd;
        public String ncm;
        public String cfop;
        public String uCom;
        public String qCom;
        public String vUnCom;
        public String vProd;
        public String csosn;
        public String orig;
    }
}
