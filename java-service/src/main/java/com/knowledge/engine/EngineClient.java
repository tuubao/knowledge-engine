package com.knowledge.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 内部服务调用只传文件字节和授权范围，不暴露 Java 本机路径。 */
@Component
public class EngineClient {
    private final HttpClient http;
    private final ObjectMapper json;
    private final String baseUrl, serviceKey;
    private final long requestSeconds, ingestSeconds;
    public EngineClient(ObjectMapper json, @Value("${app.engine.base-url}") String baseUrl,
                        @Value("${app.engine.service-key}") String serviceKey,
                        @Value("${app.engine.connect-timeout-seconds}") long connectSeconds,
                        @Value("${app.engine.request-timeout-seconds}") long requestSeconds,
                        @Value("${app.engine.ingest-timeout-seconds}") long ingestSeconds) {
        this.json = json; this.baseUrl = baseUrl.replaceAll("/+$", ""); this.serviceKey = serviceKey;
        this.requestSeconds = requestSeconds; this.ingestSeconds = ingestSeconds;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(connectSeconds)).build();
    }
    public Map<String, Object> index(Models.Document doc, String requestId) {
        String boundary = "knowledge-" + UUID.randomUUID();
        try {
            String metadata = json.writeValueAsString(Map.of("knowledge_base_id", doc.knowledgeBaseId,
                "document_id", doc.id, "index_version", doc.indexVersion, "request_id", requestId));
            String filename = doc.filename.replace("\"", "_").replace("\r", "_").replace("\n", "_");
            byte[] before = ("--"+boundary+"\r\nContent-Disposition: form-data; name=\"metadata\"\r\nContent-Type: application/json\r\n\r\n"
                +metadata+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+filename
                +"\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
            HttpRequest request = builder("/internal/v1/documents/index", requestId, ingestSeconds)
                .header("Content-Type", "multipart/form-data; boundary="+boundary)
                .POST(HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(before),
                    HttpRequest.BodyPublishers.ofFile(Path.of(doc.storagePath)),
                    HttpRequest.BodyPublishers.ofString("\r\n--"+boundary+"--\r\n"))).build();
            return execute(request);
        } catch (IOException e) { throw new ApiException(500, "UPLOAD_FILE_UNAVAILABLE", "待索引文件不可用", false); }
    }
    public void delete(Models.Document doc, String requestId) {
        execute(builder("/internal/v1/documents/"+encode(doc.id)+"?knowledge_base_id="+encode(doc.knowledgeBaseId)
            +"&index_version="+encode(doc.indexVersion), requestId, ingestSeconds).DELETE().build());
    }
    public Map<String, Object> post(String path, Map<String, Object> body, String requestId) {
        return execute(jsonRequest(path, body, requestId));
    }
    public InputStream stream(Map<String, Object> body, String requestId) {
        try {
            HttpResponse<InputStream> response = http.send(jsonRequest("/internal/v1/answer/stream", body, requestId), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                try (InputStream input = response.body()) {
                    throw engineError(response.statusCode(), new String(input.readNBytes(32_768), StandardCharsets.UTF_8));
                }
            }
            String contentType = response.headers().firstValue("content-type").orElse("");
            if (!contentType.contains("text/event-stream")) {
                response.body().close(); throw new ApiException(502, "ENGINE_PROTOCOL_ERROR", "RAG 引擎未返回 SSE 数据", true);
            }
            return response.body();
        } catch (HttpTimeoutException e) { throw timeout(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch (IOException e) { throw unavailable(); }
    }
    public Map<String, Object> health() {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(baseUrl+"/health/ready"))
                .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Map<String, Object> result = new LinkedHashMap<>(read(response.body()));
            result.put("http_status", response.statusCode());
            result.put("reachable", true); return result;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return Map.of("status", "DOWN", "reachable", false);
        }
    }
    private HttpRequest jsonRequest(String path, Map<String, Object> body, String requestId) {
        try { return builder(path, requestId, requestSeconds).header("Content-Type", "application/json")
            .header("Accept", path.endsWith("/stream") ? "text/event-stream" : "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8)).build(); }
        catch (IOException e) { throw new IllegalArgumentException(e); }
    }
    private HttpRequest.Builder builder(String path, String requestId, long timeoutSeconds) {
        if (serviceKey.isBlank()) throw new ApiException(503, "ENGINE_NOT_CONFIGURED", "请配置 Java 与 Python 一致的 RAG_SERVICE_KEY", false);
        return HttpRequest.newBuilder(URI.create(baseUrl+path)).timeout(Duration.ofSeconds(timeoutSeconds))
            .header("X-Service-Key", serviceKey).header("X-Request-Id", requestId);
    }
    private Map<String, Object> execute(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) throw engineError(response.statusCode(), response.body());
            if (response.body().isBlank()) return Map.of();
            return read(response.body());
        } catch (HttpTimeoutException e) { throw timeout(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch (IOException e) { throw unavailable(); }
    }
    public Map<String, Object> read(String value) {
        try {
            Map<String,Object> result=json.readValue(value, new TypeReference<LinkedHashMap<String, Object>>(){});
            if(result==null)throw new IOException("JSON 对象不能为空");
            return result;
        }
        catch (IOException e) { throw new ApiException(502, "ENGINE_PROTOCOL_ERROR", "RAG 引擎返回无效 JSON", true); }
    }
    private ApiException engineError(int status, String body) {
        Map<String, Object> error;
        try { error = read(body); } catch (Exception e) { error = Map.of(); }
        int outward = status == 400 || status == 422 ? 400 : status == 429 ? 429 : status == 503 ? 503 : status == 504 ? 504 : 502;
        return new ApiException(outward, String.valueOf(error.getOrDefault("code", "ENGINE_ERROR")),
            String.valueOf(error.getOrDefault("message", "RAG 引擎调用失败")), outward != 400);
    }
    private ApiException timeout() { return new ApiException(504, "ENGINE_TIMEOUT", "RAG 引擎响应超时", true); }
    private ApiException unavailable() { return new ApiException(503, "ENGINE_UNAVAILABLE", "RAG 引擎暂不可用", true); }
    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
