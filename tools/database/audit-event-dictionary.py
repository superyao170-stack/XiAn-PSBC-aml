#!/usr/bin/env python3
"""Audit unbound event names against deterministic business-action rules."""

from __future__ import annotations

import argparse
import json
import re
from collections import Counter
from pathlib import Path


RULES = [
    ("RESTITUTION_OR_DISGORGEMENT", r"退缴|退赃|退赔|赔偿|退还|上缴违法所得|退出违法所得|补偿被害|谅解"),
    ("JUDICIAL_OR_ENFORCEMENT_ACTION", r"刑事拘留|行政拘留|取保候审|投案|自首|处罚|被罚款"),
    ("PRECIOUS_METAL_TRADE", r"黄金|贵金属"),
    ("CRYPTO_ASSET_EXCHANGE", r"USDT|泰达币|虚拟币|虚拟货币|加密|跨链|DeFi"),
    ("ACCOUNT_OR_CARD_PROVISION", r"银行卡|银行账户|提供账户|租借账户|借用账户|开卡"),
    ("TOOL_OR_DEVICE_PROVISION", r"POS机|VOIP|刷脸|验证码|作案工具|APP|软件|手机"),
    ("CASH_HANDOVER", r"现金|包裹"),
    ("ILLICIT_PROCEEDS_TRANSFER", r"转账|转移|跑分|洗钱|犯罪所得|赃款"),
    ("ILLICIT_PROFIT_RECEIPT", r"获利|好处费|佣金|分赃|非法所得"),
    ("INTERMEDIARY_ORGANIZATION", r"介绍|邀集|组织"),
    ("CONTACT_OR_COORDINATION", r"联系|商议|提醒|学习|咨询|对接"),
    ("GOODS_OR_ASSET_TRADE", r"购买|出售|收购|销售|股权转让|白酒|钢板|购物卡|消费"),
    ("VICTIM_PAYMENT_OR_LOSS", r"被骗|诈骗|被害人"),
    ("COMPANY_OR_BUSINESS_REGISTRATION", r"注册公司|租赁店面|平台开发"),
    ("FINANCIAL_SERVICE_PAYMENT", r"押金|贷款|扣除|充值|信用卡|缴纳罚金|预交罚金|罚金"),
    ("TRAVEL_OR_PHYSICAL_MOVEMENT", r"前往|入住|香港|送人"),
    ("CONCEALMENT_ACTION", r"掩饰|隐瞒"),
]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("tsv", type=Path)
    args = parser.parse_args()
    rows: list[tuple[str, int]] = []
    for line in args.tsv.read_text(encoding="utf-8").splitlines():
        if "\t" not in line:
            continue
        name, count = line.rsplit("\t", 1)
        rows.append((name, int(count)))

    counts: Counter[str] = Counter()
    unmatched: list[dict[str, object]] = []
    for name, count in rows:
        matched = next((code for code, pattern in RULES if re.search(pattern, name, re.I)), None)
        if matched:
            counts[matched] += count
        else:
            unmatched.append({"name": name, "count": count})

    print(json.dumps({
        "instances": sum(count for _, count in rows),
        "distinctNames": len(rows),
        "matchedInstances": sum(counts.values()),
        "matchedByType": counts,
        "unmatchedInstances": sum(int(item["count"]) for item in unmatched),
        "unmatchedNames": unmatched,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
