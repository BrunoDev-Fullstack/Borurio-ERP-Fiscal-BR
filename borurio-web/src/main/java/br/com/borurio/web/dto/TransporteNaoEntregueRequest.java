package br.com.borurio.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corpo de {@code POST /api/admin/nfe-emissoes/{emissaoId}/marcar-transporte-nao-entregue}.
 *
 * {@code motivo} obrigatório (mesma disciplina de justificativa do abandono/cancelamento):
 * é recovery fiscal e precisa deixar rastro do porquê no log estruturado.
 */
public class TransporteNaoEntregueRequest {

    @NotBlank(message = "motivo é obrigatório")
    @Size(min = 15, max = 255, message = "motivo deve ter entre 15 e 255 caracteres")
    private String motivo;

    public String getMotivo() { return motivo; }
    public void setMotivo(String motivo) { this.motivo = motivo; }
}
