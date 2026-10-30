import copy
import json
import threading
import unittest
from http.server import ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from app import Handler, evaluate
from aml_metrics import classification_metrics
from collaborative_aml import BloomFilter, detect_collaborative_aml


def graph(width=6):
    accounts = [{"id":"SOURCE","institution":"A"},{"id":"DEST","institution":"B"}]
    transactions = []
    for i in range(width):
        accounts.extend([{"id":f"A{i}","institution":"A"},{"id":f"B{i}","institution":"B"}])
        for source, target in [("SOURCE",f"A{i}"),(f"A{i}",f"B{i}"),(f"B{i}","DEST")]:
            tid = f"T{len(transactions)}"
            transactions.append({"transactionId":tid,"source":source,"target":target,"amount":200,
                                 "occurredAt":"2026-09-01T08:00:00+08:00","evidenceRef":tid})
    accounts.append({"id":"NORMAL","institution":"A"})
    return {"accounts":accounts,"transactions":transactions,"bloomBits":10000,"bands":50,"rows":2}


class CollaborativeAmlTest(unittest.TestCase):
    def test_recovers_cross_bank_endpoints_and_intermediaries_with_local_evidence(self):
        result = detect_collaborative_aml(graph())
        decisions = {x["subjectId"]:x["decision"] for x in result["evidence"]}
        self.assertEqual("NORMAL", decisions["NORMAL"])
        self.assertEqual(14, sum(x == "SUSPECTED" for x in decisions.values()))
        self.assertEqual(2, len(result["candidates"]))
        for candidate in result["candidates"]:
            self.assertEqual(6, candidate["boundaryPairCount"])
            self.assertEqual(12, len(candidate["transactionIds"]))
            self.assertEqual(6, len(candidate["boundaryTransactionIds"]))
            self.assertEqual(12, len(candidate["evidenceRefs"]))
            self.assertEqual(1.0, candidate["score"])

    def test_does_not_use_embedded_labels_or_depend_on_order(self):
        payload = graph()
        before = detect_collaborative_aml(payload)
        for item in payload["accounts"]: item["label"] = 1
        payload["accounts"].reverse();payload["transactions"].reverse()
        after = detect_collaborative_aml(payload)
        self.assertEqual(before, after)

    def test_reverse_traversal_preserves_original_boundary_transaction_identity(self):
        result = detect_collaborative_aml(graph())
        first, second = result["candidates"]
        self.assertEqual(first["boundaryTransactionIds"], second["boundaryTransactionIds"])

    def test_no_gather_partner_produces_no_alarm(self):
        payload = graph()
        payload["transactions"] = [x for x in payload["transactions"] if x["target"] != "DEST"]
        self.assertEqual(0, detect_collaborative_aml(payload)["summary"]["suspectedAccounts"])

    def test_small_sets_are_excluded_and_exclusion_is_visible(self):
        result = detect_collaborative_aml(graph(3))
        self.assertEqual(0, result["summary"]["eligibleEndpointSets"])
        self.assertEqual(0, result["summary"]["suspectedAccounts"])

    def test_exact_control_and_sim_agree_on_identical_sets(self):
        payload = graph()
        sim = detect_collaborative_aml(payload)
        exact = detect_collaborative_aml({**payload,"method":"EXACT_JACCARD"})
        self.assertEqual(sim["evidence"], exact["evidence"])

    def test_depth_budget_and_cycles_terminate(self):
        payload = graph()
        shallow = detect_collaborative_aml({**payload,"maxDepth":1})
        self.assertEqual(0, shallow["summary"]["suspectedAccounts"])
        payload["transactions"].append({"transactionId":"CYCLE","source":"A0","target":"SOURCE","amount":100})
        self.assertEqual(14, detect_collaborative_aml(payload)["summary"]["suspectedAccounts"])

    def test_repeated_transactions_are_aggregated_before_amount_filtering(self):
        payload = graph()
        extra = []
        for tx in payload["transactions"]:
            tx["amount"] = 60
            extra.append({**tx,"transactionId":tx["transactionId"]+"-REPEAT"})
        payload["transactions"].extend(extra)
        result = detect_collaborative_aml({**payload,"minAmount":100})
        self.assertEqual(18, result["preprocessing"]["retainedPairCount"])
        self.assertEqual(14, result["summary"]["suspectedAccounts"])

    def test_window_is_half_open_and_timezone_aware(self):
        payload = graph()
        payload.update(windowStart="2026-09-01T00:00:00Z",windowEnd="2026-09-02T00:00:00Z")
        self.assertEqual(18, detect_collaborative_aml(payload)["preprocessing"]["retainedPairCount"])
        payload["windowEnd"] = "2026-09-01T00:00:00Z"
        with self.assertRaises(ValueError): detect_collaborative_aml(payload)
        payload["windowEnd"] = "2026-09-01T00:00:01Z"
        self.assertEqual(18, detect_collaborative_aml(payload)["preprocessing"]["retainedPairCount"])
        payload["windowStart"] = "2026-08-31T00:00:00Z"
        payload["windowEnd"] = "2026-09-01T00:00:00Z"
        self.assertEqual(0, detect_collaborative_aml(payload)["preprocessing"]["retainedPairCount"])
        payload["windowStart"] = "2026-09-01T00:00:00"
        with self.assertRaises(ValueError): detect_collaborative_aml(payload)

    def test_duplicate_id_missing_bank_unknown_account_and_invalid_parameters_fail(self):
        variants = []
        p=graph();p["transactions"].append(copy.deepcopy(p["transactions"][0]));variants.append(p)
        p=graph();p["accounts"][0].pop("institution");variants.append(p)
        p=graph();p["transactions"][0]["source"]="UNKNOWN";variants.append(p)
        p=graph();p["accounts"].append(copy.deepcopy(p["accounts"][0]));variants.append(p)
        p=graph();p["transactions"][0]["currency"]="USD";variants.append(p)
        for changes in [{"rows":0},{"threshold":float("nan")},{"threshold":0},{"maxDepth":10},
                        {"bloomBits":3000000,"bands":256},{"minAmount":-1},{"rows":2.5}]:
            variants.append({**graph(),**changes})
        for payload in variants:
            with self.subTest(payload=str(payload)[-150:]):
                with self.assertRaises(ValueError): detect_collaborative_aml(payload)

    def test_bloom_has_no_false_negatives_for_inserted_items(self):
        bloom=BloomFilter(8192,7)
        for i in range(100):bloom.add(str(i))
        self.assertTrue(all(bloom.contains(str(i)) for i in range(100)))


