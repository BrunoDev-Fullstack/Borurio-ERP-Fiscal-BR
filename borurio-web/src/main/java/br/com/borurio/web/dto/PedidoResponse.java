package br.com.borurio.web.dto;

import br.com.borurio.app.entity.Pedido;
import br.com.borurio.app.entity.PedidoItem;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class PedidoResponse {

    private Long id;
    private Long empresaId;
    private String numero;
    private String cnpjEmitente;
    private String destCnpjCpf;
    private String destRazaoSocial;
    private String destUf;
    private String destLogradouro;
    private String destNumero;
    private String destBairro;
    private String destCodigoMunicipio;
    private String destMunicipio;
    private String destCep;
    private String naturezaOperacao;
    private String serieNfe;
    private String status;
    private String chaveNfe;
    private BigDecimal valorTotal;
    private String observacao;
    private LocalDateTime dataPedido;
    private LocalDateTime dataAtualizacao;
    private List<PedidoItem> itens;

    public static PedidoResponse from(Pedido p) {
        PedidoResponse r = new PedidoResponse();
        r.setId(p.getId());
        r.setEmpresaId(p.getEmpresaId());
        r.setNumero(p.getNumero());
        r.setCnpjEmitente(p.getCnpjEmitente());
        r.setDestCnpjCpf(p.getDestCnpjCpf());
        r.setDestRazaoSocial(p.getDestRazaoSocial());
        r.setDestUf(p.getDestUf());
        r.setDestLogradouro(p.getDestLogradouro());
        r.setDestNumero(p.getDestNumero());
        r.setDestBairro(p.getDestBairro());
        r.setDestCodigoMunicipio(p.getDestCodigoMunicipio());
        r.setDestMunicipio(p.getDestMunicipio());
        r.setDestCep(p.getDestCep());
        r.setNaturezaOperacao(p.getNaturezaOperacao());
        r.setSerieNfe(p.getSerieNfe());
        r.setStatus(p.getStatus());
        r.setChaveNfe(p.getChaveNfe());
        r.setValorTotal(p.getValorTotal());
        r.setObservacao(p.getObservacao());
        r.setDataPedido(p.getDataPedido());
        r.setDataAtualizacao(p.getDataAtualizacao());
        r.setItens(p.getItens());
        return r;
    }
}
