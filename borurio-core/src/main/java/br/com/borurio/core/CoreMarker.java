package br.com.borurio.core;

/**
 * Marcador base do módulo Core do ERP Fiscal Borurio Brasil.
 *
 * Este pacote representa o núcleo compartilhado entre todos os módulos
 * (app, fiscal e web), contendo enums, utilitários e classes de base.
 *
 * Finalidade:
 *  - Ajudar o Spring Boot e o IntelliJ a reconhecer o pacote core;
 *  - Servir de referência para varredura de componentes;
 *  - Garantir compatibilidade modular (multi-module build).
 *
 * Compatível com Java 17 e Spring Boot 3.3.x.
 */
public final class CoreMarker {
    private CoreMarker() {
        // Evita instanciação
    }
}
