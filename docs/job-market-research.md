# 2025–2026 AI 应用工程岗位调研

检索日期：2026-10-31。目的：根据真实岗位要求，为当前已有混合检索/HyDE/rerank 的项目选择少量工程化增量。

这是一组公开 JD 样本，不是全市场统计；下述面试主题是据 JD 与源码推导的准备建议，不是公司原题或经过计数的高频排名。高校就业网站属于招聘信息发布渠道，来源与发布日期按页面记录，不把抓取日期当作岗位发布日期。

## 1. 岗位证据

| 岗位与来源 | 时间与证据边界 | 要求摘要 | 对项目的启示 |
| --- | --- | --- | --- |
| 朗新 J15275，算法工程师（RAG、多模态等） | 官方页搜索索引显示 2025-02-27；动态详情无法直接读取，日期与正文仅由官方页索引支持 | RAG 子方向关注 Python/Java、文档数据工程、向量化、混合检索、重排和业务性能指标 | 保留既有检索栈，并说明两种语言的职责边界 |
| 中科金财，大模型应用开发工程师 | 河北工业大学就业网，2025-08-27；日期与正文可直接读取 | Python、部署、测试数据、Agent/RAG 评估与技术文档 | 补齐可重复执行的评测集、部署和验证步骤 |
| 涌现工场，AI 应用工程师 / RAG 工程师 | 天津工业大学就业网，2026-05-11；日期与正文可直接读取 | Spring Boot 业务集成；解析/切片/Embedding/混合检索/Rerank/生成/评估；流式与观测 | Java 主服务 + Python 引擎符合该样本需求；检索证据与耗时应能展示 |
| 中关村科金，大模型应用工程师 | 南开大学就业网，2026-10-28；日期与正文可直接读取 | Python、召回优化、rerank/fallback、准确率、稳定性、延迟和成本 | 用明确的超时、降级和实验结果解释工程权衡 |

岗位来源：[朗新官方招聘](https://longshine.zhiye.com/social/jobs)、[中科金财招聘公告](https://career.hebut.edu.cn/home/correcruit/content/id/67729.html)、[涌现工场招聘公告](https://jobs.tiangong.edu.cn/correcruit/content/id/54911.html)、[中关村科金招聘公告](https://career.nankai.edu.cn/correcruit/content/id/120190.html)。

## 2. 选择两项增量亮点

### A. 可解释检索与跨服务观测

保留稳定 `document_id/chunk_id`，输出来源、标题/页码（有才返回）、命中的通道、各通道排名、融合分数、rerank 分数与阶段耗时。Vue 展示重排前后变化和证据正文；Java/Python 使用同一 `request_id` 关联日志。

这能回答“为什么检索到它、为什么最后选它、慢在哪里”。不同检索器分数不可直接横向比较，RRF 分数也不是概率。

第一版若只实现关联 ID、结构化日志与耗时，应明确称为轻量可观测性。完整 OpenTelemetry tracing 需要 context propagation 与 spans，不能仅改日志名就宣称已实现。[OpenTelemetry 官方 Traces 概念](https://opentelemetry.io/docs/concepts/signals/traces/)

### B. 离线评测与消融实验

使用可追溯的人工标注相关文档/片段 ID，对比 dense、dense+sparse、+rerank、+HyDE；保持相同语料、问题和数据版本。输出 Recall@K、MRR@K、样本数、平均/P50/P95 耗时及实验参数；无答案问题单独检查拒答行为，不混入有相关文档的召回分母。

第一版先做检索评测，不强制引入收费 LLM judge。相关上下文/参考答案是评测依据，检索召回与最终回答忠实性是两个问题。[Ragas 官方 Context Recall](https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/context_recall/)

评测数据量和来源必须公开；示例数据结果只用于回归和演示，不能外推为生产准确率。候选模式未运行、模型未就绪时应标为未测，不能用 mock 分数冒充真实效果。

## 3. 面试准备主题及可展示证据

| 准备主题 | 项目中应拿出的证据 |
| --- | --- |
| Java/Python 为什么分开，如何避免重复管理业务？ | 架构图、数据库归属、版本化接口、错误码和请求链路 |
| dense/sparse、加权融合、RRF 与 rerank 有什么不同？ | 原代码保留说明、候选列表、排名变化及消融结果 |
| chunk/top-k/上下文预算如何影响效果？ | 固定语料和问题的实验配置、Recall/MRR 与延迟 |
| HyDE 是否值得增加一次 LLM 调用？ | 有/无 HyDE 的 bad case、耗时和质量比较 |
| 重复上传、删除、失败重试如何一致？ | 文档状态机、稳定 ID、幂等测试、删除与重试演示 |
| SSE 失败、服务不可用和用户越权怎么处理？ | 终止事件、超时/降级记录、权限隔离测试 |

## 4. Java 选型核验

- Spring Boot **3.0.5** 是用户指定版本，发布于 **2023-03-23**，不描述为 2026 最新版本。[官方发布公告](https://spring.io/blog/2023/03/23/spring-boot-3-0-5-available-now/)
- 该版本要求 Java **17** 起、兼容至 Java 20，Maven 3.5+；当前机器的 Java 17/Maven 3.9.11 满足该基础要求。[3.0.5 官方系统要求](https://docs.spring.io/spring-boot/docs/3.0.5/reference/html/getting-started.html#getting-started.system-requirements)
- Boot 3 使用 `com.baomidou:mybatis-plus-spring-boot3-starter`，不混装 Boot 2 starter 或重复的 MyBatis starter。[MyBatis-Plus 官方安装说明](https://baomidou.com/en/getting-started/install/)
- 可将 MyBatis-Plus **3.5.5** 作为待验证候选；该版本 starter 源码使用 Java 17 与 mybatis-spring 3.0.3。仍需实际启动和 Mapper 集成验证，不能由“都是 Boot 3”推断组合一定可用。[官方 v3.5.5 starter 源码](https://raw.githubusercontent.com/baomidou/mybatis-plus/v3.5.5/spring-boot-starter/mybatis-plus-spring-boot3-starter/build.gradle)
