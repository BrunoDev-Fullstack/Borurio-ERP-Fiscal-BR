package br.com.borurio.web.dto;

/** Resultado da reserva atômica de série + número no início de uma tentativa de emissão. */
public record ReservaFiscalResultado(String serie, int numero) {}
