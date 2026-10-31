"""按文档相关性计算前 K 个片段的召回和首条相关结果排名。"""

from __future__ import annotations

import math
from collections.abc import Sequence


def retrieval_metrics(document_ids: Sequence[str], relevant_ids: Sequence[str], k: int) -> dict:
    if k < 1:
        raise ValueError("k 必须为正数")
    relevant = set(relevant_ids)
    if not relevant:
        return {"answerable": False, "document_recall_at_k": None, "document_mrr_at_k": None}
    prefix = list(document_ids[:k])
    recall = len(set(prefix) & relevant) / len(relevant)
    reciprocal_rank = next((1 / rank for rank, item in enumerate(prefix, 1) if item in relevant), 0.0)
    return {"answerable": True, "document_recall_at_k": recall, "document_mrr_at_k": reciprocal_rank}


def percentile(values: Sequence[float], q: float) -> float | None:
    if not values:
        return None
    if not 0 <= q <= 1:
        raise ValueError("分位数必须介于0与1之间")
    ordered = sorted(values)
    position = (len(ordered) - 1) * q
    lower, upper = math.floor(position), math.ceil(position)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)
