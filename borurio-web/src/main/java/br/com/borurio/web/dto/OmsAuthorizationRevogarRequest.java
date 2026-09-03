package br.com.borurio.web.dto;

import br.com.borurio.app.entity.MotivoAdminOms;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class OmsAuthorizationRevogarRequest {

    @NotNull(message = "motivoCodigo é obrigatório")
    private MotivoAdminOms motivoCodigo;

    @Size(max = 255, message = "motivoDetalhe deve ter no máximo 255 caracteres")
    private String motivoDetalhe;

    public MotivoAdminOms getMotivoCodigo() { return motivoCodigo; }
    public void setMotivoCodigo(MotivoAdminOms motivoCodigo) { this.motivoCodigo = motivoCodigo; }

    public String getMotivoDetalhe() { return motivoDetalhe; }
    public void setMotivoDetalhe(String motivoDetalhe) { this.motivoDetalhe = motivoDetalhe; }
}
