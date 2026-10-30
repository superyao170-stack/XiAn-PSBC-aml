"""Account-level binary metrics with strict labels and score-based ranking."""
from __future__ import annotations

import math
from collections import defaultdict


def binary(value, name):
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)) and value in (0, 1):
        return bool(value)
    raise ValueError(f"{name} must be a boolean or numeric 0/1")


def classification_metrics(rows):
    tp = fp = tn = fn = 0
    buckets = defaultdict(lambda: [0, 0])
    with_scores = 0
    for row in rows:
        if "actual" not in row or "predicted" not in row:
            raise ValueError("Every evaluation row requires actual and predicted")
        actual = binary(row["actual"], "actual")
        predicted = binary(row["predicted"], "predicted")
        if actual and predicted: tp += 1
        elif predicted: fp += 1
        elif actual: fn += 1
        else: tn += 1
        if "score" in row:
            score = float(row["score"])
            if not math.isfinite(score):
                raise ValueError("score must be finite")
            buckets[score][0 if actual else 1] += 1
            with_scores += 1
    n = tp + fp + tn + fn
    positives, negatives = tp + fn, tn + fp
    if with_scores not in (0, n):
        raise ValueError("Scores must be supplied for every row or no rows")
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / positives if positives else 0.0
    specificity = tn / negatives if negatives else 0.0
    auc = ap = None
    if with_scores and positives and negatives:
        negatives_below = 0
        concordant = 0.0
        for score in sorted(buckets):
            p, q = buckets[score]
            concordant += p * (negatives_below + q / 2)
            negatives_below += q
        auc = concordant / (positives * negatives)
        cumulative_p = cumulative_n = 0
        ap = 0.0
        for score in sorted(buckets, reverse=True):
            p, q = buckets[score]
            cumulative_p += p
            cumulative_n += q
            ap += (p / positives) * cumulative_p / (cumulative_p + cumulative_n)
    undefined = []
    if not n: undefined.append("accuracy")
    if not tp + fp: undefined.append("precision")
    if not positives: undefined.append("recall")
    if not negatives: undefined.append("specificity")
    if auc is None: undefined.extend(["rocAuc", "averagePrecision"])
    return {
        "sampleCount": n, "positiveCount": positives, "negativeCount": negatives,
        "truePositive": tp, "falsePositive": fp, "trueNegative": tn, "falseNegative": fn,
        "accuracy": (tp + tn) / n if n else None,
        "precision": precision, "recall": recall,
        "f1": 2 * precision * recall / (precision + recall) if precision + recall else 0.0,
        "specificity": specificity,
        "falsePositiveRate": fp / negatives if negatives else None,
        "balancedAccuracy": (recall + specificity) / 2 if positives and negatives else None,
        "illicitRatio": positives / n if n else None,
        "allNormalAccuracy": negatives / n if n else None,
        "rocAuc": auc, "averagePrecision": ap,
        "undefinedMetrics": undefined,
        "evaluationUnit": "ACCOUNT", "rankingInput": "CONTINUOUS_SCORE" if with_scores else "NOT_SUPPLIED",
    }
