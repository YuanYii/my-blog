package com.blog.common;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
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

    /** MDC key —— 与 TraceIdFilter / logback-spring.xml 对齐 */
    private static final String MDC_TRACE_ID = "traceId";

    /**
     * 业务异常（FR-4.1）：已知业务异常输出 WARN。
     * traceId 由 logback pattern 的 %X{traceId} 自动带上（FR-4.3），此处无需手工拼。
     */
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

    /** 2026-06-15 修复（BUG-NEW-5）：请求不带 multipart 头（如 form-data 缺 boundary）
     *  Spring 抛 MultipartException "Current request is not a multipart request" →
     *  之前进 @ExceptionHandler 兜底返 500 不友好；补 400 提示。
     *  触发场景：客户端用 fetch 但没设 Content-Type: multipart/form-data，或 curl 没 -F。
     */
    @ExceptionHandler(org.springframework.web.multipart.MultipartException.class)
    public ResponseEntity<Result<Void>> handleMultipart(org.springframework.web.multipart.MultipartException e) {
        log.warn("非 multipart 请求: {}", e.getMessage());
        return ResponseEntity.ok(Result.error(400, "请求格式错误：请用 multipart/form-data 上传文件"));
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

    /**
     * 兜底（FR-4.2/4.3）：未预期异常输出 ERROR（含完整 stacktrace），日志带 traceId（MDC pattern）。
     * 同时把 traceId 回写到响应 message，方便用户/owner 即使不看响应头也能复制编号定位（US-3）。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleAny(Exception e) {
        log.error("未预期异常", e);
        String traceId = MDC.get(MDC_TRACE_ID);
        String message = ResultCode.INTERNAL_ERROR.getMessage();
        if (traceId != null && !traceId.isEmpty()) {
            message = message + "（请反馈编号 traceId=" + traceId + "）";
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(ResultCode.INTERNAL_ERROR.getCode(), message));
    }
}
