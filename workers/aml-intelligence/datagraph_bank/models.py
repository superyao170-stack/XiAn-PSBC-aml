"""Schemas and controlled vocabularies for the bank case graph."""

from __future__ import annotations

from typing import Any, Dict, List, Optional

from pydantic import BaseModel, ConfigDict, Field, field_validator


class TraceableModel(BaseModel):
    """Base model that keeps trace fields created by workflow nodes."""

    model_config = ConfigDict(extra="allow", populate_by_name=True)


ENUM_MAPPINGS: Dict[str, Dict[str, str]] = {
    "submission_direction": {
        "01": "中国反洗钱监测分析中心",
        "02": "当地人民银行",
        "03": "公安机关",
    },
    "case_trigger": {
        "01": "模型筛选",
        "02": "人工筛查",
        "03": "监管协查",
        "04": "司法查询触发",
    },
    "urgency": {
        "01": "高风险",
        "02": "中风险",
        "03": "低风险",
    },
    "case_status": {
        "01": "初步可疑",
        "02": "已报送",
        "03": "持续监测",
        "04": "已处置",
    },
    "risk_level": {
        "01": "一般可疑",
        "02": "重点可疑",
        "03": "高风险",
    },
    "suspected_crime_type": {
        "1002": "短期内对私客户快进快出不留余额",
        "2002": "涉赌公共网络赌博",
        "2003": "涉赌组织经营赌博或为其转移资金",
        "3001": "涉嫌诈骗",
    },
}


RELATIONSHIP_TYPES = (
    "涉及关系",
    "包含关系",
    "持有关系",
    "参与关系",
    "来源关系",
    "顺承关系",
    "上下位关系",
    "应对关系",
    "社会关系",
)


class CaseBasicInfo(TraceableModel):
    case_id: str = Field(..., description="案例ID")
    case_name: Optional[str] = Field(None, description="案例名称")
    case_description: Optional[str] = Field(None, description="案例描述")
    business_domain: Optional[str] = Field(None, description="业务领域")
    case_type: Optional[str] = Field(None, description="案例类型")
    submission_direction: Optional[str] = Field(None, description="报送方向")
    case_trigger: Optional[str] = Field(None, description="案例触发点")
    urgency: Optional[str] = Field(None, description="紧急程度")
    report_date: Optional[str] = Field(None, description="案例上报时间")
    case_status: Optional[str] = Field(None, description="案例状态")
    risk_level: Optional[str] = Field(None, description="风险等级")
    suspected_crime_type: Optional[str] = Field(None, description="疑似涉罪类型")
    suspicious_transaction_codes: Optional[str] = Field(None, description="可疑交易特征代码")
    disposition_measures: Optional[str] = Field(None, description="处置措施")


class CustomerEntity(BaseModel):
    """Public customer schema defined by the customer-layer contract."""

    model_config = ConfigDict(extra="ignore")

    entity_id: str
    customer_name: str = ""
    customer_number: str = ""
    id_type: str = ""
    id_number: str = ""
    occupation_industry: str = ""
    nationality: str = ""
    address: str = ""
    customer_risk_level: str = ""
    legal_representative_name: str = ""
    legal_representative_id_type: str = ""
    legal_representative_id_number: str = ""
    controller_name: str = ""
    controller_id_type: str = ""
    controller_id_number: str = ""

    @field_validator("*", mode="before")
    @classmethod
    def normalize_string_fields(cls, value: Any) -> str:
        return "" if value is None else str(value)


class AccountEntity(BaseModel):
    """Public account schema defined by the account-layer contract."""

    model_config = ConfigDict(extra="ignore")

    entity_id: str
    account_type: str = ""
    holder_name: str = ""
    holder_id_type: str = ""
    holder_id_number: str = ""
    account_open_date: str = ""
    account_close_date: str = ""
    account_number: str = ""
    bank_card_type: str = ""
    bank_card_number: str = ""

    @field_validator("*", mode="before")
    @classmethod
    def normalize_string_fields(cls, value: Any) -> str:
        return "" if value is None else str(value)


class OtherEntity(TraceableModel):
    entity_id: str
    entity_attr_1: Optional[str] = None
    entity_attr_2: Optional[str] = None
    entity_attr_3: Optional[str] = None
    entity_attr_4: Optional[str] = None


class Evidence(TraceableModel):
    evidence_id: str
    evidence_link_type: Optional[str] = None
    evidence_link_id: Optional[str] = None
    evidence_type: Optional[str] = None
    evidence_source: Optional[str] = None
    original_data: Optional[str] = None


class Event(BaseModel):
    """Public event schema.

    Internal retrieval, provenance and review data stay in workflow snapshots;
    final event objects strictly follow the 12-field extraction contract.
    """

    model_config = ConfigDict(extra="ignore")

    event_id: str
    event_name: str = ""
    event_type: str = ""
    event_description: str = ""
    event_start_date: str = ""
    event_end_date: str = ""
    recognition_rule: str = ""
    product_service: str = ""
    value_tool: str = ""
    risk_type: str = ""
    risk_indicator: str = ""
    disposition_measures: str = ""

    @field_validator("*", mode="before")
    @classmethod
    def normalize_string_fields(cls, value: Any) -> str:
        return "" if value is None else str(value)


class Relationship(BaseModel):
    """Public nine-field relationship schema."""

    model_config = ConfigDict(extra="ignore")

    relationship_id: str
    relationship_type: str = ""
    relationship_description: str = ""
    source_node_id: str
    source_node_name: str = ""
    source_node_type: str = ""
    target_node_id: str
    target_node_name: str = ""
    target_node_type: str = ""

    @field_validator("*", mode="before")
    @classmethod
    def normalize_string_fields(cls, value: Any) -> str:
        return "" if value is None else str(value)


class IllegalBehaviorCase(TraceableModel):
    basic_info: CaseBasicInfo
    customers: List[CustomerEntity] = Field(default_factory=list)
    accounts: List[AccountEntity] = Field(default_factory=list)
    other_entities: List[OtherEntity] = Field(default_factory=list)
    events: List[Event] = Field(default_factory=list)
    relationships: List[Relationship] = Field(default_factory=list)
    evidences: List[Evidence] = Field(default_factory=list)
    processing_metadata: Dict[str, Any] = Field(default_factory=dict)
