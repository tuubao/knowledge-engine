package com.knowledge.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.transaction.support.TransactionTemplate;
import com.knowledge.engine.persistence.DocumentMapper;

import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;

/** 真实启动 Java HTTP 服务，以本地 HTTP 替身验证跨语言契约，绝不将替身结果视作模型验收。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiIntegrationTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final HttpServer PYTHON;
    static final AtomicInteger INDEX_CALLS=new AtomicInteger();
    static final AtomicInteger DELETE_CALLS=new AtomicInteger();
    static final AtomicReference<Map<String,Object>> LAST_QUERY=new AtomicReference<>();
    static final AtomicReference<String> BAD_HEADER=new AtomicReference<>();
    static final Map<String,AtomicInteger> INDEX_ATTEMPTS=new ConcurrentHashMap<>();
    static final Map<String,Set<String>> INDEX_VERSIONS=new ConcurrentHashMap<>();
    static final Set<String> DELETE_FAIL_ONCE=ConcurrentHashMap.newKeySet();
    static final CountDownLatch SLOW_INDEX_ENTERED=new CountDownLatch(1);
    static final CountDownLatch RELEASE_SLOW_INDEX=new CountDownLatch(1);
    static {
        try {
            PYTHON=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            PYTHON.setExecutor(Executors.newCachedThreadPool(r->{Thread t=new Thread(r);t.setDaemon(true);return t;}));
            PYTHON.createContext("/",ApiIntegrationTest::python);
            PYTHON.start();
        } catch(IOException e){throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->"jdbc:h2:mem:integration;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        registry.add("app.engine.base-url",()->"http://127.0.0.1:"+PYTHON.getAddress().getPort());
        registry.add("app.engine.service-key",()->"test-service-key");
        registry.add("app.upload-dir",()->{
            try{return Files.createTempDirectory("knowledge-java-test-").toString();}catch(IOException e){throw new IllegalStateException(e);}
        });
        registry.add("app.engine.stream-timeout-seconds",()->10);
    }
    @Autowired TestRestTemplate rest;
    @Autowired DocumentMapper documents;
    @Autowired TransactionTemplate tx;
    @LocalServerPort int port;
    @AfterAll static void stop(){RELEASE_SLOW_INDEX.countDown();PYTHON.stop(0);}

    @Test void authenticationAndOwnerIsolationCoverEveryResource() throws Exception {
        String a=account(),b=account();
        Map<String,Object> kb=createBase(a);String kbId=(String)kb.get("id");
        String session=(String)post(a,"/sessions",Map.of("knowledge_base_id",kbId,"title","隔离验证")).get("id");
        Map<String,Object> upload=upload(a,kbId,"owner-a-content");
        for(String path:List.of("/knowledge-bases/"+kbId,"/knowledge-bases/"+kbId+"/documents","/sessions/"+session,"/jobs/"+upload.get("job_id")))
            assertThat(call(b,HttpMethod.GET,path,null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(b,HttpMethod.POST,"/documents/"+upload.get("document_id")+"/retry",Map.of()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(b,HttpMethod.POST,"/knowledge-bases/"+kbId+"/search",Map.of("query","窃取数据")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(b,HttpMethod.POST,"/sessions/"+session+"/messages",Map.of("query","窃取数据")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        for(String path:List.of("/knowledge-bases/"+kbId,"/documents/"+upload.get("document_id"),"/sessions/"+session))
            assertThat(call(b,HttpMethod.DELETE,path,null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(null,HttpMethod.GET,"/knowledge-bases",null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(a,HttpMethod.GET,"/auth/me",null).getBody()).containsKeys("id","username","created_at").doesNotContainKey("password_hash");
    }
    @Test void concurrentDuplicateUploadsShareOneDocumentAndOneIndexJob() throws Exception {
        String token=account(),kbId=(String)createBase(token).get("id");int before=INDEX_CALLS.get();
        ExecutorService clients=Executors.newFixedThreadPool(6);
        try {
            List<Future<Map<String,Object>>> requests=new ArrayList<>();
            for(int i=0;i<6;i++)requests.add(clients.submit(()->upload(token,kbId,"concurrent-same-content")));
            Set<Object> ids=new HashSet<>(),jobIds=new HashSet<>();int newUploads=0;
            for(var request:requests){var result=request.get(10,TimeUnit.SECONDS);ids.add(result.get("document_id"));jobIds.add(result.get("job_id"));if(Boolean.FALSE.equals(result.get("duplicate")))newUploads++;}
            assertThat(ids).hasSize(1);assertThat(jobIds).hasSize(1);assertThat(newUploads).isEqualTo(1);
            await(()->"SUCCEEDED".equals(get(token,"/jobs/"+jobIds.iterator().next()).get("status")));
            assertThat(INDEX_CALLS.get()-before).isEqualTo(1);
        } finally { clients.shutdownNow(); }
    }
    @Test void duplicateUploadIndexesOnceAndDeletionRemovesAuthorization() throws Exception {
        String token=account(),kbId=(String)createBase(token).get("id");int before=INDEX_CALLS.get();
        Map<String,Object> first=upload(token,kbId,"digest-idempotency");
        Map<String,Object> duplicate=upload(token,kbId,"digest-idempotency");
        assertThat(duplicate.get("duplicate")).isEqualTo(true);assertThat(duplicate.get("document_id")).isEqualTo(first.get("document_id"));
        await(()->"SUCCEEDED".equals(get(token,"/jobs/"+first.get("job_id")).get("status")));
        assertThat(INDEX_CALLS.get()-before).isEqualTo(1);
        post(token,"/knowledge-bases/"+kbId+"/search",Map.of("query","检索授权"));
        List<?> allowed=(List<?>)LAST_QUERY.get().get("allowed_documents");assertThat(allowed).hasSize(1);
        assertThat(((Map<?,?>)allowed.get(0)).get("document_id")).isEqualTo(first.get("document_id"));
        assertThat(call(token,HttpMethod.DELETE,"/documents/"+first.get("document_id"),null).getStatusCode()).isEqualTo(HttpStatus.OK);
        post(token,"/knowledge-bases/"+kbId+"/search",Map.of("query","删除后检索"));
        assertThat((List<?>)LAST_QUERY.get().get("allowed_documents")).isEmpty();
        assertThat(BAD_HEADER.get()).isNull();
    }
    @Test void deletionDuringIndexNeverRevertsToReady() throws Exception {
        String token=account(),kbId=(String)createBase(token).get("id");
        Map<String,Object> doc=upload(token,kbId,"slow-index-race");
        assertThat(SLOW_INDEX_ENTERED.await(5,TimeUnit.SECONDS)).isTrue();
        assertThat(call(token,HttpMethod.DELETE,"/documents/"+doc.get("document_id"),null).getStatusCode()).isEqualTo(HttpStatus.OK);
        RELEASE_SLOW_INDEX.countDown();
        await(()->{
            var rows=rest.exchange("/api/v1/knowledge-bases/"+kbId+"/documents",HttpMethod.GET,entity(token,null),List.class).getBody();
            return rows!=null && rows.isEmpty();
        });
        assertThat(get(token,"/jobs/"+doc.get("job_id")).get("status")).isEqualTo("CANCELLED");
        post(token,"/knowledge-bases/"+kbId+"/search",Map.of("query","竞态检索"));
        assertThat((List<?>)LAST_QUERY.get().get("allowed_documents")).isEmpty();
    }
    @Test void failedIndexAndFailedDeletionCanRetryIdempotently() throws Exception {
        String token=account(),kbId=(String)createBase(token).get("id");
        Map<String,Object> first=upload(token,kbId,"index-fail-once delete-fail-once");String documentId=(String)first.get("document_id");
        await(()->"FAILED".equals(get(token,"/jobs/"+first.get("job_id")).get("status")));
        var retry=post(token,"/documents/"+documentId+"/retry",Map.of());
        assertThat(retry.get("document_id")).isEqualTo(documentId);assertThat(retry.get("job_id")).isNotEqualTo(first.get("job_id"));
        await(()->"SUCCEEDED".equals(get(token,"/jobs/"+retry.get("job_id")).get("status")));
        assertThat(INDEX_ATTEMPTS.get(documentId).get()).isEqualTo(2);assertThat(INDEX_VERSIONS.get(documentId)).hasSize(1);
        call(token,HttpMethod.DELETE,"/documents/"+documentId,null);
        await(()->{List<Map<String,Object>> rows=documentRows(token,kbId);return rows.size()==1 && "DELETE_FAILED".equals(rows.get(0).get("status"));});
        call(token,HttpMethod.DELETE,"/documents/"+documentId,null);
        await(()->documentRows(token,kbId).isEmpty());
        assertThat(call(token,HttpMethod.DELETE,"/documents/"+documentId,null).getBody().get("status")).isEqualTo("DELETED");
    }
    @Test void synchronousAnswerPassesAuthorizedHistoryAndPersistsIt() throws Exception {
        String token=account(),kbId=(String)createBase(token).get("id");
        String session=(String)post(token,"/sessions",Map.of("knowledge_base_id",kbId)).get("id");
        Map<String,Object> result=post(token,"/sessions/"+session+"/messages",Map.of("query","第一问"));
        assertThat(result).containsKeys("message_id","request_id","answer","citations","trace");
        assertThat((List<?>)LAST_QUERY.get().get("allowed_documents")).isEmpty();
        post(token,"/sessions/"+session+"/messages",Map.of("query","第二问"));
        assertThat((List<?>)LAST_QUERY.get().get("history")).hasSize(2);
        List<?> history=(List<?>)get(token,"/sessions/"+session).get("messages");assertThat(history).hasSize(4);
        assertThat(((Map<?,?>)history.get(3)).get("status")).isEqualTo("COMPLETE");
        assertThat((List<?>)((Map<?,?>)history.get(3)).get("candidates")).hasSize(1);
        assertThat((List<?>)((Map<?,?>)((Map<?,?>)history.get(3)).get("trace")).get("candidates")).hasSize(1);
    }
    @Test void sseEmitsExactlyOneTerminalAndCommitsBeforeDone() throws Exception {
        String token=account(),kbId=(String)createBase(token).get("id");
        String session=(String)post(token,"/sessions",Map.of("knowledge_base_id",kbId)).get("id");
        HttpResponse<String> response=HttpClient.newHttpClient().send(streamRequest(token,session,"正常流"),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(count(response.body(),"event:done")).isEqualTo(1);assertThat(response.body()).doesNotContain("event:error");
        assertThat(response.body()).contains("event:meta","event:retrieval","event:delta","\"request_id\":\"sse-test\"");
        List<?> history=(List<?>)get(token,"/sessions/"+session).get("messages");Map<?,?> last=(Map<?,?>)history.get(history.size()-1);
        assertThat(last.get("status")).isEqualTo("COMPLETE");assertThat(last.get("content")).isEqualTo("真实接口替身答案");
        assertThat(response.body()).contains("\"message_id\":\""+last.get("id")+"\"");
    }
    @Test void upstreamErrorAndMissingTerminalPersistPartialFailure() throws Exception {
        String token=account(),kbId=(String)createBase(token).get("id");
        for(String query:List.of("error-stream","abrupt-stream","bad-terminal")) {
            String session=(String)post(token,"/sessions",Map.of("knowledge_base_id",kbId)).get("id");
            var response=HttpClient.newHttpClient().send(streamRequest(token,session,query),HttpResponse.BodyHandlers.ofString());
            assertThat(count(response.body(),"event:error")).isEqualTo(1);assertThat(response.body()).doesNotContain("event:done");
            List<?> history=(List<?>)get(token,"/sessions/"+session).get("messages");Map<?,?> last=(Map<?,?>)history.get(1);
            assertThat(last.get("status")).isEqualTo("FAILED");assertThat(last.get("content")).isEqualTo("部分答案");
            assertThat(((Map<?,?>)last.get("trace")).containsKey("error")).isTrue();
            assertThat((List<?>)last.get("candidates")).hasSize(1);
            post(token,"/sessions/"+session+"/messages",Map.of("query","失败后的新问题"));
            assertThat((List<?>)LAST_QUERY.get().get("history")).isEmpty();
        }
    }
    @Test void validationErrorsDoNotCreatePendingTurnsAndDeletedBasesHideSessions() {
        String token=account(),kbId=(String)createBase(token).get("id");
        String session=(String)post(token,"/sessions",Map.of("knowledge_base_id",kbId)).get("id");
        for(Map<String,Object> invalid:List.of(Map.<String,Object>of("query"," "),Map.<String,Object>of("query","\u00a0"),Map.<String,Object>of("query","界".repeat(4001)),Map.<String,Object>of("query","校验","retrieval",Map.of("top_k",5,"candidate_k",2)),Map.<String,Object>of("query","校验","retrieval",Map.of("mode","all"))))
            assertThat(call(token,HttpMethod.POST,"/sessions/"+session+"/messages",invalid).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((List<?>)get(token,"/sessions/"+session).get("messages")).isEmpty();
        assertThat(call(token,HttpMethod.DELETE,"/knowledge-bases/"+kbId,null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(token,HttpMethod.GET,"/sessions/"+session,null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(token,HttpMethod.DELETE,"/knowledge-bases/"+kbId,null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    @Test void overlargeKnowledgeBaseIsRejectedBeforeCreatingMessages() {
        String token=account(),owner=(String)get(token,"/auth/me").get("id"),kbId=(String)createBase(token).get("id");
        String session=(String)post(token,"/sessions",Map.of("knowledge_base_id",kbId)).get("id");
        // 模拟旧数据导入造成的越界知识库，无需触发 1001 次无关的引擎调用。
        tx.executeWithoutResult(status->{for(int i=0;i<1001;i++){
            Models.Document doc=new Models.Document();doc.id=UUID.randomUUID().toString();doc.ownerId=owner;doc.knowledgeBaseId=kbId;
            doc.filename="fixture.md";doc.sha256=UUID.randomUUID().toString();doc.status="READY";doc.indexVersion="v1";
            doc.storagePath="fixture-not-a-real-upload";doc.createdAt=ResourceService.now();doc.chunkCount=1;documents.insert(doc);
        }});
        var response=call(token,HttpMethod.POST,"/sessions/"+session+"/messages",Map.of("query","范围限制"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("KNOWLEDGE_BASE_TOO_LARGE");
        assertThat((List<?>)get(token,"/sessions/"+session).get("messages")).isEmpty();
    }
    @Test void sparseModeAndLongHistoryRespectThePythonContract() {
        String token=account(),kbId=(String)createBase(token).get("id");
        post(token,"/knowledge-bases/"+kbId+"/search",Map.of("query","稀疏检索","retrieval",Map.of("mode","sparse")));
        assertThat(((Map<?,?>)LAST_QUERY.get().get("retrieval")).get("mode")).isEqualTo("sparse");
        String session=(String)post(token,"/sessions",Map.of("knowledge_base_id",kbId)).get("id");
        post(token,"/sessions/"+session+"/messages",Map.of("query","long-answer"));
        post(token,"/sessions/"+session+"/messages",Map.of("query","后续问题"));
        List<?> passed=(List<?>)LAST_QUERY.get().get("history");
        assertThat(((String)((Map<?,?>)passed.get(1)).get("content")).length()).isEqualTo(16000);
        List<?> saved=(List<?>)get(token,"/sessions/"+session).get("messages");
        assertThat(((String)((Map<?,?>)saved.get(1)).get("content")).length()).isEqualTo(17001);
    }
    @Test void disconnectCancelsUpstreamAndPersistsCancelled() throws Exception {
        String token=account(),kbId=(String)createBase(token).get("id");
        String session=(String)post(token,"/sessions",Map.of("knowledge_base_id",kbId)).get("id");
        var response=HttpClient.newHttpClient().send(streamRequest(token,session,"cancel-stream"),HttpResponse.BodyHandlers.ofInputStream());
        response.body().readNBytes(60);
        assertThat(call(token,HttpMethod.POST,"/sessions/"+session+"/messages",Map.of("query","并发问题")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(token,HttpMethod.DELETE,"/sessions/"+session,null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        response.body().close();
        await(()->{
            List<?> history=(List<?>)get(token,"/sessions/"+session).get("messages");
            return "CANCELLED".equals(((Map<?,?>)history.get(1)).get("status"));
        });
    }
    private String account() {
        Map<String,Object> result=post(null,"/auth/register",Map.of("username","u"+UUID.randomUUID().toString().replace("-", ""),"password","password123"));
        return (String)result.get("token");
    }
    private Map<String,Object> createBase(String token){return post(token,"/knowledge-bases",Map.of("name","接口测试知识库","description","隔离与契约"));}
    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> documentRows(String token,String kbId) {
        return rest.exchange("/api/v1/knowledge-bases/"+kbId+"/documents",HttpMethod.GET,entity(token,null),List.class).getBody();
    }
    private Map<String,Object> upload(String token,String kbId,String content) {
        MultiValueMap<String,Object> parts=new LinkedMultiValueMap<>();
        parts.add("file",new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)){@Override public String getFilename(){return "guide.md";}});
        HttpHeaders headers=headers(token);headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        var response=rest.exchange("/api/v1/knowledge-bases/"+kbId+"/documents",HttpMethod.POST,new HttpEntity<>(parts,headers),Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);return response.getBody();
    }
    private Map<String,Object> post(String token,String path,Object body) {
        var response=call(token,HttpMethod.POST,path,body);assertThat(response.getStatusCode()).as(response.toString()).isEqualTo(HttpStatus.OK);return response.getBody();
    }
    private Map<String,Object> get(String token,String path){var response=call(token,HttpMethod.GET,path,null);assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);return response.getBody();}
    private ResponseEntity<Map> call(String token,HttpMethod method,String path,Object body){return rest.exchange("/api/v1"+path,method,entity(token,body),Map.class);}
    private HttpEntity<?> entity(String token,Object body){return new HttpEntity<>(body,headers(token));}
    private HttpHeaders headers(String token){HttpHeaders h=new HttpHeaders();if(token!=null)h.setBearerAuth(token);return h;}
    private HttpRequest streamRequest(String token,String session,String query) throws Exception {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/sessions/"+session+"/messages/stream"))
            .header("Content-Type","application/json").header("Authorization","Bearer "+token).header("X-Request-Id","sse-test")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("query",query)))).build();
    }
    private static void await(Supplier<Boolean> condition)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        while(System.nanoTime()<until){if(condition.get())return;Thread.sleep(25);}
        assertThat(condition.get()).as("异步任务应在预算内完成").isTrue();
    }
    private int count(String value,String needle){return value.split(Pattern.quote(needle),-1).length-1;}
    private static void python(HttpExchange exchange) throws IOException {
        String path=exchange.getRequestURI().getPath();
        if(!path.startsWith("/health") && !"test-service-key".equals(exchange.getRequestHeaders().getFirst("X-Service-Key")))BAD_HEADER.set("missing service key");
        if(!path.startsWith("/health") && exchange.getRequestHeaders().getFirst("X-Request-Id")==null)BAD_HEADER.set("missing request id");
        String raw=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
        try {
            if(path.equals("/internal/v1/documents/index")) {
                INDEX_CALLS.incrementAndGet();
                if(!raw.contains("name=\"metadata\"")||!raw.contains("name=\"file\""))BAD_HEADER.set("invalid multipart");
                String documentId=field(raw,"document_id"),indexVersion=field(raw,"index_version");
                int attempt=INDEX_ATTEMPTS.computeIfAbsent(documentId,k->new AtomicInteger()).incrementAndGet();
                INDEX_VERSIONS.computeIfAbsent(documentId,k->ConcurrentHashMap.newKeySet()).add(indexVersion);
                if(raw.contains("delete-fail-once"))DELETE_FAIL_ONCE.add(documentId);
                if(raw.contains("index-fail-once")&&attempt==1){respond(exchange,503,Map.of("code","TEST_INDEX_FAILURE","message","模拟首次索引失败","retryable",true));return;}
                if(raw.contains("slow-index-race")){SLOW_INDEX_ENTERED.countDown();RELEASE_SLOW_INDEX.await(8,TimeUnit.SECONDS);}
                respond(exchange,Map.of("document_id",documentId,"index_version",indexVersion,"chunk_count",3,"trace",Map.of()));
            } else if(path.startsWith("/internal/v1/documents/")) {
                DELETE_CALLS.incrementAndGet();
                if(DELETE_FAIL_ONCE.remove(path.substring(path.lastIndexOf('/')+1))){respond(exchange,503,Map.of("code","TEST_DELETE_FAILURE","message","模拟首次删除失败","retryable",true));return;}
                respond(exchange,Map.of("status","deleted"));
            } else if(path.equals("/health/ready"))respond(exchange,Map.of("ready",true,"status","ready"));
            else {
                Map<String,Object> body=JSON.readValue(raw,new TypeReference<>(){});LAST_QUERY.set(body);
                if(!body.containsKey("allowed_documents"))BAD_HEADER.set("missing allowed_documents");
                List<Map<String,Object>> candidates=List.of(Map.of("chunk_id","test-chunk","content","用于测试候选快照保存","selected",false));
                String answerText="long-answer".equals(body.get("query"))?"长".repeat(17001):"真实接口替身答案";
                Map<String,Object> answer=new LinkedHashMap<>(Map.of("request_id","python-id","answer",answerText,"citations",List.of(),"candidates",candidates,"trace",Map.of("durations_ms",Map.of("retrieve",2))));
                if(path.endsWith("/stream")) {
                    exchange.getResponseHeaders().set("Content-Type","text/event-stream; charset=utf-8");exchange.sendResponseHeaders(200,0);
                    try(OutputStream output=exchange.getResponseBody()) {
                        frame(output,"meta",Map.of("request_id","python-id"));
                        frame(output,"retrieval",Map.of("request_id","python-id","citations",List.of(),"candidates",candidates,"trace",Map.of()));
                        String query=String.valueOf(body.get("query"));
                        if(query.equals("cancel-stream")){
                            for(int i=0;i<300;i++){frame(output,"delta",Map.of("request_id","python-id","delta","持续输出"));Thread.sleep(25);}return;
                        }
                        if(query.equals("error-stream")||query.equals("abrupt-stream")||query.equals("bad-terminal")) {
                            frame(output,"delta",Map.of("request_id","python-id","delta","部分答案"));
                            if(query.equals("error-stream"))frame(output,"error",Map.of("request_id","python-id","code","LLM_UNAVAILABLE","message","模型暂不可用","retryable",true));
                            if(query.equals("bad-terminal"))frame(output,"done",Map.of("request_id","python-id","answer","非法终态缺少引用与 trace"));
                        } else {frame(output,"delta",Map.of("request_id","python-id","delta","真实接口替身答案"));frame(output,"done",answer);}
                    }
                } else respond(exchange,answer);
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();exchange.close();}
        catch(Exception e){exchange.close();}
    }
    private static String field(String body,String name){var m=Pattern.compile("\\\""+name+"\\\":\\\"([^\\\"]+)\\\"").matcher(body);if(!m.find())throw new IllegalArgumentException(name);return m.group(1);}
    private static void respond(HttpExchange exchange,Map<String,Object> value)throws IOException {
        respond(exchange,200,value);
    }
    private static void respond(HttpExchange exchange,int status,Map<String,Object> value)throws IOException {
        byte[] bytes=JSON.writeValueAsBytes(value);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);
        try(OutputStream output=exchange.getResponseBody()){output.write(bytes);}
    }
    private static void frame(OutputStream output,String event,Object value)throws IOException {
        output.write(("event: "+event+"\ndata: "+JSON.writeValueAsString(value)+"\n\n").getBytes(StandardCharsets.UTF_8));output.flush();
    }
}