class MetricsTest(unittest.TestCase):
    def test_confusion_matrix_and_metrics_are_independently_known(self):
        rows=[{"actual":1,"predicted":1,"score":.9},{"actual":0,"predicted":1,"score":.8},
              {"actual":1,"predicted":0,"score":.7},{"actual":0,"predicted":0,"score":.1}]
        m=classification_metrics(rows)
        self.assertEqual([1,1,1,1],[m[k] for k in ["truePositive","falsePositive","trueNegative","falseNegative"]])
        self.assertEqual(.5,m["accuracy"]);self.assertEqual(.5,m["f1"])
        self.assertEqual(.75,m["rocAuc"])
        self.assertAlmostEqual(5/6,m["averagePrecision"])

    def test_tied_scores_have_auc_half_and_ap_prevalence(self):
        m=classification_metrics([{"actual":i%2,"predicted":0,"score":0} for i in range(10)])
        self.assertEqual(.5,m["rocAuc"]);self.assertEqual(.5,m["averagePrecision"])

    def test_high_accuracy_all_normal_baseline_has_zero_recall(self):
        m=classification_metrics([{"actual":int(i==0),"predicted":0} for i in range(1000)])
        self.assertEqual(.999,m["accuracy"]);self.assertEqual(0,m["recall"])

    def test_missing_and_string_labels_fail_instead_of_becoming_true(self):
        for row in [{"actual":"0","predicted":0},{"predicted":0},{"actual":0,"predicted":"false"}]:
            with self.assertRaises(ValueError): evaluate({"rows":[row]})

    def test_empty_single_class_and_partial_scores_are_reported(self):
        self.assertIsNone(classification_metrics([])["accuracy"])
        self.assertIsNone(classification_metrics([{"actual":0,"predicted":0,"score":0}])["rocAuc"])
        with self.assertRaises(ValueError):
            classification_metrics([{"actual":0,"predicted":0,"score":0},{"actual":1,"predicted":0}])


class HttpContractTest(unittest.TestCase):
    def test_worker_endpoint_and_evaluation_errors(self):
        server=ThreadingHTTPServer(("127.0.0.1",0),Handler)
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            root=f"http://127.0.0.1:{server.server_port}"
            request=Request(root+"/v1/collaborative-aml/detect",data=json.dumps(graph()).encode(),
                            headers={"Content-Type":"application/json"})
            with urlopen(request,timeout=10) as response:
                result=json.load(response)
            self.assertEqual(14,result["summary"]["suspectedAccounts"])
            request=Request(root+"/v1/evaluations/run",data=b'{"rows":[{"actual":"0","predicted":0}]}')
            with self.assertRaises(HTTPError) as exc:urlopen(request,timeout=10)
            self.assertEqual(400,exc.exception.code)
        finally:server.shutdown();server.server_close();thread.join()


if __name__ == "__main__":unittest.main()
