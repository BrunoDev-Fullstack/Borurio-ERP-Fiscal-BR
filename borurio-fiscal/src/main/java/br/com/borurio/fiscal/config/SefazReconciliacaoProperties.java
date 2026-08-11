package br.com.borurio.fiscal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuração da política de backoff da reconciliação fiscal (Gate 3, 10-08-2026) — nunca
 * hardcoded no código, porque os critérios de consumo indevido (cStat 656) são definidos pelo
 * autorizador e podem mudar por ambiente/UF.
 *
 * sefaz.reconciliacao no application.yml:
 *   backoff-inicial-segundos: intervalo antes da primeira consulta de reconciliação repetida
 *   backoff-multiplicador: fator de crescimento exponencial a cada tentativa
 *   backoff-maximo-segundos: teto do intervalo, nunca cresce além disso
 *   limite-tentativas: quantidade de consultas antes de considerar necessária intervenção manual
 *   idade-maxima-minutos: idade do ciclo pendente a partir da qual, mesmo dentro do limite de
 *     tentativas, a situação deve ser registrada como precisando de intervenção operacional —
 *     nunca decide sozinho liberar o número, só sinaliza a necessidade de olhar o caso.
 */
@Configuration
@ConfigurationProperties(prefix = "sefaz.reconciliacao")
public class SefazReconciliacaoProperties {

    private int backoffInicialSegundos = 30;
    private double backoffMultiplicador = 2.0;
    private int backoffMaximoSegundos = 600;
    private int limiteTentativas = 10;
    private int idadeMaximaMinutos = 60;

    public int getBackoffInicialSegundos() { return backoffInicialSegundos; }
    public void setBackoffInicialSegundos(int backoffInicialSegundos) { this.backoffInicialSegundos = backoffInicialSegundos; }

    public double getBackoffMultiplicador() { return backoffMultiplicador; }
    public void setBackoffMultiplicador(double backoffMultiplicador) { this.backoffMultiplicador = backoffMultiplicador; }

    public int getBackoffMaximoSegundos() { return backoffMaximoSegundos; }
    public void setBackoffMaximoSegundos(int backoffMaximoSegundos) { this.backoffMaximoSegundos = backoffMaximoSegundos; }

    public int getLimiteTentativas() { return limiteTentativas; }
    public void setLimiteTentativas(int limiteTentativas) { this.limiteTentativas = limiteTentativas; }

    public int getIdadeMaximaMinutos() { return idadeMaximaMinutos; }
    public void setIdadeMaximaMinutos(int idadeMaximaMinutos) { this.idadeMaximaMinutos = idadeMaximaMinutos; }
}
