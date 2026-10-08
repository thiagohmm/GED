package br.com.ged.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class InvalidCredentialsException extends RuntimeException {

    private static final String DEFAULT_MESSAGE = "Usuario ou senha invalidos.";

    public InvalidCredentialsException() {
        super(DEFAULT_MESSAGE);
    }
}
