package io.modivra.model.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class ModelDisabledException extends RuntimeException {

    public ModelDisabledException(String message) {
        super(message);
    }
}
