package io.modivra.model.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class ModelAmbiguityException extends RuntimeException {

    public ModelAmbiguityException(String message) {
        super(message);
    }
}
