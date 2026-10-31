package com.knowledge.engine;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 业务表仅保存在 Java；Python 只接收已授权的检索范围。 */
public final class Models {
    private Models() {}
    @TableName("app_users") public static class User {
        @TableId public String id;
        public String username, passwordHash, createdAt;
    }
    @TableName("auth_tokens") public static class Token {
        @TableId public String id;
        public String userId, expiresAt;
    }
    @TableName("knowledge_bases") public static class KnowledgeBase {
        @TableId public String id;
        public String ownerId, name, description, createdAt;
        public Boolean deleted;
    }
    @TableName("documents") public static class Document {
        @TableId public String id;
        public String ownerId, knowledgeBaseId, filename, sha256, status, errorMessage;
        public String indexVersion, storagePath, jobId, createdAt;
        public Integer chunkCount;
    }
    @TableName("ingestion_jobs") public static class Job {
        @TableId public String id;
        public String documentId, ownerId, action, status, errorMessage, createdAt, updatedAt;
    }
    @TableName("chat_sessions") public static class Session {
        @TableId public String id;
        public String ownerId, knowledgeBaseId, title, createdAt;
    }
    @TableName("chat_messages") public static class Message {
        @TableId public String id;
        public String sessionId, role, content, status, citationsJson, traceJson, requestId, createdAt;
        public Long sequenceNo;
    }
}
