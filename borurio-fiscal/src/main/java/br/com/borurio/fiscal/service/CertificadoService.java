package br.com.borurio.fiscal.service;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * =============================================================================
 * INTERFACE: CertificadoService
 * -----------------------------------------------------------------------------
 * Responsável por definir o contrato para o gerenciamento e obtenção do
 * certificado digital A1 (arquivo .pfx) utilizado na comunicação segura
 * com a SEFAZ-SP (NF-e 4.00).
 *
 * Perfis de implementação:
 *   - dev / hom: modo simulado (mock SEFAZ, sem carga real de certificado)
 *   - prd: modo real (carrega o certificado digital A1 e inicializa SSLContext)
 *
 * Utilização:
 *   Implementações concretas devem garantir que o SSLContext esteja
 *   devidamente configurado para autenticação mútua via TLS 1.2+,
 *   respeitando os padrões ICP-Brasil e SEFAZ.
 *
 * Observações:
 *   - Em DEV/HOM este serviço pode operar em modo passivo, retornando null.
 *   - Em PRD qualquer falha no carregamento do certificado deve impedir
 *     a inicialização da aplicação.
 *
 * Autor: Bruno Ribeiro – Desenvolvedor Fullstack / DevSecOps
 * Projeto: Borurio ERP Fiscal BR
 * Data: 29/10/2025
 * =============================================================================
 */
public interface CertificadoService {

    /**
     * Retorna o contexto SSL configurado com base no certificado digital A1.
     * Deve ser utilizado em clientes HTTP/SOAP que se comunicam com a SEFAZ-SP.
     *
     * Comportamento esperado:
     *   - PRD: retorna um SSLContext totalmente configurado
     *   - DEV/HOM: pode retornar null quando operando em modo simulado
     *
     * @return SSLContext configurado ou null (DEV/HOM)
     * @throws GeneralSecurityException falha criptográfica ou de keystore
     * @throws IOException falha de leitura do arquivo do certificado
     */
    SSLContext getSslContext() throws GeneralSecurityException, IOException;

    /**
     * Retorna uma descrição textual do estado atual do serviço de certificado.
     * Método indicado para:
     *   - logs de inicialização
     *   - endpoints de diagnóstico
     *   - auditoria operacional
     *
     * Exemplos de retorno:
     *   - "Certificado A1 carregado com sucesso"
     *   - "Modo DEV ativo - certificado não carregado"
     *   - "Erro ao inicializar certificado digital"
     *
     * @return Descrição do status atual do serviço
     */
    String getStatus();
}
