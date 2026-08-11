package br.com.borurio.fiscal.dto;

/**
 * Resultado de uma tentativa de emissão/reconciliação, devolvido por /emitir.
 *
 * chaveNfe/soapRetorno existem desde sempre — nunca renomear (contrato OMS já em uso). Os campos
 * fiscais (serie, numeroNfe, estadoFiscal, cStat, xMotivo, nProt) são aditivos (Gate de contrato
 * OMS, 11-08-2026): preservam os tipos reais de {@code nfe_emissao} (fonte única destes seis
 * dados) — serie e xMotivo/nProt String, numeroNfe/cStat Integer, ambos nullable porque nem toda
 * chamada tem um ciclo de nNF resolvido (ex.: endpoint legado {@code NfeEnvioController}, que
 * nunca preenche esses campos e continua usando só chaveNfe/soapRetorno).
 */
public class NfeGeracaoResult {

    private final String chaveNfe;
    private final String soapRetorno;
    private final String serie;
    private final Integer numeroNfe;
    private final String estadoFiscal;
    private final Integer cStat;
    private final String xMotivo;
    private final String nProt;

    public NfeGeracaoResult(String chaveNfe, String soapRetorno) {
        this(chaveNfe, soapRetorno, null, null, null, null, null, null);
    }

    public NfeGeracaoResult(String chaveNfe, String soapRetorno, String serie, Integer numeroNfe,
                             String estadoFiscal, Integer cStat, String xMotivo, String nProt) {
        this.chaveNfe     = chaveNfe;
        this.soapRetorno  = soapRetorno;
        this.serie        = serie;
        this.numeroNfe    = numeroNfe;
        this.estadoFiscal = estadoFiscal;
        this.cStat        = cStat;
        this.xMotivo      = xMotivo;
        this.nProt        = nProt;
    }

    public String getChaveNfe()      { return chaveNfe; }
    public String getSoapRetorno()   { return soapRetorno; }
    public String getSerie()         { return serie; }
    public Integer getNumeroNfe()    { return numeroNfe; }
    public String getEstadoFiscal()  { return estadoFiscal; }
    public Integer getCStat()        { return cStat; }
    public String getXMotivo()       { return xMotivo; }
    public String getNProt()         { return nProt; }
}
