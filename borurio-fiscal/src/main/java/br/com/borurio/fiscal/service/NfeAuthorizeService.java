package br.com.borurio.fiscal.service;

import org.w3c.dom.Document;

/**
 * Interface responsável pelo processo de autorização da Nota Fiscal Eletrônica (NF-e)
 * no ambiente local do ERP Borurio Fiscal Brasil.
 *
 * Esta camada define o contrato para validação, autorização e geração de protocolo
 * da NF-e conforme o layout PL009 (versão 4.00) e a Nota Técnica 2025.002 (IBS/CBS/IS),
 * antes da integração com a SEFAZ-SP real.
 *
 * A implementação concreta (NfeAuthorizeServiceImpl) é responsável por:
 *  - Ler o XML de entrada enviado pelo controlador (/nfe/enviar);
 *  - Validar o documento contra os schemas XSD oficiais da SEFAZ;
 *  - Simular a autorização (mock SEFAZ local) com geração de protocolo;
 *  - Persistir o evento de autorização na tabela nfe_log;
 *  - Retornar o XML de resposta contendo o elemento <protNFe>.
 *
 * Boas práticas aplicadas:
 *  - Arquitetura em camadas (Controller → Service → Mapper)
 *  - Separação de responsabilidades (interface + implementação)
 *  - Validação XML via XSD oficial (leitura por classpath)
 *  - Registro auditável de eventos fiscais (NfeLogService)
 *  - Princípios DevSecOps (segurança, rastreabilidade, automação)
 *
 * Versão: 1.0.0
 * Módulo: borurio-fiscal
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 */
public interface NfeAuthorizeService {

    /**
     * Realiza o processo completo de autorização da NF-e em ambiente local (mock SEFAZ).
     *
     * Etapas esperadas:
     *  1. Validação do XML conforme layout PL009 (XSD 4.00);
     *  2. Simulação da autorização com geração de protocolo (<protNFe>);
     *  3. Registro do evento em nfe_log;
     *  4. Retorno do XML de resposta com <cStat>100</cStat>.
     *
     * @param xmlDocumento Documento XML da NF-e (DOM parseado).
     * @return Documento XML de resposta (autorização simulada SEFAZ).
     * @throws Exception Em caso de falha de validação, IO ou inconsistência fiscal.
     */
    Document autorizarNFe(Document xmlDocumento) throws Exception;

    /**
     * Valida o XML de entrada da NF-e contra os schemas XSD oficiais.
     *
     * O método deve utilizar o validador interno (XsdValidator) e o conjunto de arquivos
     * localizados em /resources/xsd/ (leiauteNFe_v4.00.xsd e dependências).
     *
     * @param xmlDocumento Documento XML da NF-e a ser validado.
     * @throws Exception Caso o XML não esteja em conformidade com o schema.
     */
    void validarXML(Document xmlDocumento) throws Exception;

    /**
     * Gera o XML de resposta contendo o protocolo de autorização (mock SEFAZ).
     *
     * O retorno simula o comportamento da SEFAZ-SP, incluindo:
     *  - Código de status (cStat=100);
     *  - Mensagem de sucesso ("Autorizado o uso da NF-e");
     *  - Data/hora da autorização;
     *  - Número de protocolo simulado (nProt).
     *
     * @param xmlDocumento Documento XML original autorizado.
     * @return Documento XML de resposta (retEnviNFe).
     * @throws Exception Em caso de erro na montagem do XML de retorno.
     */
    Document gerarProtocolo(Document xmlDocumento) throws Exception;
}
