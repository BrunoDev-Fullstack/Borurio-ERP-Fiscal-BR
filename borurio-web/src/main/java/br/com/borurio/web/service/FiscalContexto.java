package br.com.borurio.web.service;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.fiscal.service.CertificadoContexto;

/**
 * Empresa emitente e certificado resolvidos pra uma operação fiscal vinculada a um pedido/NF-e
 * (cancelamento, CC-e, consulta). Sempre representa um contexto completo e utilizável — nunca
 * um estado parcial: ver FiscalContextoResolver, que lança exceção em vez de devolver empresa
 * ou certificado nulos quando a resolução falha.
 */
public record FiscalContexto(Empresa empresa, CertificadoContexto certificado) {

    public FiscalContexto {
        if (empresa == null) {
            throw new IllegalArgumentException("FiscalContexto exige empresa resolvida (não nula).");
        }
        if (certificado == null) {
            throw new IllegalArgumentException("FiscalContexto exige certificado resolvido (não nulo).");
        }
    }
}
