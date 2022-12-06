package org.openjdk.skara.network;

/**
 * Specialized RuntimeException thrown when a REST call receives a response code
 * >=400 that isn't handled. When catching this, details about the failed call
 * has already been logged.
 */
public class UncheckedRestException extends RuntimeException {
    int statusCode;

    public UncheckedRestException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
