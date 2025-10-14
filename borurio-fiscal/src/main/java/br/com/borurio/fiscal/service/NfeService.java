package br.com.borurio.fiscal.service;

/**
 * Interface de serviço responsável pelas operações de NF-e.
 * Fornece métodos para checar status e autorizar notas fiscais eletrônicas.
 */
public interface NfeService {

    /**
     * Retorna o status atual do serviço SEFAZ (mock em ambiente dev).
     */
    String getStatus();

    /**
     * Processa e autoriza uma NF-e em formato XML.
     *
     * @param xmlNfe conteúdo XML da NF-e
     * @return resultado da autorização (mock)
     */
    String authorize(String xmlNfe);
}
