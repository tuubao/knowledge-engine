package com.knowledge.engine;

import java.util.LinkedHashMap;
import java.util.Map;

public class ApiException extends RuntimeException {
    public final int status;
    public final String code;
    public final boolean retryable;
    public ApiException(int status, String code, String message, boolean retryable) {
        super(message); this.status = status; this.code = code; this.retryable = retryable;
    }
    public Map<String, Object> payload(String requestId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code); result.put("message", getMessage());
        result.put("request_id", requestId); result.put("retryable", retryable);
        return result;
    }
    public static ApiException missing() { return new ApiException(404, "NOT_FOUND", "资源不存在", false); }
    public static ApiException invalid(String message) { return new ApiException(400, "INVALID_REQUEST", message, false); }
}
