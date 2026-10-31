package com.knowledge.engine;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.knowledge.engine.Models.*;
import com.knowledge.engine.persistence.DocumentMapper;
import com.knowledge.engine.persistence.JobMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

import static com.knowledge.engine.ResourceService.*;

@Service
public class DocumentService {
    private static final Logger log=LoggerFactory.getLogger(DocumentService.class);
    private final ResourceService resources;
    private final DocumentMapper documents;
    private final JobMapper jobs;
    private final EngineClient engine;
    private final TransactionTemplate tx;
    private final Path root;
    private final ThreadPoolExecutor executor;
    private final ReentrantLock[] locks=new ReentrantLock[64];
    public DocumentService(ResourceService resources, DocumentMapper documents, JobMapper jobs, EngineClient engine,
                           TransactionTemplate tx, @Value("${app.upload-dir}") String directory,
                           @Value("${app.jobs.workers}") int workers, @Value("${app.jobs.queue-capacity}") int capacity) throws Exception {
        this.resources=resources; this.documents=documents; this.jobs=jobs; this.engine=engine; this.tx=tx;
        this.root=Path.of(directory).toAbsolutePath().normalize(); Files.createDirectories(root);
        this.executor=new ThreadPoolExecutor(workers,workers,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(capacity),
            runnable -> { Thread t=new Thread(runnable,"document-job"); t.setDaemon(true); return t; },new ThreadPoolExecutor.AbortPolicy());
        Arrays.setAll(locks,i->new ReentrantLock());
    }
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedJobs() {
        // 单实例本地执行器不承诺进程重启续跑，持久化状态明确标成可重试。
        tx.executeWithoutResult(s -> {
            documents.update(null,new UpdateWrapper<Document>().in("status",List.of("PENDING","INDEXING"))
                .set("status","FAILED").set("error_message","服务重启中断了索引，请重试"));
            documents.update(null,new UpdateWrapper<Document>().eq("status","DELETING")
                .set("status","DELETE_FAILED").set("error_message","服务重启中断了删除，请重试删除"));
            jobs.update(null,new UpdateWrapper<Job>().in("status",List.of("PENDING","RUNNING"))
                .set("status","FAILED").set("error_message","服务重启中断了任务").set("updated_at",now()));
        });
    }
    public List<Map<String,Object>> list(String owner,String baseId) {
        resources.ownBase(owner,baseId,false);
        return documents.selectList(new QueryWrapper<Document>().eq("owner_id",owner).eq("knowledge_base_id",baseId)
            .ne("status","DELETED").orderByDesc("created_at")).stream().map(this::view).toList();
    }
    public Map<String,Object> upload(String owner,String baseId,MultipartFile file,String requestId) {
        resources.ownBase(owner,baseId,false);
        String filename=Optional.ofNullable(file.getOriginalFilename()).orElse("document.md").replace('\\','/');
        filename=filename.substring(filename.lastIndexOf('/')+1);
        if(file.isEmpty()) throw ApiException.invalid("不能上传空文件");
        if(filename.length()>255 || filename.contains("\r") || filename.contains("\n") ||
            !filename.toLowerCase(Locale.ROOT).matches(".+\\.(md|markdown|txt|pdf)"))
            throw ApiException.invalid("支持 .md、.markdown、.txt、.pdf 文件，文件名不超过 255 字符");
        String newId=id(); Path stored=root.resolve(newId+".upload").normalize();
        if(!stored.startsWith(root)) throw new IllegalStateException("上传目录越界");
        String digest;
        try (InputStream input=file.getInputStream(); OutputStream output=Files.newOutputStream(stored,StandardOpenOption.CREATE_NEW)) {
            MessageDigest sha=MessageDigest.getInstance("SHA-256"); byte[] bytes=new byte[8192]; int length;
            while((length=input.read(bytes))!=-1) { sha.update(bytes,0,length); output.write(bytes,0,length); }
            digest=HexFormat.of().formatHex(sha.digest());
        } catch(Exception e) { remove(stored); throw new ApiException(500,"UPLOAD_FAILED","文件保存失败",true); }
        String display=filename;
        Map<String,Object> result;
        try {
            result=tx.execute(s -> {
                // 锁定知识库后检查摘要，避免并发上传重复索引及与知识库删除竞争。
                resources.ownBase(owner,baseId,true);
                Document existing=documents.selectOne(new QueryWrapper<Document>().eq("knowledge_base_id",baseId).eq("sha256",digest));
                if(existing!=null && !existing.status.equals("DELETED")) return uploadView(existing,true);
                if(documents.selectCount(new QueryWrapper<Document>().eq("knowledge_base_id",baseId).ne("status","DELETED"))>=1000)
                    throw new ApiException(409,"KNOWLEDGE_BASE_DOCUMENT_LIMIT","每个知识库最多保存 1000 份有效文档，请拆分知识库或先删除旧文档",false);
                Document doc=existing==null ? new Document() : existing;
                if(existing==null) { doc.id=newId; doc.ownerId=owner; doc.knowledgeBaseId=baseId; doc.sha256=digest; doc.createdAt=now(); }
                doc.filename=display; doc.storagePath=stored.toString(); doc.status="PENDING"; doc.errorMessage=null;
                doc.indexVersion=id(); doc.chunkCount=0; doc.jobId=id();
                if(existing==null) documents.insert(doc); else {
                    documents.updateById(doc);
                    documents.update(null,new UpdateWrapper<Document>().eq("id",doc.id).set("error_message",null));
                }
                jobs.insert(job(doc,"INDEX")); return uploadView(doc,false);
            });
        } catch(RuntimeException e) { remove(stored); throw e; }
        if(Boolean.TRUE.equals(result.get("duplicate"))) remove(stored);
        else enqueue((String)result.get("document_id"),(String)result.get("job_id"),"INDEX",requestId);
        return result;
    }
    public Map<String,Object> retry(String owner,String documentId,String requestId) {
        Document found=resources.ownDocument(owner,documentId);
        Map<String,Object> result=tx.execute(s -> {
            resources.ownBase(owner,found.knowledgeBaseId,true);
            Document doc=resources.ownDocument(owner,documentId);
            if(!doc.status.equals("FAILED")) throw new ApiException(409,"DOCUMENT_STATE_CONFLICT","仅失败的索引可以重试",false);
            doc.status="PENDING"; doc.jobId=id();
            documents.update(null,new UpdateWrapper<Document>().eq("id",doc.id).set("status",doc.status)
                .set("job_id",doc.jobId).set("error_message",null));
            jobs.insert(job(doc,"INDEX")); return uploadView(doc,false);
        });
        enqueue(documentId,(String)result.get("job_id"),"INDEX",requestId); return result;
    }
    public Map<String,Object> delete(String owner,String documentId,String requestId) {
        Document found=resources.ownDocument(owner,documentId);
        Map<String,Object> result=tx.execute(s -> {
            // 已软删除的知识库仍允许资源所有者重试物理清理。
            KnowledgeBase base=resources.bases.selectOne(new QueryWrapper<KnowledgeBase>().eq("id",found.knowledgeBaseId)
                .eq("owner_id",owner).last("FOR UPDATE"));
            if(base==null)throw ApiException.missing();
            Document current=resources.ownDocument(owner,documentId);
            if(current.status.equals("DELETED") || current.status.equals("DELETING"))return Map.<String,Object>of("status",current.status,"enqueue",false);
            markDeleting(current);return Map.<String,Object>of("status",current.status,"enqueue",true,"job_id",current.jobId);
        });
        if(Boolean.TRUE.equals(result.get("enqueue")))enqueue(documentId,(String)result.get("job_id"),"DELETE",requestId);
        return Map.of("status",result.get("status"));
    }
    public void deleteBase(String owner,String baseId,String requestId) {
        List<Document> pending=tx.execute(s -> {
            // 重复删除仍可重试 DELETE_FAILED 文档，避免知识库软删除后丢失清理入口。
            KnowledgeBase base=resources.bases.selectOne(new QueryWrapper<KnowledgeBase>().eq("id",baseId)
                .eq("owner_id",owner).last("FOR UPDATE"));
            if(base==null)throw ApiException.missing();
            base.deleted=true; resources.bases.updateById(base);
            List<Document> docs=documents.selectList(new QueryWrapper<Document>().eq("knowledge_base_id",baseId)
                .notIn("status",List.of("DELETED","DELETING")));
            docs.forEach(this::markDeleting);return docs;
        });
        pending.forEach(doc->enqueue(doc.id,doc.jobId,"DELETE",requestId));
    }
    public Map<String,Object> getJob(String owner,String jobId) {
        Job job=jobs.selectOne(new QueryWrapper<Job>().eq("id",jobId).eq("owner_id",owner));
        if(job==null) throw ApiException.missing();
        Map<String,Object> map=new LinkedHashMap<>(); map.put("id",job.id); map.put("document_id",job.documentId);
        map.put("status",job.status); map.put("error_message",job.errorMessage); map.put("created_at",job.createdAt); map.put("updated_at",job.updatedAt); return map;
    }
    private void markDeleting(Document doc) {
        doc.status="DELETING"; doc.jobId=id();
        documents.update(null,new UpdateWrapper<Document>().eq("id",doc.id).set("status","DELETING").set("job_id",doc.jobId).set("error_message",null));
        jobs.insert(job(doc,"DELETE"));
    }
    private Job job(Document doc,String action) {
        Job job=new Job(); job.id=doc.jobId; job.documentId=doc.id; job.ownerId=doc.ownerId;
        job.action=action; job.status="PENDING"; job.createdAt=now(); job.updatedAt=job.createdAt; return job;
    }
    private void enqueue(String docId,String jobId,String action,String requestId) {
        try { executor.execute(()->run(docId,jobId,action,requestId)); }
        catch(RejectedExecutionException e) { fail(docId,jobId,action,"后台队列已满，请稍后重试"); }
    }
    private void run(String docId,String jobId,String action,String requestId) {
        // 同一文档的索引与删除串行；删除先改状态排除检索，再等待索引结束完成物理清理。
        ReentrantLock lock=locks[(docId.hashCode() & Integer.MAX_VALUE)%locks.length]; lock.lock();
        try {
            Document doc=documents.selectById(docId);
            String expected=action.equals("INDEX") ? "PENDING" : "DELETING";
            if(doc==null || !jobId.equals(doc.jobId) || !expected.equals(doc.status)) {
                finishJob(jobId,"CANCELLED","任务已被后续操作替代"); return;
            }
            finishJob(jobId,"RUNNING",null);
            if(action.equals("INDEX")) {
                documents.update(null,new UpdateWrapper<Document>().eq("id",docId).eq("job_id",jobId).eq("status","PENDING").set("status","INDEXING"));
                Map<String,Object> result=engine.index(doc,requestId);
                Object chunks=result.get("chunk_count");
                if(!(chunks instanceof Number) || ((Number)chunks).intValue()<0 || !docId.equals(result.get("document_id")) || !doc.indexVersion.equals(result.get("index_version")))
                    throw new ApiException(502,"ENGINE_PROTOCOL_ERROR","索引响应缺少文档标识、版本或合法片段数",false);
                int changed=documents.update(null,new UpdateWrapper<Document>().eq("id",docId).eq("job_id",jobId).eq("status","INDEXING")
                    .set("status","READY").set("chunk_count",((Number)chunks).intValue()).set("error_message",null));
                finishJob(jobId,changed==1 ? "SUCCEEDED" : "CANCELLED",changed==1 ? null : "索引完成期间文档已被删除");
            } else {
                engine.delete(doc,requestId);
                int changed=documents.update(null,new UpdateWrapper<Document>().eq("id",docId).eq("job_id",jobId).eq("status","DELETING")
                    .set("status","DELETED").set("error_message",null));
                if(changed==1) remove(Path.of(doc.storagePath));
                finishJob(jobId,changed==1 ? "SUCCEEDED" : "CANCELLED",null);
            }
            log.info("request_id={} document_id={} job_id={} action={} completed",requestId,docId,jobId,action);
        } catch(Exception e) {
            log.warn("request_id={} document_id={} job_id={} action={} failed",requestId,docId,jobId,action,e);
            fail(docId,jobId,action,e instanceof ApiException ? e.getMessage() : "任务执行失败，请通过 request_id 排查日志");
        } finally { lock.unlock(); }
    }
    private void fail(String docId,String jobId,String action,String message) {
        String clipped=message.length()>1900 ? message.substring(0,1900) : message;
        documents.update(null,new UpdateWrapper<Document>().eq("id",docId).eq("job_id",jobId)
            .set("status",action.equals("DELETE") ? "DELETE_FAILED" : "FAILED").set("error_message",clipped));
        finishJob(jobId,"FAILED",clipped);
    }
    private void finishJob(String jobId,String status,String message) {
        jobs.update(null,new UpdateWrapper<Job>().eq("id",jobId).set("status",status).set("error_message",message).set("updated_at",now()));
    }
    private Map<String,Object> uploadView(Document doc,boolean duplicate) {
        return Map.of("document_id",doc.id,"job_id",doc.jobId,"status",doc.status,"duplicate",duplicate);
    }
    private Map<String,Object> view(Document doc) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("id",doc.id); result.put("knowledge_base_id",doc.knowledgeBaseId); result.put("filename",doc.filename);
        result.put("status",doc.status); result.put("error_message",doc.errorMessage); result.put("index_version",doc.indexVersion);
        result.put("chunk_count",doc.chunkCount); result.put("created_at",doc.createdAt); result.put("job_id",doc.jobId); return result;
    }
    private void remove(Path path) { try { Files.deleteIfExists(path); } catch(Exception e) { log.warn("文件清理失败: {}",path); } }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
