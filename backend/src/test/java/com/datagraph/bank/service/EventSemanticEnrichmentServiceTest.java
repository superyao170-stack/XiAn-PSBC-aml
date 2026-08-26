package com.datagraph.bank.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class EventSemanticEnrichmentServiceTest {
    private final EventSemanticEnrichmentService service =
            new EventSemanticEnrichmentService(null, new ObjectMapper());

    @Test
    void qualityScoreUsesVersionedFiveDimensionPolicy() {
        var result = service.score(new EventSemanticEnrichmentService.QualityInput(
                1.0, 0.8, 1.0, 0.9, 0.8));

        assertEquals(new BigDecimal("0.910000"), result.score());
        assertEquals("HIGH", result.grade());
        assertEquals(5, result.breakdown().size());
    }

    @Test
    void strongIdentityKeyCanAutoMatch() {
        var result = service.resolve(
                Map.of("uetr", "UETR-001", "amount", "100.00"),
                Map.of("uetr", "UETR-001", "amount", "999.00"));

        assertEquals("MATCHED", result.status());
        assertEquals("uetr", result.matchedBy());
        assertEquals(1.0, result.score());
    }

    @Test
    void compositeSimilarityNeverAutoMerges() {
        Map<String, Object> left = Map.of(
                "amount", "100.00", "currency", "CNY", "debtor", "A",
                "creditor", "B", "eventTime", "2026-07-24T10:00:00");
        Map<String, Object> right = Map.copyOf(left);

        var result = service.resolve(left, right);

        assertEquals("CANDIDATE", result.status());
        assertNotEquals("MATCHED", result.status());
        assertEquals("composite", result.matchedBy());
    }

    @Test
    void transferProfileUsesControlledWhitelist() {
        assertEquals("PAYMENT.CREDIT_TRANSFER", service.semanticProfileFor("FUNDS_TRANSFER"));
        assertEquals("CASH.DEPOSIT", service.semanticProfileFor("CASH_DEPOSIT"));
        assertEquals("LEGACY.CRYPTO_CROSS_CHAIN_TRANSFER",
                service.semanticProfileFor("CRYPTO_CROSS_CHAIN_TRANSFER"));
    }
}
