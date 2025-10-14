package br.com.borurio.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controlador responsável por verificar a disponibilidade da API.
 *
 * Utilizado como endpoint de monitoramento e diagnóstico
 * em ambientes de desenvolvimento, homologação e produção.
 */
@RestController
public class PingController {

    /**
     * Endpoint de verificação básica da aplicação.
     *
     * @return Mensagem indicando que a API está em execução.
     */
    @GetMapping("/ping")
    public String ping() {
        return "API Borurio ERP BR está online.";
    }
}
