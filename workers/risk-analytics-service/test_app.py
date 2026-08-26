import importlib.util
from pathlib import Path
import unittest


SPEC = importlib.util.spec_from_file_location("risk_analytics_app", Path(__file__).with_name("app.py"))
APP = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(APP)


class AnalyticsServiceTest(unittest.TestCase):
    def test_event_chain_mining_is_ordered_and_reproducible(self):
        payload = {"events": [
            {"eventId": "E2", "subjectId": "A", "eventType": "TRANSFER", "occurredAt": "2026-01-01T00:10:00Z"},
            {"eventId": "E1", "subjectId": "A", "eventType": "LOGIN", "occurredAt": "2026-01-01T00:00:00Z"},
        ]}
        first = APP.mine_event_chains(payload)
        second = APP.mine_event_chains(payload)
        self.assertEqual(first, second)
        self.assertEqual(["LOGIN", "TRANSFER"], first["chains"][0]["eventTypes"])
        self.assertEqual(600.0, first["behaviorPairs"][0]["meanDeltaSeconds"])

    def test_event_chain_accepts_canonical_event_contract(self):
        result = APP.mine_event_chains({"events": [{
            "contractVersion": "CanonicalEvent/1.0",
            "eventId": "E1",
            "standardEventCode": "LOGIN",
            "action": {"code": "LOGIN"},
            "participants": [{"entityUid": "ACCOUNT:A", "role": "SUBJECT"}],
            "occurredAt": {"start": "2026-01-01T00:00:00Z", "precision": "SECOND"},
            "confidence": 0.9,
        }]})
        self.assertEqual(1, result["chainCount"])
        self.assertEqual("ACCOUNT:A", result["chains"][0]["subjectId"])
        self.assertEqual(["LOGIN"], result["chains"][0]["eventTypes"])

    def test_inference_returns_explainable_contributions(self):
        result = APP.infer({"threshold": 0.5, "weights": {"amountRisk": 0.7, "nightRisk": 0.3},
                            "subjects": [{"subjectId": "A", "features": {"amountRisk": 1, "nightRisk": 0.5}}]})
        evidence = result["evidence"][0]
        self.assertEqual("SUSPECTED", evidence["decision"])
        self.assertEqual(2, len(evidence["contributions"]))

    def test_evaluation_metrics(self):
        result = APP.evaluate({"rows": [
            {"actual": True, "predicted": True},
            {"actual": True, "predicted": False},
            {"actual": False, "predicted": False},
        ]})
        self.assertAlmostEqual(0.5, result["metrics"]["recall"])

    def test_behavior_matrix_measures_support_and_temporal_strength(self):
        result = APP.behavior_matrix({
            "windowSeconds": 3600,
            "peakWindowSeconds": 600,
            "events": [
                {"subjectId": "A", "eventType": "LOGIN", "occurredAt": "2026-01-01T00:00:00Z"},
                {"subjectId": "A", "eventType": "TRANSFER", "occurredAt": "2026-01-01T00:05:00Z"},
                {"subjectId": "B", "eventType": "LOGIN", "occurredAt": "2026-01-01T00:00:00Z"},
                {"subjectId": "B", "eventType": "TRANSFER", "occurredAt": "2026-01-01T00:20:00Z"},
            ],
        })
        relation = result["relations"][0]
        self.assertEqual(("LOGIN", "TRANSFER"),
                         (relation["fromEvent"], relation["toEvent"]))
        self.assertEqual(1.0, relation["support"])
        self.assertGreater(relation["temporalStrength"], 0)
        self.assertEqual(relation["riskWeight"],
                         result["matrix"]["LOGIN"]["TRANSFER"])

    def test_risk_diffusion_returns_paths_interventions_and_evidence(self):
        result = APP.risk_diffusion({
            "nodes": [
                {"id": "A", "initialRisk": 0.9},
                {"id": "B", "initialRisk": 0.0},
                {"id": "C", "initialRisk": 0.0},
            ],
            "edges": [
                {"source": "A", "target": "B", "weight": 0.8},
                {"source": "B", "target": "C", "weight": 0.7},
            ],
            "damping": 1.0,
            "maxHops": 2,
            "decisionThreshold": 0.5,
        })
        scores = {item["nodeId"]: item["propagatedRisk"] for item in result["nodeScores"]}
        self.assertAlmostEqual(0.72, scores["B"])
        self.assertAlmostEqual(0.504, scores["C"])
        self.assertEqual(["A", "B", "C"], result["highRiskPaths"][1]["nodes"])
        self.assertEqual("A", result["interventions"][0]["nodeId"])
        self.assertEqual({"A", "B", "C"},
                         {item["subjectId"] for item in result["evidence"]})

    def test_incremental_learning_matches_and_creates_review_candidate(self):
        result = APP.incremental_learning({
            "similarityThreshold": 0.8,
            "existingTemplates": [
                {"templateId": "T1", "eventTypes": ["LOGIN", "TRANSFER"], "support": 3}
            ],
            "observedChains": [
                {"chainId": "C1", "eventTypes": ["LOGIN", "TRANSFER"]},
                {"chainId": "C2", "eventTypes": ["OPEN", "TEST", "CASH_OUT"]},
            ],
        })
        self.assertEqual(1, result["matchedCount"])
        self.assertEqual(1, result["candidateCount"])
        self.assertEqual("PENDING", result["candidates"][0]["reviewStatus"])
        self.assertEqual(["OPEN", "TEST", "CASH_OUT"],
                         result["candidates"][0]["eventTypes"])

    def test_meta_path_detection_finds_deviation_and_hub(self):
        payload = {
            "caseGraphEnvelope": {
                "entities": [
                    {"entityUid": "A", "entityType": "ACCOUNT"},
                    {"entityUid": "B", "entityType": "ACCOUNT"},
                    {"entityUid": "C", "entityType": "COMPANY"},
                ],
                "events": [],
                "relationships": [
                    {"id": "R1", "source": "A", "target": "B",
                     "type": "TRANSFERS_TO", "riskWeight": 0.9},
                    {"id": "R2", "source": "B", "target": "C",
                     "type": "CONTROLS", "riskWeight": 0.8},
                ],
            },
            "sourceNodeIds": ["A"],
            "minHops": 2,
            "maxHops": 3,
            "deviationThreshold": 0.4,
            "compliantTemplates": [{
                "nodeTypes": ["ACCOUNT", "ACCOUNT", "COMPANY"],
                "relationTypes": ["PAYS", "OWNS"],
            }],
        }
        first = APP.detect_meta_paths(payload)
        self.assertEqual(first, APP.detect_meta_paths(payload))
        self.assertEqual(["A", "B", "C"], first["detectedPaths"][0]["nodes"])
        self.assertEqual("B", first["hubScores"][0]["nodeId"])
        self.assertTrue(first["evidence"])

    def test_temporal_anomaly_uses_canonical_events(self):
        events = []
        for index, amount in enumerate([10, 12, 11, 1000]):
            events.append({
                "contractVersion": "CanonicalEvent/1.0",
                "eventId": f"E{index}",
                "standardEventCode": "TRANSFER",
                "participants": [{"entityUid": "ACCOUNT:A", "role": "SUBJECT"}],
                "occurredAt": {"start": f"2026-01-01T01:0{index}:00Z", "precision": "SECOND"},
                "attributes": {"amount": amount},
            })
        result = APP.detect_temporal_anomalies({
            "events": events, "windowSeconds": 600, "burstMinEvents": 3,
            "decisionThreshold": 0.5,
        })
        self.assertTrue(result["anomalies"])
        self.assertEqual("ACCOUNT:A", result["evidence"][0]["subjectId"])
        reasons = {reason for item in result["anomalies"] for reason in item["reasonCodes"]}
        self.assertIn("BURST_ACTIVITY", reasons)
        self.assertIn("AMOUNT_OUTLIER", reasons)

    def test_local_hypergraph_returns_risk_and_intervention(self):
        result = APP.infer_local_hypergraph({
            "caseGraphEnvelope": {
                "entities": [{"entityUid": value, "entityType": "ACCOUNT"}
                             for value in ["A", "B", "C"]],
                "events": [], "relationships": [],
            },
            "hyperedges": [
                {"hyperedgeId": "H1", "type": "FUNDS", "nodeIds": ["A", "B"],
                 "baseRisk": 0.9, "featureSimilarity": 1, "temporalStrength": 1},
                {"hyperedgeId": "H2", "type": "CONTROL", "nodeIds": ["B", "C"],
                 "baseRisk": 0.8, "featureSimilarity": 1, "temporalStrength": 1},
            ],
            "decisionThreshold": 0.5,
        })
        scores = {item["nodeId"]: item["riskScore"] for item in result["nodeScores"]}
        self.assertAlmostEqual(0.98, scores["B"])
        self.assertEqual("H1", result["interventions"][0]["hyperedgeId"])
        self.assertEqual({"A", "B", "C"},
                         {item["subjectId"] for item in result["evidence"]})

    def test_cascade_requires_multi_module_confirmation(self):
        result = APP.cascade_inference({
            "decisionThreshold": 0.6,
            "minModules": 2,
            "upstreamResults": [
                {"runId": "R1", "runType": "META_PATH_DETECTION",
                 "result": {"evidence": [{"subjectId": "B", "score": 0.8,
                                           "pathEvidence": []}]}},
                {"runId": "R2", "runType": "LOCAL_HYPERGRAPH",
                 "result": {"evidence": [{"subjectId": "B", "score": 0.9,
                                           "pathEvidence": []}]}},
            ],
        })
        self.assertEqual("SUSPECTED", result["decisions"][0]["decision"])
        self.assertEqual(1, len(result["knowledgeCandidates"]))
        self.assertEqual("PENDING", result["knowledgeCandidates"][0]["reviewStatus"])

    def test_structured_case_matter_explanation_is_useful_and_traceable(self):
        result = APP.explain_case({
            "caseId": "CASE-S-1",
            "sourceType": "STRUCTURED",
            "events": [{"eventId": "E1"}, {"eventId": "E2"}],
            "transactions": [
                {
                    "sourceRecordId": "TX-1", "sourceLine": 4, "caseMember": True,
                    "timestamp": "2022/09/01 00:00", "sourceAccount": "8000ECA90",
                    "targetAccount": "8006AA910", "amount": 592571,
                    "currency": "US Dollar", "paymentFormat": "Cheque",
                },
                {
                    "sourceRecordId": "TX-2", "sourceLine": 2, "caseMember": True,
                    "timestamp": "2022/09/01 00:08", "sourceAccount": "8000ECA90",
                    "targetAccount": "8000ECA90", "amount": 3195403,
                    "currency": "US Dollar", "paymentFormat": "Reinvestment",
                },
                {
                    "sourceRecordId": "TX-CONTEXT", "sourceLine": 9, "caseMember": False,
                    "timestamp": "2022/09/01 00:07", "sourceAccount": "8000ECA90",
                    "targetAccount": "8000ECA90", "amount": 22.97,
                    "currency": "US Dollar", "paymentFormat": "Reinvestment",
                },
            ],
        })
        self.assertEqual(result, APP.explain_case({
            "caseId": "CASE-S-1",
            "sourceType": "STRUCTURED",
            "events": [{"eventId": "E1"}, {"eventId": "E2"}],
            "transactions": [
                {
                    "sourceRecordId": "TX-1", "sourceLine": 4, "caseMember": True,
                    "timestamp": "2022/09/01 00:00", "sourceAccount": "8000ECA90",
                    "targetAccount": "8006AA910", "amount": 592571,
                    "currency": "US Dollar", "paymentFormat": "Cheque",
                },
                {
                    "sourceRecordId": "TX-2", "sourceLine": 2, "caseMember": True,
                    "timestamp": "2022/09/01 00:08", "sourceAccount": "8000ECA90",
                    "targetAccount": "8000ECA90", "amount": 3195403,
                    "currency": "US Dollar", "paymentFormat": "Reinvestment",
                },
                {
                    "sourceRecordId": "TX-CONTEXT", "sourceLine": 9, "caseMember": False,
                    "timestamp": "2022/09/01 00:07", "sourceAccount": "8000ECA90",
                    "targetAccount": "8000ECA90", "amount": 22.97,
                    "currency": "US Dollar", "paymentFormat": "Reinvestment",
                },
            ],
        }))
        matter_types = {item["matterType"] for item in result["matters"]}
        self.assertIn("SHORT_WINDOW_COMPOSITE", matter_types)
        self.assertIn("CROSS_SUBJECT_TRANSFER", matter_types)
        self.assertTrue(all(item["businessValue"] for item in result["matters"]))
        self.assertTrue(all(item["sourceRefs"] for item in result["matters"]))
        techniques = {item["techniqueCode"]: item for item in result["techniqueOccurrences"]}
        self.assertEqual("CANDIDATE", techniques["T0002"]["decision"])
        self.assertEqual("WEAK_CANDIDATE", techniques["T0035"]["decision"])
        self.assertFalse(result["reviewSuggestions"][0]["blocking"])
        self.assertEqual(1, len(result["behaviorPatternOccurrences"]))
        self.assertEqual("SHORT_WINDOW_COMPOSITE",
                         result["behaviorPatternOccurrences"][0]["patternCode"])
        self.assertEqual("E5", result["behaviorPatternOccurrences"][0]["evidenceStrength"])
        self.assertEqual(1, len(result["riskEvents"]))
        self.assertNotEqual(result["riskEvents"][0]["factConfidence"],
                            result["riskEvents"][0]["riskConfidence"])
        self.assertEqual("NORMAL_BUSINESS",
                         result["alternativeExplanations"][0]["alternativeType"])
        self.assertTrue(result["investigationHypotheses"][0]["evidenceNeeded"])
        self.assertNotIn("attackPathCandidates", result)
        self.assertTrue(all(item["mappingConfidence"] != item["riskConfidence"]
                            for item in result["techniqueOccurrences"]))

    def test_text_case_does_not_promote_reported_codes_to_techniques(self):
        description = (
            "张静组织并招募车手，使用T0016.001微结构化将资金分笔低于5万元存入卡农账户。"
            "随后通过T0055.001购买黄金，并使用T0063兑换USDT、T0067.004进行DeFi跨链。"
            "最终使用T0098贷款方案和T0031虚构销售回流，全程采用T0035测试支付。"
        )
        result = APP.explain_case({
            "caseId": "CASE-T-1", "sourceType": "TEXT", "description": description,
            "events": [{"eventId": "ET1"}],
        })
        self.assertEqual([], result["techniqueOccurrences"])
        self.assertEqual([], result["techniqueAssessments"])
        matter_types = {item["matterType"] for item in result["matters"]}
        self.assertIn("PRECIOUS_METAL_CONVERSION", matter_types)
        self.assertIn("DIGITAL_ASSET_CONVERSION", matter_types)
        self.assertIn("CROSS_CHAIN_TRANSFER", matter_types)
        self.assertTrue(all(item["evidenceRefs"] for item in result["matters"]))
        self.assertEqual("E2", result["behaviorPatternOccurrences"][0]["evidenceStrength"])
        self.assertEqual([], result["alternativeExplanations"])
        self.assertFalse(result["investigationHypotheses"][0]["blocking"])

    def test_withdrawal_fact_does_not_invent_gold_or_crypto_routes(self):
        result = APP.explain_case({
            "caseId": "CASE-T-1056",
            "sourceType": "TEXT",
            "description": (
                "相关人员收集银行卡，使用他人银行账户用于转账。"
                "在违法所得资金转入涉案银行账户取现后，罗某将涉案钱款通过高某前妻"
                "刘某转至高某微信或直接转至高某指定收款账户。"
            ),
            "events": [
                {
                    "eventId": "E-CASH-1",
                    "eventName": "张某某取现莫某某卡内资金",
                    "eventType": "04-取现事件",
                    "eventFrameCode": "CASH_WITHDRAWAL",
                },
                {
                    "eventId": "E-TRANSFER-1",
                    "eventName": "罗某向高某转账",
                    "eventType": "01-转账事件",
                    "eventFrameCode": "FUNDS_TRANSFER",
                },
                {
                    "eventId": "E-JUDICIAL-1",
                    "eventName": "高某投案",
                    "eventType": "11-司法进展事件",
                    "eventFrameCode": "JUDICIAL_PROCEEDING",
                },
            ],
        })
        matter = next(
            item for item in result["matters"]
            if item["matterType"] == "CASH_WITHDRAWAL"
        )
        self.assertEqual("涉案资金进入银行账户后被取现", matter["summary"])
        self.assertNotIn("黄金", matter["summary"])
        self.assertNotIn("加密", matter["summary"])
        self.assertNotIn("拆分", matter["summary"])
        self.assertEqual(["取现"], matter["details"]["matchedTerms"])
        self.assertEqual("EVIDENCE_COMPOSED", matter["details"]["summaryMode"])
        self.assertEqual(["E-CASH-1"], matter["eventRefs"])
        self.assertEqual([], matter["indicatorResultRefs"])
        self.assertEqual([], matter["techniqueCodes"])
        account_fact = next(
            item for item in result["matters"]
            if item["matterType"] == "ACCOUNT_MULE_USAGE"
        )
        self.assertEqual(
            "涉案资金流转涉及他人名下银行卡或银行账户",
            account_fact["summary"],
        )
        self.assertEqual(["E-CASH-1"], account_fact["eventRefs"])
        self.assertEqual(2, len(account_fact["evidenceRefs"]))
        self.assertEqual(
            "CASE_LEVEL_SUMMARY", account_fact["details"]["factScope"]
        )
        self.assertEqual(
            "MIXED_EVIDENCE", account_fact["details"]["basisType"]
        )
        onward_fact = next(
            item for item in result["matters"]
            if item["matterType"] == "ONWARD_FUNDS_TRANSFER"
        )
        self.assertTrue(
            onward_fact["summary"].startswith(
                "涉案资金存在取现后继续转移的路径"
            )
        )
        self.assertNotIn("材料记载", onward_fact["summary"])
        self.assertNotIn("pliStageAssessments", result)
        self.assertEqual(
            "TRADITIONAL_TEXT_FACT_BASELINE",
            result["algorithm"]["id"],
        )

    def test_multi_account_evidence_does_not_invent_asset_conversion(self):
        result = APP.explain_case({
            "caseId": "CASE-T-MULTI-ACCOUNT",
            "sourceType": "TEXT",
            "description": "涉案资金经多层账户转移后进入指定收款账户。",
            "events": [{
                "eventId": "E-LAYER-1",
                "eventName": "资金经多层账户转移",
                "eventType": "01-转账事件",
            }],
        })
        matter = next(
            item for item in result["matters"]
            if item["matterType"] == "MULTI_ACCOUNT_TRANSFER"
        )
        self.assertEqual("材料记载资金经多层账户转移", matter["summary"])
        self.assertNotIn("黄金", matter["summary"])
        self.assertNotIn("USDT", matter["summary"])
        self.assertNotIn("跨链", matter["summary"])
        self.assertEqual(["E-LAYER-1"], matter["eventRefs"])

    def test_text_case_builds_semantic_observations_for_backend_matching(self):
        description = (
            "客户账户交易频繁，日均交易笔数达39笔，短期内密集交易。"
            "多笔资金转入后迅速转出，账户快进快出、不留余额，过渡性质明显。"
            "交易金额频繁出现100元及其整数倍，且金额较小。"
            "资金通过多个个人账户层层吸纳并逐级转移，形成资金转移网络。"
            "交易对手开户地分散至多个省份，交易集中在22:00-次日05:00。"
            "客户职业及收入水平明显不符，并频繁使用支付宝和微信支付。"
        )
        result = APP.explain_case({
            "caseId": "CASE-T-INDICATORS",
            "sourceType": "TEXT",
            "description": description,
            "events": [{"eventId": "ET1"}, {"eventId": "ET2"}],
        })
        observation_codes = {
            item["observationCode"] for item in result["semanticObservations"]
        }
        self.assertTrue({
            "OBS_HIGH_FREQUENCY_ACTIVITY",
            "OBS_RAPID_PASS_THROUGH",
            "OBS_ROUND_AMOUNT_PATTERN",
            "OBS_NIGHT_ACTIVITY",
            "OBS_MULTI_ACCOUNT_LAYERING",
            "OBS_CROSS_REGION_ACTIVITY",
            "OBS_PROFILE_TRANSACTION_MISMATCH",
            "OBS_THIRD_PARTY_PAYMENT",
        }.issubset(observation_codes))
        pattern_codes = {
            item["patternCode"] for item in result["behaviorPatternOccurrences"]
        }
        self.assertTrue({
            "TEXT_HIGH_FREQUENCY_PASS_THROUGH",
            "TEXT_ROUND_AMOUNT_STRUCTURING",
            "TEXT_MULTI_ACCOUNT_LAYERING",
        }.issubset(pattern_codes))
        self.assertEqual([], result["techniqueOccurrences"])
        self.assertTrue(all(
            item["observabilityType"] == "SEMANTIC_OBSERVATION"
            for item in result["semanticObservations"]
        ))

    def test_single_text_observation_does_not_create_technique(self):
        result = APP.explain_case({
            "caseId": "CASE-T-SINGLE",
            "sourceType": "TEXT",
            "description": "该账户交易频繁，日均交易笔数较高。",
            "events": [{"eventId": "ET1"}],
        })
        self.assertEqual(
            {"OBS_HIGH_FREQUENCY_ACTIVITY"},
            {item["observationCode"] for item in result["semanticObservations"]},
        )
        self.assertEqual([], result["techniqueOccurrences"])

    def test_bank_fast_pass_through_wording_builds_layering_pattern(self):
        description = (
            "客户张某在短期内通过多个个人账户接收来自不同地区的多笔资金，随后迅速归集至核心账户。"
            "资金到账后两小时内，通过网银分拆转出至两个新开立账户。"
            "交易行为与其职业和历史流水明显不符。"
        )
        result = APP.explain_case({
            "caseId": "CASE-T-1040",
            "sourceType": "TEXT",
            "description": description,
            "events": [{"eventId": "ET1"}, {"eventId": "ET2"}],
        })
        observation_codes = {
            item["observationCode"] for item in result["semanticObservations"]
        }
        self.assertTrue({
            "OBS_HIGH_FREQUENCY_ACTIVITY",
            "OBS_RAPID_PASS_THROUGH",
            "OBS_MULTI_ACCOUNT_LAYERING",
            "OBS_PROFILE_TRANSACTION_MISMATCH",
        }.issubset(observation_codes))
        pattern_codes = {
            item["patternCode"] for item in result["behaviorPatternOccurrences"]
        }
        self.assertIn("TEXT_MULTI_ACCOUNT_LAYERING", pattern_codes)
        self.assertTrue(result["matters"])
        self.assertTrue(all(
            item["details"]["productClass"] == "FACT_SUMMARY"
            for item in result["matters"]
        ))
        self.assertEqual([], result["techniqueOccurrences"])


if __name__ == "__main__":
    unittest.main()
