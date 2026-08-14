package br.com.borurio.fiscal.exception;

/**
 * A rota SEFAZ (autorizador normal) para a UF informada não existe ou está incompleta —
 * {@code sefaz.rotas.<UF>.*} ausente ou com algum dos 4 endpoints em branco (Fase 0 do Gate SVC,
 * 14-08-2026, achado de code review).
 *
 * Erro de configuração determinístico, nunca de rede: precisa permanecer distinguível de
 * {@link SefazTransmissaoIncertaException} em qualquer ponto do pipeline que capture exceções de
 * transmissão genericamente (ex.: {@code NfeOrquestradorService.processar()}) — do contrário, uma
 * UF sem rota configurada seria classificada como "transmissão incerta"/retryable, quando na
 * verdade é um erro local que nunca se resolve sozinho em uma nova tentativa. Estende
 * {@link IllegalStateException} (compatível com o tipo genérico já usado antes desta correção),
 * mas deve ser sempre capturada pelo tipo específico ANTES de qualquer {@code catch (Exception e)}
 * que envolva a chamada de transporte.
 */
public class SefazRotaNaoConfiguradaException extends IllegalStateException {

    public SefazRotaNaoConfiguradaException(String message) {
        super(message);
    }
}
