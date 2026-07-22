package br.com.borurio.web.auth;

import br.com.borurio.app.entity.OmsFiscalAuthorization;
import br.com.borurio.app.mapper.OmsFiscalAuthorizationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class OmsTokenAuthorizationValidatorImpl implements OmsTokenAuthorizationValidator {

    private static final Logger log = LoggerFactory.getLogger(OmsTokenAuthorizationValidatorImpl.class);

    private final OmsFiscalAuthorizationMapper omsAuthMapper;

    public OmsTokenAuthorizationValidatorImpl(OmsFiscalAuthorizationMapper omsAuthMapper) {
        this.omsAuthMapper = omsAuthMapper;
    }

    /**
     * Consultado a cada requisição autenticada como OMS — sem cache, para que uma revogação
     * administrativa tenha efeito imediato na próxima chamada. Falha fechado: erro de acesso ao
     * banco retorna SERVICE_UNAVAILABLE (503, retryable), nunca autentica silenciosamente.
     */
    @Override
    public OmsTokenAuthorizationContext validate(String jti) {
        OmsFiscalAuthorization auth;
        try {
            auth = omsAuthMapper.buscarPorJti(jti);
        } catch (DataAccessException e) {
            log.error("[OmsTokenValidator] Falha ao consultar autorização OMS", e);
            return OmsTokenAuthorizationContext.inativo(OmsTokenValidationResult.SERVICE_UNAVAILABLE);
        }

        if (auth == null) {
            return OmsTokenAuthorizationContext.inativo(OmsTokenValidationResult.NOT_FOUND);
        }
        if (auth.getRevogadoEm() != null) {
            return OmsTokenAuthorizationContext.inativo(OmsTokenValidationResult.REVOKED);
        }
        if (auth.getTokenExpiraEm() != null && auth.getTokenExpiraEm().isBefore(LocalDateTime.now())) {
            return OmsTokenAuthorizationContext.inativo(OmsTokenValidationResult.EXPIRED);
        }
        return OmsTokenAuthorizationContext.ativo(auth.getId(), auth.getEmpresaId(), auth.getCodigoOms());
    }
}
