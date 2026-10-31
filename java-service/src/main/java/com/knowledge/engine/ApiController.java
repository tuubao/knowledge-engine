package com.knowledge.engine;

import com.knowledge.engine.Models.User;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class ApiController {
    private final AuthService auth;
    private final ResourceService resources;
    private final DocumentService documents;
    private final ChatService chats;
    private final EngineClient engine;
    public ApiController(AuthService auth,ResourceService resources,DocumentService documents,ChatService chats,EngineClient engine) {
        this.auth=auth;this.resources=resources;this.documents=documents;this.chats=chats;this.engine=engine;
    }
    public record Credentials(String username,String password) {}
    public record BaseInput(String name,String description) {}
    // Jackson 2.14 对 record 构造参数的全局命名策略支持不完整，显式固定跨语言字段名。
    public record SessionInput(@JsonProperty("knowledge_base_id") String knowledgeBaseId,String title) {}
    public record QueryInput(String query,Map<String,Object> retrieval) {}
    @PostMapping("/auth/register") public Map<String,Object> register(@RequestBody Credentials input) { return auth.register(input.username,input.password); }
    @PostMapping("/auth/login") public Map<String,Object> login(@RequestBody Credentials input) { return auth.login(input.username,input.password); }
    @GetMapping("/auth/me") public Map<String,Object> me(HttpServletRequest request) { return AuthService.view(user(request)); }
    @GetMapping("/knowledge-bases") public List<Map<String,Object>> bases(HttpServletRequest request) { return resources.listBases(owner(request)); }
    @PostMapping("/knowledge-bases") public Map<String,Object> createBase(@RequestBody BaseInput input,HttpServletRequest request) {
        return resources.createBase(owner(request),input.name,input.description);
    }
    @GetMapping("/knowledge-bases/{id}") public Map<String,Object> base(@PathVariable String id,HttpServletRequest request) {
        return resources.baseView(resources.ownBase(owner(request),id,false));
    }
    @PatchMapping("/knowledge-bases/{id}") public Map<String,Object> patchBase(@PathVariable String id,@RequestBody BaseInput input,HttpServletRequest request) {
        return resources.updateBase(owner(request),id,input.name,input.description);
    }
    @DeleteMapping("/knowledge-bases/{id}") public Map<String,Object> deleteBase(@PathVariable String id,HttpServletRequest request) {
        documents.deleteBase(owner(request),id,requestId(request));return Map.of("status","DELETED");
    }
    @GetMapping("/knowledge-bases/{id}/documents") public List<Map<String,Object>> documents(@PathVariable String id,HttpServletRequest request) {
        return documents.list(owner(request),id);
    }
    @PostMapping(value="/knowledge-bases/{id}/documents",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,Object> upload(@PathVariable String id,@RequestPart("file") MultipartFile file,HttpServletRequest request) {
        return documents.upload(owner(request),id,file,requestId(request));
    }
    @GetMapping("/jobs/{id}") public Map<String,Object> job(@PathVariable String id,HttpServletRequest request) {return documents.getJob(owner(request),id);}
    @PostMapping("/documents/{id}/retry") public Map<String,Object> retry(@PathVariable String id,HttpServletRequest request) {
        return documents.retry(owner(request),id,requestId(request));
    }
    @DeleteMapping("/documents/{id}") public Map<String,Object> deleteDocument(@PathVariable String id,HttpServletRequest request) {
        return documents.delete(owner(request),id,requestId(request));
    }
    @GetMapping("/sessions") public List<Map<String,Object>> sessions(@RequestParam(name="knowledge_base_id",required=false) String baseId,HttpServletRequest request) {
        return resources.listSessions(owner(request),baseId);
    }
    @PostMapping("/sessions") public Map<String,Object> createSession(@RequestBody SessionInput input,HttpServletRequest request) {
        if(input.knowledgeBaseId==null || input.knowledgeBaseId.isBlank())throw ApiException.invalid("knowledge_base_id 必填");
        return resources.createSession(owner(request),input.knowledgeBaseId,input.title);
    }
    @GetMapping("/sessions/{id}") public Map<String,Object> session(@PathVariable String id,HttpServletRequest request) {return chats.session(owner(request),id);}
    @DeleteMapping("/sessions/{id}") public Map<String,Object> deleteSession(@PathVariable String id,HttpServletRequest request) {
        resources.deleteSession(owner(request),id);return Map.of("status","DELETED");
    }
    @PostMapping("/sessions/{id}/messages") public Map<String,Object> answer(@PathVariable String id,@RequestBody QueryInput input,HttpServletRequest request) {
        return chats.answer(owner(request),id,input.query,input.retrieval,requestId(request));
    }
    @PostMapping(value="/sessions/{id}/messages/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id,@RequestBody QueryInput input,HttpServletRequest request) {
        return chats.stream(owner(request),id,input.query,input.retrieval,requestId(request));
    }
    @PostMapping("/knowledge-bases/{id}/search") public Map<String,Object> search(@PathVariable String id,@RequestBody QueryInput input,HttpServletRequest request) {
        return chats.search(owner(request),id,input.query,input.retrieval,requestId(request));
    }
    @GetMapping("/health") public Map<String,Object> health() {
        Map<String,Object> ready=engine.health();
        boolean ok=Boolean.TRUE.equals(ready.get("reachable")) && ready.get("http_status") instanceof Number n && n.intValue()<300;
        return Map.of("status",ok?"UP":"DEGRADED","java",Map.of("status","UP"),"engine",ready);
    }
    private User user(HttpServletRequest request) {
        User user=(User)request.getAttribute(RequestContext.USER);
        if(user==null)throw new ApiException(401,"UNAUTHORIZED","请先登录",false);return user;
    }
    private String owner(HttpServletRequest request){return user(request).id;}
    private String requestId(HttpServletRequest request){return String.valueOf(request.getAttribute(RequestContext.REQUEST_ID));}
}
