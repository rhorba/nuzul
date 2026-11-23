package ma.nuzul.shared.web;

import java.util.List;
import ma.nuzul.shared.error.CodedException;
import ma.nuzul.shared.error.ConflictException;
import ma.nuzul.shared.error.DomainValidationException;
import ma.nuzul.shared.error.GoneException;
import ma.nuzul.shared.error.NotFoundException;
import ma.nuzul.shared.error.PaymentRequiredException;
import ma.nuzul.shared.error.TooManyRequestsException;
import ma.nuzul.shared.error.UnprocessableException;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The single source of problem+json responses (so {@code spring.mvc.problemdetails.enabled} is not used).
 * Maps errors to RFC 9457 problem+json with a stable {@code code} property; every 400 carries VALIDATION_FAILED. Deliberately does not catch
 * Exception/RuntimeException, so Spring Security still turns AccessDeniedException into 403 and missing
 * authentication into 401.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    static final String CODE = "code";
    static final String MALFORMED = "Malformed request";

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail handleNotFound(NotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex);
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail handleConflict(ConflictException ex) {
        return problem(HttpStatus.CONFLICT, ex);
    }

    @ExceptionHandler(GoneException.class)
    ProblemDetail handleGone(GoneException ex) {
        return problem(HttpStatus.GONE, ex);
    }

    @ExceptionHandler(PaymentRequiredException.class)
    ProblemDetail handlePaymentRequired(PaymentRequiredException ex) {
        return problem(HttpStatus.PAYMENT_REQUIRED, ex);
    }

    @ExceptionHandler(UnprocessableException.class)
    ProblemDetail handleUnprocessable(UnprocessableException ex) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, ex);
    }

    /** 429; {@code Retry-After} (whole seconds) only when the limit knows when it lifts. */
    @ExceptionHandler(TooManyRequestsException.class)
    ResponseEntity<ProblemDetail> handleTooManyRequests(TooManyRequestsException ex) {
        var response = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS);
        ex.retryAfterSeconds().ifPresent(seconds -> response.header(HttpHeaders.RETRY_AFTER, Long.toString(seconds)));
        return response.body(problem(HttpStatus.TOO_MANY_REQUESTS, ex));
    }

    @ExceptionHandler(DomainValidationException.class)
    ProblemDetail handleDomainValidation(DomainValidationException ex) {
        return problem(HttpStatus.BAD_REQUEST, ex);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var response = super.handleMethodArgumentNotValid(ex, headers, status, request);
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .toList();
        return withValidationCode(response, errors);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var response = super.handleHandlerMethodValidationException(ex, headers, status, request);
        List<String> errors = ex.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream()
                        .map(e -> r.getMethodParameter().getParameterName() + ": " + e.getDefaultMessage()))
                .toList();
        return withValidationCode(response, errors);
    }

    /** Malformed JSON or an unknown enum constant. The parser's message names internal types, so it is dropped. */
    @Override
    protected @Nullable ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return malformed(super.handleHttpMessageNotReadable(ex, headers, status, request), List.of());
    }

    /** A path variable or query parameter of the wrong type, e.g. a non-UUID id or {@code ?page=abc}. */
    @Override
    protected @Nullable ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String name = ex instanceof MethodArgumentTypeMismatchException m ? m.getName() : ex.getPropertyName();
        List<String> errors = name == null ? List.of() : List.of(name + ": invalid value");
        return malformed(super.handleTypeMismatch(ex, headers, status, request), errors);
    }

    /** Any other 400 raised by Spring MVC still carries the VALIDATION_FAILED code. */
    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        var response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (statusCode.value() == HttpStatus.BAD_REQUEST.value()
                && response != null
                && response.getBody() instanceof ProblemDetail pd
                && (pd.getProperties() == null || !pd.getProperties().containsKey(CODE))) {
            pd.setProperty(CODE, DomainValidationException.VALIDATION_FAILED);
        }
        return response;
    }

    private static @Nullable ResponseEntity<Object> malformed(
            @Nullable ResponseEntity<Object> response, List<String> errors) {
        var result = withValidationCode(response, errors);
        if (result != null && result.getBody() instanceof ProblemDetail pd) {
            pd.setDetail(MALFORMED);
        }
        return result;
    }

    private static @Nullable ResponseEntity<Object> withValidationCode(
            @Nullable ResponseEntity<Object> response, List<String> errors) {
        if (response != null && response.getBody() instanceof ProblemDetail pd) {
            pd.setProperty(CODE, DomainValidationException.VALIDATION_FAILED);
            pd.setProperty("errors", errors);
        }
        return response;
    }

    private static ProblemDetail problem(HttpStatus status, CodedException ex) {
        var pd = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        ex.properties().forEach(pd::setProperty);
        pd.setProperty(CODE, ex.code());
        return pd;
    }
}
