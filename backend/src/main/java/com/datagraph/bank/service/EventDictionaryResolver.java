package com.datagraph.bank.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Resolves a runtime event assertion to a versioned business event definition.
 * Source format is deliberately excluded from the classification contract.
 */
public final class EventDictionaryResolver {
    private static final List<NameRule> REPORTED_ACTION_RULES = List.of(
            rule("RESTITUTION_OR_DISGORGEMENT", "退缴|退赃|退赔|赔偿|退还|上缴违法所得|退出违法所得|补偿被害|谅解"),
            rule("JUDICIAL_PROCEEDING", "刑事拘留|行政拘留|取保候审|投案|自首|处罚|被罚款"),
            rule("PRECIOUS_METAL_LIQUIDATION", "出售黄金|销赃黄金|黄金.*转卖|贵金属.*变现"),
            rule("PRECIOUS_METAL_PURCHASE", "黄金|贵金属"),
            rule("CRYPTO_ASSET_EXCHANGE", "USDT|泰达币|虚拟币|虚拟货币|加密|跨链|DeFi"),
            rule("ACCOUNT_OR_CARD_PROVISION", "银行卡|银行账户|提供账户|租借账户|借用账户|开卡"),
            rule("TOOL_OR_DEVICE_PROVISION", "POS机|VOIP|刷脸|验证码|作案工具|APP|软件|手机"),
            rule("CASH_HANDOVER", "现金|包裹"),
            rule("FUNDS_TRANSFER", "转账|转移|跑分|洗钱|犯罪所得|赃款"),
            rule("FUNDS_RECEIPT", "获利|好处费|佣金|分赃|非法所得"),
            rule("INTERMEDIARY_ORGANIZATION", "介绍|邀集|组织"),
            rule("CONTACT_OR_COORDINATION", "联系|商议|提醒|学习|咨询|对接"),
            rule("GOODS_OR_ASSET_TRADE", "购买|出售|收购|销售|股权转让|白酒|钢板|购物卡|消费"),
            rule("VICTIM_PAYMENT_OR_LOSS", "被骗|诈骗|被害人"),
            rule("COMPANY_OR_BUSINESS_REGISTRATION", "注册公司|租赁店面|平台开发"),
            rule("FINANCIAL_SERVICE_PAYMENT", "押金|贷款|扣除|充值|信用卡|缴纳罚金|预交罚金|罚金"),
            rule("TRAVEL_OR_PHYSICAL_MOVEMENT", "前往|入住|香港|送人"),
            rule("CONCEALMENT_ACTION", "掩饰|隐瞒"),
            rule("COOPERATION_OR_MERITORIOUS_ACTION", "表扬|协助抓捕|立功"),
            rule("INVESTMENT_OR_FUNDING", "投入资金|投资"),
            rule("CURRENCY_EXCHANGE", "兑换港币|外币兑换"),
            rule("CASH_WITHDRAWAL", "套现"),
            rule("ACCOUNT_RESTRICTION", "解封|解除管控"),
            rule("PRIOR_OFFENSE", "危险驾驶")
    );

    private EventDictionaryResolver() {
    }

    public static Resolution resolve(String eventType, String eventName) {
        String type = eventType == null ? "" : eventType.trim();
        if (type.startsWith("01-")) return typed("FUNDS_TRANSFER");
        if (type.startsWith("02-")) return typed("FUNDS_RECEIPT");
        if (type.startsWith("03-")) return typed("CASH_DEPOSIT");
        if (type.startsWith("04-")) return typed("CASH_WITHDRAWAL");
        if (type.startsWith("05-")) return typed("ACCOUNT_OPENING");
        if (type.startsWith("06-")) return typed("ACCOUNT_CLOSURE");
        if (type.startsWith("07-")) return typed("ACCOUNT_RESTRICTION");
        if (type.startsWith("08-")) return typed("CUSTOMER_DUE_DILIGENCE");
        if (type.startsWith("09-")) return typed("SUSPICIOUS_ACTIVITY_REPORT");
        if (type.startsWith("10-")) return typed("INVESTIGATION_ACTION");
        if (type.startsWith("11-")) return typed("JUDICIAL_PROCEEDING");
        if ("SUSPICIOUS_TRANSACTION_CLUSTER".equals(type)) {
            return new Resolution("MANUAL_REVIEW_DECISION", "LEGACY_MANUAL_CLUSTER_ALIAS",
                    new BigDecimal("0.9500"));
        }
        String name = eventName == null ? "" : eventName.trim();
        for (NameRule rule : REPORTED_ACTION_RULES) {
            if (rule.pattern().matcher(name).find()) {
                return new Resolution(rule.frameCode(), "EVENT_NAME_DICTIONARY",
                        new BigDecimal("0.9500"));
            }
        }
        return new Resolution("UNCLASSIFIED_REPORTED_ACTION", "PENDING_EVENT_CLASSIFICATION",
                new BigDecimal("0.2500"));
    }

    private static Resolution typed(String code) {
        return new Resolution(code, "EVENT_TYPE_DICTIONARY", BigDecimal.ONE);
    }

    private static NameRule rule(String code, String pattern) {
        return new NameRule(code, Pattern.compile(pattern, Pattern.CASE_INSENSITIVE));
    }

    private record NameRule(String frameCode, Pattern pattern) {
    }

    public record Resolution(String frameCode, String matchMethod, BigDecimal confidence) {
    }
}
