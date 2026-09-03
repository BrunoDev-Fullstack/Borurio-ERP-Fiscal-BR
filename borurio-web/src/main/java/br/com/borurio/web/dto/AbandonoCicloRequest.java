package br.com.borurio.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corpo de {@code POST /api/admin/nfe-emissoes/{emissaoId}/abandonar}.
 *
 * {@code motivo} obrigatório (mesma disciplina de justificativa do cancelamento e da revogação
 * de token OMS): o abandono é uma operação de recovery fiscal e precisa deixar rastro do porquê
 * no log. Não vai para o banco nesta versão — é registrado no log estruturado do serviço.
 */
public class AbandonoCicloRequest {

    @NotBlank(message = "motivo é obrigatório")
    @Size(min = 15, max = 255, message = "motivo deve ter entre 15 e 255 caracteres")
    private String motivo;

    public String getMotivo() { return motivo; }
    public void setMotivo(String motivo) { this.motivo = motivo; }
}
