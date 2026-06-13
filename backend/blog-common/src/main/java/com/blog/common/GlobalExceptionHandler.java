package com.blog.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import javax.validation.ConstraintViolationException;
import java.util.stream.Collectors;

/**
 * 全局异常处理
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException e) {
        log.warn("业务异常: code={} msg={}", e.getCode(), e.getMessage());
        return ResponseEntity.ok(Result.error(e.getCode(), e.getMessage()));
    }

    /** @Valid 参数校验失败 (RequestBody) */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", msg);
        return ResponseEntity.ok(Result.error(ResultCode.BAD_REQUEST.getCode(), msg));
    }

    /** @Valid 参数校验失败 (Form/Query) */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBind(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return ResponseEntity.ok(Result.error(ResultCode.BAD_REQUEST.getCode(), msg));
    }

    /** 单个参数校验失败 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraint(ConstraintViolationException e) {
        return ResponseEntity.ok(Result.error(ResultCode.BAD_REQUEST.getCode(), e.getMessage()));
    }

    /** 404 */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Result<Void>> handleNotFound(NoHandlerFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.error(ResultCode.NOT_FOUND));
    }

    /** 405 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Result.error(ResultCode.METHOD_NOT_ALLOWED));
    }

    /** 2026-06-12 新增：文件上传超 Spring multipart 限制（max-file-size / max-request-size）
     *  原 throw MaxUploadSizeExceededException → 500（兜底 catch），现在返 400 友好提示
     */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleUploadTooLarge(org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        log.warn("上传文件超过 Spring multipart 限制: {}", e.getMessage());
        return ResponseEntity.ok(Result.error(400, "上传文件过大，请压缩后重试（图片最大 5MB）"));
    }

    /** 2026-06-12 新增：缺少 multipart file 字段（required=false 但没传）
     *  Spring 抛 MissingServletRequestPartException，避免 500
     */
    @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException.class)
    public ResponseEntity<Result<Void>> handleMissingPart(org.springframework.web.multipart.support.MissingServletRequestPartException e) {
        log.warn("缺少 multipart 字段: {}", e.getMessage());
        return ResponseEntity.ok(Result.error(400, "缺少文件字段: " + e.getRequestPartName()));
    }

    /** 2026-06-12 新增：缺少 @RequestParam 必填参数
     *  Spring 抛 MissingServletRequestParameterException，避免 500
     */
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParam(org.springframework.web.bind.MissingServletRequestParameterException e) {
        log.warn("缺少参数: {}", e.getMessage());
        return ResponseEntity.ok(Result.error(400, "缺少参数: " + e.getParameterName()));
    }

    /** 2026-06-12 新增：参数类型错（?articleId=abc 这种）
     *  Spring 抛 MethodArgumentTypeMismatchException，避免 500
     */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException e) {
        log.warn("参数类型错: name={} value={}", e.getName(), e.getValue());
        return ResponseEntity.ok(Result.error(400, "参数 " + e.getName() + " 类型错误"));
    }

    /** 兜底 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleAny(Exception e) {
        log.error("未预期异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(ResultCode.INTERNAL_ERROR));
    }
}
