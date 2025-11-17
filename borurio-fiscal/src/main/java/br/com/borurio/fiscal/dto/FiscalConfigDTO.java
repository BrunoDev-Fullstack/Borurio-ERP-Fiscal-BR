package br.com.borurio.fiscal.dto;

import lombok.Data;
import java.util.List;

@Data
public class FiscalConfigDTO {
    private List<CfopDTO> cfops;
    private List<CstDTO> csts;
    private List<CsosnDTO> csosn;
    private List<PisCofinsDTO> pis;
    private List<PisCofinsDTO> cofins;
    private List<IpiDTO> ipi;
    private List<TipoOperacaoDTO> tiposOperacao;
}
