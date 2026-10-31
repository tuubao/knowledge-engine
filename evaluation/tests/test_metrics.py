"""验证指标语义，避免重复片段抬高文档召回。"""

import unittest

from evaluation.metrics import percentile, retrieval_metrics


class MetricsTests(unittest.TestCase):
    def test_repeated_document_does_not_inflate_recall(self):
        result = retrieval_metrics(["a", "a", "c"], ["a", "b"], 3)
        self.assertEqual(result["document_recall_at_k"], .5)
        self.assertEqual(result["document_mrr_at_k"], 1)

    def test_rank_and_truncation(self):
        self.assertEqual(retrieval_metrics(["x", "a"], ["a"], 2)["document_mrr_at_k"], .5)
        self.assertEqual(retrieval_metrics(["x", "a"], ["a"], 1)["document_recall_at_k"], 0)

    def test_unanswerable_is_not_a_recall_sample(self):
        self.assertIsNone(retrieval_metrics(["a"], [], 5)["document_recall_at_k"])

    def test_percentile_interpolation(self):
        self.assertEqual(percentile([10, 20, 30], .5), 20)
        self.assertAlmostEqual(percentile([10, 20], .95), 19.5)
        self.assertIsNone(percentile([], .95))


if __name__ == "__main__":
    unittest.main()
