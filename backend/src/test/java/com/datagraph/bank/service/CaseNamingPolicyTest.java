package com.datagraph.bank.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaseNamingPolicyTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void separatesIdentifierAndBuildsChineseSemanticName() throws Exception {
        JsonNode graph = mapper.readTree("""
            {"nodes":{
              "customers":[{"name":"孙某"}],
              "events":[
                {"name":"集中收款"},
                {"name":"分散转账"},
                {"name":"跨境汇款"},
                {"name":"临时冻结"}
              ]
            }}
            """);

        CaseNamingPolicy.Result result = CaseNamingPolicy.normalize(
                "ML-2023-001", "经查，案件编号“ML-2023-001”。", graph);

        assertEquals("ML-2023-001", result.sourceCaseNo());
        assertEquals("孙某集中收款、分散转账及跨境汇款案", result.caseName());
    }

    @Test
    void keepsMeaningfulChineseNameAndStillExtractsCaseNumber() throws Exception {
        JsonNode graph = mapper.readTree("{\"nodes\":{}}");

        CaseNamingPolicy.Result result = CaseNamingPolicy.normalize(
                "张某地下钱庄洗钱案", "案件编号：AML/2026/009", graph);

        assertEquals("张某地下钱庄洗钱案", result.caseName());
        assertEquals("AML/2026/009", result.sourceCaseNo());
        assertTrue(CaseNamingPolicy.isSemanticName(result.caseName()));
    }
}
