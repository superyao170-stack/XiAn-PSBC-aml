package com.datagraph.bank.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaseRiskRatingServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final CaseRiskRatingService service = new CaseRiskRatingService(json);

    @Test
    void emptyFrameworkHasTransparentLowRiskDefault() {
        CaseRiskRatingService.Rating rating = service.rate(json.createObjectNode(), json.createObjectNode());
        assertEquals("LOW", rating.level());
        assertEquals("0.00", rating.score().toPlainString());
        assertEquals("EXPLAINABLE_CASE_RISK_V1", rating.breakdown().get("modelVersion"));
    }

    @Test
    void rawExtractedFactsCanProduceHighRecommendationWithoutSourceRiskLevel() {
        ObjectNode framework = json.createObjectNode();
        ArrayNode events = framework.putArray("events");
        for (int i = 0; i < 7; i++) events.addObject().put("event_description", "诈骗团伙地下钱庄跑分洗钱涉案高风险");
        ArrayNode relationships = framework.putArray("relationships");
        for (int i = 0; i < 14; i++) relationships.addObject().put("relationship_type", "顺承关系");
        for (int i = 0; i < 3; i++) framework.withArray("customers").addObject();
        for (int i = 0; i < 4; i++) framework.withArray("accounts").addObject();
        framework.putArray("other_entities").addObject();
        ObjectNode similarity = json.createObjectNode();
        similarity.putArray("matches").addObject().put("similarity", 0.92);

        CaseRiskRatingService.Rating rating = service.rate(framework, similarity);
        assertEquals("HIGH", rating.level());
        assertTrue(rating.score().doubleValue() >= 70);
    }
}
