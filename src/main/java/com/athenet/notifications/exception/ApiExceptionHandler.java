package com.athenet.notifications.exception;

import java.time.Instant;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    public record ApiError(Instant timestamp, int status, String error, String message) {}

    @ExceptionHandler(ConfirmationException.class)
    public ResponseEntity<ApiError> confirmationFailure(ConfirmationException ex) {
        return error(ex.getStatus(), ex.getMessage());
    }

    @ExceptionHandler(RegistrationException.class)
    public ResponseEntity<ApiError> registrationFailure(RegistrationException ex) {
        return error(ex.getStatus(), ex.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            HandlerMethodValidationException.class, HttpMessageNotReadableException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> invalidInput(Exception ex) {
        return error(HttpStatus.BAD_REQUEST, "Revisa el correo o el token enviado.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> invalidToken(IllegalArgumentException ex) {
        return error(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> conflict(DataIntegrityViolationException ex) {
        return error(HttpStatus.CONFLICT, "Otra solicitud está actualizando la suscripción. Intenta nuevamente.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> notFound(NoResourceFoundException ex) {
        return error(HttpStatus.NOT_FOUND, "La ruta solicitada no existe.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> methodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "El método no está permitido para esta ruta.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> unsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Envía JSON o un formulario según el endpoint.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unavailable(Exception ex) {
        log.error("Error en la API del boletín (tipo {})", ex.getClass().getSimpleName());
        return error(HttpStatus.SERVICE_UNAVAILABLE, "No pudimos procesar la solicitud. Intenta nuevamente.");
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), message));
    }
}
