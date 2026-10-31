package com.knowledge.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.io.IOException;
import java.util.UUID;

@Configuration
public class WebConfig {
    @Bean TransactionTemplate transactionTemplate(PlatformTransactionManager manager) { return new TransactionTemplate(manager); }
    @Bean OncePerRequestFilter requestFilter(AuthService auth, ObjectMapper json) {
        return new OncePerRequestFilter() {
            private final Logger log = LoggerFactory.getLogger("request");
            @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                String requestId = request.getHeader("X-Request-Id");
                if (requestId == null || !requestId.matches("[A-Za-z0-9._-]{1,128}")) requestId = UUID.randomUUID().toString();
                request.setAttribute(RequestContext.REQUEST_ID, requestId);
                response.setHeader("X-Request-Id", requestId);
                long start = System.nanoTime();
                try {
                    String path = request.getRequestURI();
                    boolean publicPath = path.equals("/api/v1/health") || path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/register");
                    if (path.startsWith("/api/") && !publicPath)
                        request.setAttribute(RequestContext.USER, auth.authenticate(request.getHeader("Authorization")));
                    chain.doFilter(request, response);
                } catch (ApiException e) {
                    response.setStatus(e.status); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
                    json.writeValue(response.getOutputStream(), e.payload(requestId));
                } finally {
                    log.info("request_id={} method={} path={} status={} duration_ms={}", requestId,
                        request.getMethod(), request.getRequestURI(), response.getStatus(), (System.nanoTime()-start)/1_000_000);
                }
            }
        };
    }
}

@RestControllerAdvice
class ErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);
    @ExceptionHandler(ApiException.class)
    org.springframework.http.ResponseEntity<?> api(ApiException e, HttpServletRequest request) {
        return org.springframework.http.ResponseEntity.status(e.status).contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(e.payload(id(request)));
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    org.springframework.http.ResponseEntity<?> large(Exception e, HttpServletRequest request) {
        return api(new ApiException(413, "FILE_TOO_LARGE", "上传文件超出大小限制", false), request);
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestPartException.class,
        MissingServletRequestParameterException.class, HttpMediaTypeNotSupportedException.class})
    org.springframework.http.ResponseEntity<?> bad(Exception e, HttpServletRequest request) {
        return api(ApiException.invalid("请求格式错误或缺少必要字段"), request);
    }
    @ExceptionHandler(NoHandlerFoundException.class)
    org.springframework.http.ResponseEntity<?> missing(Exception e,HttpServletRequest request){return api(ApiException.missing(),request);}
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    org.springframework.http.ResponseEntity<?> method(Exception e,HttpServletRequest request){return api(new ApiException(405,"METHOD_NOT_ALLOWED","请求方法不支持",false),request);}
    @ExceptionHandler(Exception.class)
    org.springframework.http.ResponseEntity<?> unexpected(Exception e, HttpServletRequest request) {
        log.error("request_id={} unhandled_error", id(request), e);
        return api(new ApiException(500, "INTERNAL_ERROR", "服务处理失败，请通过 request_id 排查日志", true), request);
    }
    private String id(HttpServletRequest request) {
        Object id = request.getAttribute(RequestContext.REQUEST_ID);
        return id == null ? UUID.randomUUID().toString() : id.toString();
    }
}
