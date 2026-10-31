package com.knowledge.engine;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledge.engine.Models.*;
import com.knowledge.engine.persistence.MessageMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.knowledge.engine.ResourceService.*;

@Service
public class ChatService {
    private static final Logger log=LoggerFactory.getLogger(ChatService.class);
    private final ResourceService resources;
    private final MessageMapper messages;
    private final EngineClient engine;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final ThreadPoolExecutor streams;
    private final long timeout;
    public ChatService(ResourceService resources,MessageMapper messages,EngineClient engine,ObjectMapper json,
                       TransactionTemplate tx,@Value("${app.streams.workers}") int workers,
                       @Value("${app.streams.queue-capacity}") int capacity,
                       @Value("${app.engine.stream-timeout-seconds}") long seconds) {
        this.resources=resources; this.messages=messages; this.engine=engine; this.json=json; this.tx=tx; this.timeout=seconds*1000;
        streams=new ThreadPoolExecutor(workers,workers,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(capacity),
            runnable->{Thread t=new Thread(runnable,"answer-stream");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    }
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedMessages() {
        messages.update(null,new UpdateWrapper<Message>().eq("status","PENDING").set("status","FAILED")
            .set("trace_json",write(Map.of("error",Map.of("code","PROCESS_RESTARTED","message","服务重启中断了回答")))));
    }
    public Map<String,Object> session(String owner,String sessionId) {
        Session session=resources.ownSession(owner,sessionId,false);
        Map<String,Object> result=resources.sessionView(session);
        result.put("messages",messages.selectList(new QueryWrapper<Message>().eq("session_id",sessionId).orderByAsc("sequence_no"))
            .stream().map(this::messageView).toList()); return result;
    }
    public Map<String,Object> search(String owner,String baseId,String query,Map<String,Object> retrieval,String requestId) {
        validateQuery(query);
        Map<String,Object> body=body(baseId,query,List.of(),resources.allowed(owner,baseId),retrieval);
        Map<String,Object> result=new LinkedHashMap<>(engine.post("/internal/v1/retrieve",body,requestId));
        result.put("request_id",requestId); return result;
    }
    public Map<String,Object> answer(String owner,String sessionId,String query,Map<String,Object> retrieval,String requestId) {
        Turn turn=createTurn(owner,sessionId,query,retrieval,requestId);
        try {
            Map<String,Object> result=engine.post("/internal/v1/answer",turn.body,requestId);
            validateAnswer(result); persistSuccess(turn,result);
            Map<String,Object> response=new LinkedHashMap<>(result); response.put("request_id",requestId); response.put("message_id",turn.messageId); return response;
        } catch(RuntimeException e) {
            persistFailure(turn,"FAILED",error(e)); throw e;
        }
    }
    public SseEmitter stream(String owner,String sessionId,String query,Map<String,Object> retrieval,String requestId) {
        Turn turn=createTurn(owner,sessionId,query,retrieval,requestId);
        SseEmitter emitter=new SseEmitter(timeout);
        StreamState state=new StreamState(turn,emitter);
        emitter.onCompletion(state::cancel);
        emitter.onError(e->state.cancel());
        emitter.onTimeout(()->state.fail(new ApiException(504,"STREAM_TIMEOUT","流式回答超过时间预算",true)));
        try {
            Future<?> future=streams.submit(()->pump(state)); state.future.set(future);
            if(state.terminal.get()) future.cancel(true);
        } catch(RejectedExecutionException e) {
            ApiException busy=new ApiException(429,"STREAM_CAPACITY_EXCEEDED","并发回答已满，请稍后重试",true);
            state.terminal.set(true); persistFailure(turn,"FAILED",busy); throw busy;
        }
        return emitter;
    }
    private void pump(StreamState state) {
        try {
            InputStream upstream=engine.stream(state.turn.body,state.turn.requestId);
            state.input.set(upstream);
            if(state.terminal.get()) { upstream.close();return; }
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(upstream,StandardCharsets.UTF_8))) {
                String event="message";StringBuilder data=new StringBuilder();String line;
                while(!state.terminal.get() && (line=reader.readLine())!=null) {
                    if(line.isEmpty()) {
                        if(data.length()>0) { dispatch(state,event,data.toString()); }
                        event="message";data.setLength(0);
                    } else if(line.startsWith("event:")) event=line.substring(6).trim();
                    else if(line.startsWith("data:")) {
                        if(data.length()>0) data.append('\n');
                        String part=line.substring(5);data.append(part.startsWith(" ")?part.substring(1):part);
                        if(data.length()>2_000_000) throw new ApiException(502,"ENGINE_PROTOCOL_ERROR","SSE 事件超出大小限制",false);
                    } else if(line.startsWith(":")) { state.heartbeat(); }
                }
                if(!state.terminal.get() && data.length()>0) dispatch(state,event,data.toString());
                if(!state.terminal.get()) state.fail(new ApiException(502,"ENGINE_STREAM_INTERRUPTED","RAG 流未返回终态",true));
            }
        } catch(Exception e) {
            if(!state.terminal.get()) state.fail(error(e));
        } finally { state.closeInput(); }
    }
    private void dispatch(StreamState state,String event,String data) throws IOException {
        synchronized(state) {
        if(state.terminal.get())return;
        Map<String,Object> payload=new LinkedHashMap<>(engine.read(data));payload.put("request_id",state.turn.requestId);
        switch(event) {
            case "meta" -> { payload.put("message_id",state.turn.messageId); state.send(event,payload); }
            case "retrieval" -> {
                state.turn.citations=payload.getOrDefault("citations",List.of());
                state.turn.candidates=payload.getOrDefault("candidates",List.of());
                state.turn.trace=payload.getOrDefault("trace",Map.of());
                state.send(event,payload);
            }
            case "delta" -> {
                if(!(payload.get("delta") instanceof String delta)) throw new ApiException(502,"ENGINE_PROTOCOL_ERROR","delta 事件缺少文本",false);
                state.turn.partial.append(delta);
                if(state.turn.partial.length()>1_000_000) throw new ApiException(502,"ANSWER_TOO_LARGE","回答超出长度限制",false);
                state.send(event,payload);
            }
            case "done" -> state.done(payload);
            case "error" -> state.fail(new ApiException(502,String.valueOf(payload.getOrDefault("code","ENGINE_ERROR")),
                String.valueOf(payload.getOrDefault("message","RAG 流式回答失败")),Boolean.TRUE.equals(payload.get("retryable"))));
            default -> throw new ApiException(502,"ENGINE_PROTOCOL_ERROR","RAG 返回未知 SSE 事件",false);
        }
        }
    }
    private Turn createTurn(String owner,String sessionId,String query,Map<String,Object> retrieval,String requestId) {
        validateQuery(query);Map<String,Object> config=normalizeRetrieval(retrieval);
        return tx.execute(s->{
            Session session=resources.ownSession(owner,sessionId,true);
            if(messages.selectCount(new QueryWrapper<Message>().eq("session_id",sessionId).eq("status","PENDING"))>0)
                throw new ApiException(409,"SESSION_BUSY","同一会话已有回答正在生成",true);
            List<Message> past=messages.selectList(new QueryWrapper<Message>().eq("session_id",sessionId).orderByAsc("sequence_no"));
            List<Map<String,Object>> history=completedHistory(past);
            List<Map<String,Object>> allowed=resources.allowed(owner,session.knowledgeBaseId);
            long seq=past.isEmpty()?0:past.get(past.size()-1).sequenceNo;
            Message user=newMessage(sessionId,"user",query,"COMPLETE",requestId,seq+1);
            Message assistant=newMessage(sessionId,"assistant","","PENDING",requestId,seq+2);
            messages.insert(user);messages.insert(assistant);
            return new Turn(assistant.id,requestId,body(session.knowledgeBaseId,query,history,allowed,config));
        });
    }
    private List<Map<String,Object>> completedHistory(List<Message> past) {
        // 仅发送完整问答对，失败/取消轮次的 user 消息不能成为下一轮的孤立上下文。
        List<Map<String,Object>> history=new ArrayList<>();
        for(int i=1;i<past.size();i++) {
            Message previous=past.get(i-1),current=past.get(i);
            if("user".equals(previous.role) && "assistant".equals(current.role)
                && "COMPLETE".equals(previous.status) && "COMPLETE".equals(current.status)) {
                history.add(Map.of("role","user","content",historyContent(previous.content)));
                history.add(Map.of("role","assistant","content",historyContent(current.content)));
            }
        }
        return history.subList(Math.max(0,history.size()-20),history.size());
    }
    private String historyContent(String content) {
        // 单条历史遵守 Python 契约的 16000 字符预算；数据库仍保留完整原文用于复盘。
        int size=content.codePointCount(0,content.length());
        return size<=16000?content:content.substring(0,content.offsetByCodePoints(0,16000));
    }
    private Message newMessage(String sessionId,String role,String content,String status,String requestId,long sequence) {
        Message message=new Message();message.id=id();message.sessionId=sessionId;message.role=role;message.content=content;
        message.status=status;message.requestId=requestId;message.createdAt=now();message.sequenceNo=sequence;
        message.citationsJson="[]";message.traceJson="{}";return message;
    }
    private Map<String,Object> body(String baseId,String query,List<Map<String,Object>> history,List<Map<String,Object>> allowed,Map<String,Object> retrieval) {
        return Map.of("knowledge_base_id",baseId,"query",query,"history",history,"allowed_documents",allowed,"retrieval",normalizeRetrieval(retrieval));
    }
    private Map<String,Object> normalizeRetrieval(Map<String,Object> raw) {
        Map<String,Object> config=new LinkedHashMap<>(Map.of("mode","hybrid","top_k",5,"candidate_k",20,"use_rerank",true,"use_hyde",false,"use_web",false));
        if(raw!=null) for(var entry:raw.entrySet()) {
            if(!config.containsKey(entry.getKey())) throw ApiException.invalid("未知检索配置项: "+entry.getKey());
            config.put(entry.getKey(),entry.getValue());
        }
        if(!(config.get("mode") instanceof String mode) || !Set.of("dense","sparse","hybrid").contains(mode))
            throw ApiException.invalid("mode 只支持 dense、sparse 或 hybrid");
        for(String key:List.of("top_k","candidate_k")) {
            Object value=config.get(key);int max=key.equals("top_k")?20:100;
            if(!(value instanceof Number n) || n.doubleValue()!=n.intValue() || n.intValue()<1 || n.intValue()>max)
                throw ApiException.invalid(key+" 必须是 1–"+max+" 的整数");
        }
        if(((Number)config.get("candidate_k")).intValue()<((Number)config.get("top_k")).intValue())
            throw ApiException.invalid("candidate_k 不能小于 top_k");
        for(String key:List.of("use_rerank","use_hyde","use_web")) if(!(config.get(key) instanceof Boolean)) throw ApiException.invalid(key+" 必须为布尔值");
        return config;
    }
    private void validateQuery(String query) {
        if(query==null || query.codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c)||c==0x85)
            || query.codePointCount(0,query.length())>4000)throw ApiException.invalid("query 须为 1–4000 字符");
    }
    private void validateAnswer(Map<String,Object> payload) {
        if(!(payload.get("answer") instanceof String answer) || answer.length()>1_000_000
            || !(payload.get("citations") instanceof List<?>) || !(payload.get("candidates") instanceof List<?>)
            || !(payload.get("trace") instanceof Map<?,?>))
            throw new ApiException(502,"ENGINE_PROTOCOL_ERROR","回答响应缺少合法 answer/citations/candidates/trace",true);
    }
    private void persistSuccess(Turn turn,Map<String,Object> result) {
        validateAnswer(result);
        int changed=messages.update(null,new UpdateWrapper<Message>().eq("id",turn.messageId).eq("status","PENDING")
            .set("content",result.get("answer")).set("status","COMPLETE").set("citations_json",write(result.get("citations")))
            .set("trace_json",write(traceWithCandidates(result.get("trace"),result.get("candidates")))));
        if(changed!=1) throw new ApiException(409,"MESSAGE_STATE_CONFLICT","消息状态已发生变化",false);
    }
    private void persistFailure(Turn turn,String status,ApiException error) {
        Map<String,Object> trace=traceWithCandidates(turn.trace,turn.candidates);
        trace.put("error",error.payload(turn.requestId));
        messages.update(null,new UpdateWrapper<Message>().eq("id",turn.messageId).eq("status","PENDING")
            .set("content",turn.partial.toString()).set("status",status).set("citations_json",write(turn.citations)).set("trace_json",write(trace)));
    }
    private Map<String,Object> messageView(Message message) {
        Map<String,Object> result=new LinkedHashMap<>();result.put("id",message.id);result.put("role",message.role);
        result.put("content",message.content);result.put("status",message.status);result.put("citations",read(message.citationsJson,List.of()));
        Object trace=read(message.traceJson,Map.of());result.put("trace",trace);
        result.put("candidates",trace instanceof Map<?,?> map?map.getOrDefault("candidates",null):List.of());
        if(result.get("candidates")==null)result.put("candidates",List.of());
        result.put("request_id",message.requestId);result.put("created_at",message.createdAt);return result;
    }
    private Map<String,Object> traceWithCandidates(Object value,Object candidates) {
        Map<String,Object> trace=new LinkedHashMap<>();
        if(value instanceof Map<?,?> original)original.forEach((key,item)->trace.put(String.valueOf(key),item));
        trace.put("candidates",candidates instanceof List<?>?candidates:List.of());return trace;
    }
    private String write(Object value) { try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException(e);} }
    private Object read(String value,Object fallback) { if(value==null)return fallback;try{return json.readValue(value,Object.class);}catch(Exception e){return fallback;} }
    private ApiException error(Exception e) {
        if(e instanceof ApiException api) return api;
        log.warn("回答处理失败",e);return new ApiException(502,"ANSWER_FAILED","回答处理失败，请通过 request_id 排查日志",true);
    }
    private static class Turn {
        final String messageId,requestId;final Map<String,Object> body;final StringBuffer partial=new StringBuffer();
        volatile Object citations=List.of(),candidates=List.of(),trace=Map.of();
        Turn(String messageId,String requestId,Map<String,Object> body){this.messageId=messageId;this.requestId=requestId;this.body=body;}
    }
    private class StreamState {
        final Turn turn;final SseEmitter emitter;final AtomicBoolean terminal=new AtomicBoolean();
        final AtomicReference<InputStream> input=new AtomicReference<>();final AtomicReference<Future<?>> future=new AtomicReference<>();
        StreamState(Turn turn,SseEmitter emitter){this.turn=turn;this.emitter=emitter;}
        synchronized void send(String event,Object payload)throws IOException {
            if(terminal.get())return;
            try{emitter.send(SseEmitter.event().name(event).data(payload));}
            catch(IOException e){cancel();throw e;}
        }
        synchronized void heartbeat()throws IOException {
            if(terminal.get())return;
            try{emitter.send(SseEmitter.event().comment("heartbeat"));}
            catch(IOException e){cancel();throw e;}
        }
        synchronized void done(Map<String,Object> result) {
            if(!terminal.compareAndSet(false,true))return;
            try {
                // 必须先落库再给前端 done，避免界面显示完成而历史记录丢失。
                persistSuccess(turn,result);result.put("message_id",turn.messageId);
                emitter.send(SseEmitter.event().name("done").data(result));emitter.complete();
            } catch(Exception e) {
                ApiException api=error(e);
                try { persistFailure(turn,"FAILED",api);emitter.send(SseEmitter.event().name("error").data(api.payload(turn.requestId))); }
                catch(Exception ignored) { log.warn("request_id={} 无法发送流式终态",turn.requestId); }
                emitter.complete();
            } finally { closeInput(); }
        }
        synchronized void fail(ApiException error) {
            if(!terminal.compareAndSet(false,true))return;
            try { persistFailure(turn,"FAILED",error);emitter.send(SseEmitter.event().name("error").data(error.payload(turn.requestId))); }
            catch(Exception e){log.warn("request_id={} 流式失败终态发送失败",turn.requestId);}
            finally{emitter.complete();closeInput();cancelFuture();}
        }
        synchronized void cancel() {
            if(!terminal.compareAndSet(false,true))return;
            try{persistFailure(turn,"CANCELLED",new ApiException(499,"CLIENT_CANCELLED","客户端已取消回答",false));}
            catch(Exception e){log.warn("request_id={} 取消状态保存失败",turn.requestId,e);}
            finally{closeInput();cancelFuture();}
        }
        void closeInput(){InputStream stream=input.getAndSet(null);if(stream!=null)try{stream.close();}catch(IOException ignored){}}
        void cancelFuture(){Future<?> task=future.get();if(task!=null)task.cancel(true);}
    }
    @PreDestroy public void close(){streams.shutdownNow();}
}
