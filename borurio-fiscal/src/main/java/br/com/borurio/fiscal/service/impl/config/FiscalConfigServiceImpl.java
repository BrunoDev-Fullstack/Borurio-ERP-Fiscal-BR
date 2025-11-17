package br.com.borurio.fiscal.service.impl.config;

import br.com.borurio.fiscal.dto.*;
import br.com.borurio.fiscal.service.config.FiscalConfigService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class FiscalConfigServiceImpl implements FiscalConfigService {

    @Override
    public FiscalConfigDTO carregarConfiguracoes() {

        FiscalConfigDTO dto = new FiscalConfigDTO();

        dto.setCfops(List.of(
                cfop("5102", "Venda dentro do estado", "interna"),
                cfop("6102", "Venda fora do estado", "interestadual"),
                cfop("7101", "Venda exterior", "exterior"),
                cfop("1202", "Devolução de venda", "devolucao")
        ));

        dto.setCsts(List.of(
                cst("00", "Tributação integral", "Regime Normal"),
                cst("20", "Com redução da BC", "Regime Normal"),
                cst("40", "Isento", "Regime Normal")
        ));

        dto.setCsosn(List.of(
                csosn("101", "Simples Nacional com crédito"),
                csosn("102", "Simples Nacional sem crédito"),
                csosn("500", "ST recolhida anteriormente")
        ));

        dto.setPis(List.of(
                pis("01", "Operação tributável"),
                pis("06", "Operação isenta")
        ));

        dto.setCofins(List.of(
                pis("01", "Operação tributável"),
                pis("04", "Operação isenta")
        ));

        dto.setIpi(List.of(
                ipi("50", "Tributado"),
                ipi("99", "Outras saídas")
        ));

        dto.setTiposOperacao(List.of(
                tipo("VENDA", "Venda"),
                tipo("DEVOLUCAO", "Devolução"),
                tipo("REMESSA", "Remessa"),
                tipo("AJUSTE", "Ajuste fiscal")
        ));

        return dto;
    }

    private CfopDTO cfop(String c, String d, String t) {
        CfopDTO o = new CfopDTO();
        o.setCodigo(c);
        o.setDescricao(d);
        o.setTipo(t);
        return o;
    }

    private CstDTO cst(String c, String d, String r) {
        CstDTO o = new CstDTO();
        o.setCodigo(c);
        o.setDescricao(d);
        o.setRegime(r);
        return o;
    }

    private CsosnDTO csosn(String c, String d) {
        CsosnDTO o = new CsosnDTO();
        o.setCodigo(c);
        o.setDescricao(d);
        return o;
    }

    private PisCofinsDTO pis(String c, String d) {
        PisCofinsDTO o = new PisCofinsDTO();
        o.setCodigo(c);
        o.setDescricao(d);
        return o;
    }

    private IpiDTO ipi(String c, String d) {
        IpiDTO o = new IpiDTO();
        o.setCodigo(c);
        o.setDescricao(d);
        return o;
    }

    private TipoOperacaoDTO tipo(String c, String d) {
        TipoOperacaoDTO o = new TipoOperacaoDTO();
        o.setCodigo(c);
        o.setDescricao(d);
        return o;
    }
}
