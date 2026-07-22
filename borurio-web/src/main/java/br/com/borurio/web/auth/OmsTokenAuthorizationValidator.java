package br.com.borurio.web.auth;

/**
 * Desacopla JwtFilter do acesso a dados — o filtro depende só desta interface, nunca de mapper/
 * MyBatis diretamente. Permite testar o filtro com um mock simples (JwtFilterTest) e centraliza
 * a única checagem de revogação que faltava (achado do Gate 7H: JwtFilter nunca consultava
 * revogado_em).
 */
public interface OmsTokenAuthorizationValidator {

    OmsTokenAuthorizationContext validate(String jti);
}
