package br.com.borurio.fiscal.exception;

/** XML da NF-e não passou na validação de schema (XSD) antes da assinatura/transmissão. */
public class XmlSchemaValidationException extends RuntimeException {

    public XmlSchemaValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
