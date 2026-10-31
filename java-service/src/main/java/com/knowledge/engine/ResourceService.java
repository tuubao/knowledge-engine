package com.knowledge.engine;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.knowledge.engine.Models.*;
import com.knowledge.engine.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;

@Service
public class ResourceService {
    final KnowledgeBaseMapper bases;
    final DocumentMapper documents;
    final SessionMapper sessions;
    final MessageMapper messages;
    private final TransactionTemplate tx;
    public ResourceService(KnowledgeBaseMapper bases, DocumentMapper documents, SessionMapper sessions,
                           MessageMapper messages, TransactionTemplate tx) {
        this.bases=bases; this.documents=documents; this.sessions=sessions; this.messages=messages; this.tx=tx;
    }
    public List<Map<String, Object>> listBases(String owner) {
        return bases.selectList(new QueryWrapper<KnowledgeBase>().eq("owner_id", owner).eq("deleted", false)
            .orderByDesc("created_at")).stream().map(this::baseView).toList();
    }
    public Map<String, Object> createBase(String owner, String name, String description) {
        validateName(name); validateDescription(description);
        KnowledgeBase base = new KnowledgeBase(); base.id=id(); base.ownerId=owner; base.name=name.trim();
        base.description=description==null ? "" : description; base.createdAt=now(); base.deleted=false;
        bases.insert(base); return baseView(base);
    }
    public Map<String, Object> updateBase(String owner, String id, String name, String description) {
        return tx.execute(s -> {
            KnowledgeBase base=ownBase(owner,id,true);
            if (name!=null) { validateName(name); base.name=name.trim(); }
            if (description!=null) { validateDescription(description); base.description=description; }
            bases.updateById(base); return baseView(base);
        });
    }
    KnowledgeBase ownBase(String owner, String id, boolean lock) {
        QueryWrapper<KnowledgeBase> query=new QueryWrapper<KnowledgeBase>().eq("id",id).eq("owner_id",owner).eq("deleted",false);
        if(lock) query.last("FOR UPDATE");
        KnowledgeBase base=bases.selectOne(query); if(base==null) throw ApiException.missing(); return base;
    }
    Document ownDocument(String owner, String id) {
        Document doc=documents.selectOne(new QueryWrapper<Document>().eq("id",id).eq("owner_id",owner));
        if(doc==null) throw ApiException.missing(); return doc;
    }
    Session ownSession(String owner, String id, boolean lock) {
        QueryWrapper<Session> q=new QueryWrapper<Session>().eq("id",id).eq("owner_id",owner);
        if(lock) q.last("FOR UPDATE");
        Session session=sessions.selectOne(q); if(session==null) throw ApiException.missing();
        ownBase(owner,session.knowledgeBaseId,false); return session;
    }
    public Map<String,Object> createSession(String owner,String baseId,String title) {
        return tx.execute(s -> {
            ownBase(owner,baseId,true);
            String value=title==null || title.isBlank() ? "新对话" : title.trim();
            if(value.length()>200) throw ApiException.invalid("会话标题不超过 200 字符");
            Session session=new Session(); session.id=id(); session.ownerId=owner; session.knowledgeBaseId=baseId;
            session.title=value; session.createdAt=now(); sessions.insert(session); return sessionView(session);
        });
    }
    public List<Map<String,Object>> listSessions(String owner,String baseId) {
        QueryWrapper<Session> q=new QueryWrapper<Session>().eq("owner_id",owner);
        if(baseId!=null && !baseId.isBlank()) { ownBase(owner,baseId,false); q.eq("knowledge_base_id",baseId); }
        Set<String> visible=new HashSet<>(bases.selectList(new QueryWrapper<KnowledgeBase>().eq("owner_id",owner)
            .eq("deleted",false)).stream().map(b->b.id).toList());
        return sessions.selectList(q.orderByDesc("created_at")).stream().filter(s->visible.contains(s.knowledgeBaseId))
            .map(this::sessionView).toList();
    }
    public void deleteSession(String owner,String id) {
        tx.executeWithoutResult(s -> {
            ownSession(owner,id,true);
            if(messages.selectCount(new QueryWrapper<Message>().eq("session_id",id).eq("status","PENDING"))>0)
                throw new ApiException(409,"SESSION_BUSY","请先停止当前回答后再删除会话",true);
            messages.delete(new QueryWrapper<Message>().eq("session_id",id)); sessions.deleteById(id);
        });
    }
    List<Map<String,Object>> allowed(String owner,String baseId) {
        ownBase(owner,baseId,false);
        List<Document> ready=documents.selectList(new QueryWrapper<Document>().eq("owner_id",owner).eq("knowledge_base_id",baseId)
            .eq("status","READY").last("LIMIT 1001"));
        if(ready.size()>1000)throw new ApiException(409,"KNOWLEDGE_BASE_TOO_LARGE","单次检索最多支持 1000 份文档，请拆分知识库后重试",false);
        return ready.stream().map(d->Map.<String,Object>of("document_id",d.id,"index_version",d.indexVersion)).toList();
    }
    Map<String,Object> baseView(KnowledgeBase b) {
        return Map.of("id",b.id,"name",b.name,"description",b.description,"created_at",b.createdAt,
            "document_count",documents.selectCount(new QueryWrapper<Document>().eq("knowledge_base_id",b.id).ne("status","DELETED")));
    }
    Map<String,Object> sessionView(Session s) {
        return new LinkedHashMap<>(Map.of("id",s.id,"knowledge_base_id",s.knowledgeBaseId,"title",s.title,"created_at",s.createdAt));
    }
    static String id() { return UUID.randomUUID().toString(); }
    static String now() { return Instant.now().toString(); }
    private void validateName(String name) { if(name==null || name.isBlank() || name.length()>100) throw ApiException.invalid("知识库名称须为 1–100 字符"); }
    private void validateDescription(String value) { if(value!=null && value.length()>2000) throw ApiException.invalid("知识库描述不超过 2000 字符"); }
}
