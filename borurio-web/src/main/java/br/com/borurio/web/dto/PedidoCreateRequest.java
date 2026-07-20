package br.com.borurio.web.dto;

import br.com.borurio.app.entity.Pedido;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * Payload de criação de pedido controlado pela OMS. Espelha exatamente o Bloco 4 do checklist de
 * onboarding — não inclui {@code serieNfe}, {@code chaveNfe}, {@code status}, {@code numero} nem
 * {@code empresaId}: esses continuam controlados exclusivamente pelo servidor (achados de
 * 17~20/07/2026: bind direto de {@code Pedido} permitia à OMS sobrescrever tanto a série quanto,
 * sem validação nenhuma, um {@code chaveNfe} arbitrário num pedido ainda em RASCUNHO).
 *
 * Se o JSON contiver {@code serieNfe} ou {@code chaveNfe}, o campo é simplesmente ignorado pelo
 * Jackson (propriedade desconhecida) — sem erro ao integrador, sem aplicar o valor.
 */
@Data
public class PedidoCreateRequest {

    private String cnpjEmitente;

    @NotBlank(message = "CNPJ/CPF do destinatário é obrigatório")
    private String destCnpjCpf;

    @NotBlank(message = "Razão social do destinatário é obrigatória")
    private String destRazaoSocial;

    private String destUf;
    private String destLogradouro;
    private String destNumero;
    private String destBairro;
    private String destCodigoMunicipio;
    private String destMunicipio;
    private String destCep;
    private String naturezaOperacao;
    private String externalOrderId;
    private String observacao;

    /** Endereço do emitente — só completa campos ausentes no cadastro da Empresa (ver Bloco 4). */
    private String emitLogradouro;
    private String emitNumero;
    private String emitBairro;
    private String emitCodigoMunicipio;
    private String emitMunicipio;
    private String emitCep;

    private List<PedidoItemCreateRequest> itens;

    /** Converte para a entidade de persistência — nunca copia serieNfe/chaveNfe/status/numero/empresaId. */
    public Pedido toPedido() {
        Pedido pedido = new Pedido();
        pedido.setCnpjEmitente(cnpjEmitente);
        pedido.setDestCnpjCpf(destCnpjCpf);
        pedido.setDestRazaoSocial(destRazaoSocial);
        pedido.setDestUf(destUf);
        pedido.setDestLogradouro(destLogradouro);
        pedido.setDestNumero(destNumero);
        pedido.setDestBairro(destBairro);
        pedido.setDestCodigoMunicipio(destCodigoMunicipio);
        pedido.setDestMunicipio(destMunicipio);
        pedido.setDestCep(destCep);
        pedido.setNaturezaOperacao(naturezaOperacao);
        pedido.setExternalOrderId(externalOrderId);
        pedido.setObservacao(observacao);
        pedido.setEmitLogradouro(emitLogradouro);
        pedido.setEmitNumero(emitNumero);
        pedido.setEmitBairro(emitBairro);
        pedido.setEmitCodigoMunicipio(emitCodigoMunicipio);
        pedido.setEmitMunicipio(emitMunicipio);
        pedido.setEmitCep(emitCep);
        if (itens != null) {
            pedido.setItens(itens.stream().map(PedidoItemCreateRequest::toPedidoItem).toList());
        }
        return pedido;
    }
}
