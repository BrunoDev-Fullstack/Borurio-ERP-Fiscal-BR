package br.com.borurio.fiscal.service.impl;

import org.springframework.stereotype.Service;
import br.com.borurio.fiscal.service.NfeService;

/**
 * Implementação mock da camada de serviço NF-e.
 * Em produção, este serviço fará integração direta com SEFAZ.
 */
@Service
public class NfeServiceImpl implements NfeService {

    @Override
    public String getStatus() {
        return "Serviço NF-e ativo (mock SEFAZ-SP)";
    }

    @Override
    public String authorize(String xmlNfe) {
        if (xmlNfe == null || xmlNfe.isBlank()) {
            return "Rejeitado: XML inválido ou ausente.";
        }
        return "Autorizado: NF-e processada com sucesso (mock).";
    }
}
