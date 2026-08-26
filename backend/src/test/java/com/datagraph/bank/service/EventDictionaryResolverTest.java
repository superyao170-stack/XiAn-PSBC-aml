package com.datagraph.bank.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EventDictionaryResolverTest {
    @Test
    void resolvesAllCanonicalEventTypeFamilies() {
        assertEquals("FUNDS_TRANSFER", EventDictionaryResolver.resolve("01-Yuan-转账事件", "转账").frameCode());
        assertEquals("FUNDS_RECEIPT", EventDictionaryResolver.resolve("02-收款事件", "收款").frameCode());
        assertEquals("CASH_DEPOSIT", EventDictionaryResolver.resolve("03-存现事件", "存现").frameCode());
        assertEquals("CASH_WITHDRAWAL", EventDictionaryResolver.resolve("04-取现事件", "取现").frameCode());
        assertEquals("ACCOUNT_OPENING", EventDictionaryResolver.resolve("05-开户事件", "开户").frameCode());
        assertEquals("ACCOUNT_CLOSURE", EventDictionaryResolver.resolve("06-销户事件", "销户").frameCode());
        assertEquals("ACCOUNT_RESTRICTION", EventDictionaryResolver.resolve("07-冻结/止付事件", "冻结").frameCode());
        assertEquals("CUSTOMER_DUE_DILIGENCE", EventDictionaryResolver.resolve("08-尽调事件", "尽调").frameCode());
        assertEquals("SUSPICIOUS_ACTIVITY_REPORT", EventDictionaryResolver.resolve("09-报送事件", "报送").frameCode());
        assertEquals("INVESTIGATION_ACTION", EventDictionaryResolver.resolve("10-调查核查事件", "调查").frameCode());
        assertEquals("JUDICIAL_PROCEEDING", EventDictionaryResolver.resolve("11-司法进展事件", "判决").frameCode());
    }

    @Test
    void refinesReportedOtherActionsWithoutUsingSourceFormat() {
        assertEquals("RESTITUTION_OR_DISGORGEMENT",
                EventDictionaryResolver.resolve("99-其他", "退缴违法所得").frameCode());
        assertEquals("ACCOUNT_OR_CARD_PROVISION",
                EventDictionaryResolver.resolve("99-其他", "提供银行卡用于接收资金").frameCode());
        assertEquals("CRYPTO_ASSET_EXCHANGE",
                EventDictionaryResolver.resolve("99-其他", "购买USDT并跨链").frameCode());
        assertEquals("UNCLASSIFIED_REPORTED_ACTION",
                EventDictionaryResolver.resolve("99-其他", "UNNAMED_WORKER_EVENT").frameCode());
    }
}
