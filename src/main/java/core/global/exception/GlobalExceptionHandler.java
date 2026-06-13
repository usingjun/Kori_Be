package core.global.exception;


import core.global.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Set;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private ResponseEntity<ApiErrorResponse> respond(HttpStatus status, String error, String code, String message) {
        return ResponseEntity.status(status).body(ApiErrorResponse.of(error, code, message));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiErrorResponse> handleBusiness(BusinessException ex) {
        return ResponseEntity.status(ex.getStatus()).body(ApiErrorResponse.of("BUSINESS_ERROR", ex.getError().code(), ex.getMessage(), ex.getDetail()));
    }

    // Bean Validation - DTO 바인딩/필드 검증 실패(@ValidBirthday 포함)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpServletRequest req) {
        var fe = ex.getBindingResult().getFieldError();
        String field = fe != null ? fe.getField() : null;
        String msg = fe != null ? fe.getDefaultMessage() : "유효성 검증에 실패했습니다.";
        String code = "INVALID_INPUT";
        if ("birthday".equals(field)) code = "INVALID_BIRTHDAY_FORMAT";

        log.warn("422 Validation Error: method={} uri={} field={} msg={}", req.getMethod(), req.getRequestURI(), field, msg);
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR", code, msg);
    }

    // Bean Validation - 파라미터/쿼리 제약 위반
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest req) {
        var v = ex.getConstraintViolations().stream().findFirst();
        String path = v.map(cv -> cv.getPropertyPath().toString()).orElse(null);
        String msg = v.map(jakarta.validation.ConstraintViolation::getMessage).orElse("유효성 검증에 실패했습니다.");
        String code = (path != null && path.contains("birthday")) ? "INVALID_BIRTHDAY_FORMAT" : "INVALID_INPUT";

        log.warn("422 Constraint Violation: method={} uri={} path={} msg={}", req.getMethod(), req.getRequestURI(), path, msg);
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_ERROR", code, msg);
    }

    // JSON 파싱/타입 불일치
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiErrorResponse> handleBadBody(Exception ex, HttpServletRequest req) {
        log.warn("400 Bad Request parse/type: method={} uri={} msg={}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "INVALID_JSON", "요청 본문을 파싱할 수 없습니다.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handle405(HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        Set<HttpMethod> supported = ex.getSupportedHttpMethods();

        log.warn("405 Method Not Allowed | method={} uri={} query={} supported={} ua=\"{}\" remote={}", req.getMethod(), req.getRequestURI(), req.getQueryString(), supported, req.getHeader("User-Agent"), req.getRemoteAddr());

        HttpHeaders headers = new HttpHeaders();
        if (supported != null && !supported.isEmpty()) {
            headers.setAllow(supported); // Allow: GET,POST,...
        }

        return new ResponseEntity<>(ApiErrorResponse.of(HttpStatus.METHOD_NOT_ALLOWED.name(), "지원하지 않는 HTTP 메서드입니다."), headers, HttpStatus.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGenericException(Exception ex, HttpServletRequest req) {
        log.error("Unexpected Error Occurred: method={} uri={} query={} ua=\"{}\" remote={} msg={}", req.getMethod(), req.getRequestURI(), req.getQueryString(), req.getHeader("User-Agent"), req.getRemoteAddr(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR.name(), "알 수 없는 서버 오류가 발생했습니다. 잠시 후 다시 시도해주세요."));
    }
}