#!/usr/bin/env python3
"""Insert a current-system abstract using the draft document's first-page layout."""

from __future__ import annotations

import copy
import sys
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

from lxml import etree


W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
XML_NS = "http://www.w3.org/XML/1998/namespace"
W = f"{{{W_NS}}}"


ABSTRACT_PARAGRAPHS = [
    (
        "针对商业银行非法行为数据来源分散、参与主体复杂、行为链条隐蔽以及证据形态多样等特点，"
        "传统依赖单一规则和孤立数据的风控方式难以完整还原风险事实，也难以支撑跨案例关联分析与结果追溯。"
        "本技术方案围绕“数据接入—知识组织—风险评估—业务应用”的闭环，构建面向商业银行非法行为识别的"
        "知识模型与案例智能处理系统，为反洗钱、反欺诈等场景提供统一、可计算、可解释的技术基础。"
    ),
    (
        "方案以案例为组织边界，建立案例基本信息层、实体层、事件层、关系层和证据层组成的五层知识结构，"
        "并通过非法行为知识本体统一概念、属性、关系和语义约束。在此基础上，构建覆盖实体风险、事件风险、"
        "关系网络、证据质量和案例综合评价的分类分级指标体系，使案例事实、风险判断与证据来源能够相互关联、逐级追溯。"
    ),
    (
        "在系统功能层，案例数据经统一接入、校验、清洗、标准化和任务创建后，进入新增案例或历史案例处理路线。"
        "新增案例依次执行可疑报告智能生成、知识驱动的五层框架抽取和语义增强的相似案例检索三项核心功能；"
        "历史案例复用已有分析材料，重点完成框架抽取、审核和图谱入库。系统进一步支持风险定级、人工复核、"
        "结果保存以及非法行为关系图谱构建与展示，实现从原始材料到结构化知识和关联线索的全过程处理。"
    ),
    (
        "技术实现采用前后端分离、业务服务与算法Worker解耦、关系数据库与图数据库协同的架构。"
        "Spring Boot后端负责认证授权、流程状态、数据持久化与审计，Python Worker承担报告生成、知识抽取和相似度计算，"
        "PostgreSQL与TuGraph分别保存业务数据和图谱数据，CoreNLP及BGE模型服务提供中文语义处理能力。"
        "系统通过统一的REST API和内部组件接口对外提供案例上传、智能分析、复核审批、图谱查询、知识解释和系统管理能力。"
    ),
]


def set_paragraph_text(paragraph: etree._Element, text: str) -> None:
    """Replace paragraph content while preserving its paragraph properties."""
    for child in list(paragraph):
        if child.tag != W + "pPr":
            paragraph.remove(child)
    run = etree.SubElement(paragraph, W + "r")
    text_node = etree.SubElement(run, W + "t")
    text_node.set(f"{{{XML_NS}}}space", "preserve")
    text_node.text = text


def make_abstract_title(template: etree._Element) -> etree._Element:
    paragraph = copy.deepcopy(template)
    ppr = paragraph.find(W + "pPr")
    if ppr is None:
        ppr = etree.Element(W + "pPr")
        paragraph.insert(0, ppr)

    style = ppr.find(W + "pStyle")
    if style is not None:
        ppr.remove(style)

    # Mirror the draft Heading 1 appearance directly, without assigning a
    # heading/outline level that would add the abstract to the TOC on refresh.
    for tag in ("keepNext", "keepLines"):
        if ppr.find(W + tag) is None:
            etree.SubElement(ppr, W + tag)
    justification = ppr.find(W + "jc")
    if justification is None:
        justification = etree.SubElement(ppr, W + "jc")
    justification.set(W + "val", "center")

    for run in paragraph.findall(W + "r"):
        rpr = run.find(W + "rPr")
        if rpr is None:
            rpr = etree.Element(W + "rPr")
            run.insert(0, rpr)
        fonts = rpr.find(W + "rFonts")
        if fonts is None:
            fonts = etree.SubElement(rpr, W + "rFonts")
        fonts.set(W + "eastAsia", "黑体")
        if rpr.find(W + "b") is None:
            etree.SubElement(rpr, W + "b")
        size = rpr.find(W + "sz")
        if size is None:
            size = etree.SubElement(rpr, W + "sz")
        size.set(W + "val", "32")
        size_cs = rpr.find(W + "szCs")
        if size_cs is None:
            size_cs = etree.SubElement(rpr, W + "szCs")
        size_cs.set(W + "val", "48")
    return paragraph


def strip_template_bookmarks(paragraph: etree._Element) -> None:
    """Remove draft-local bookmarks so identifiers remain unique in the target."""
    for tag in (W + "bookmarkStart", W + "bookmarkEnd"):
        for element in paragraph.findall(".//" + tag):
            parent = element.getparent()
            if parent is not None:
                parent.remove(element)


def build_abstract_elements(draft_xml: bytes) -> list[etree._Element]:
    draft_root = etree.fromstring(draft_xml)
    body = draft_root.find(W + "body")
    if body is None:
        raise RuntimeError("Draft document has no body")
    direct_paragraphs = body.findall(W + "p")
    if len(direct_paragraphs) < 6:
        raise RuntimeError("Draft document does not contain the expected abstract template")

    abstract_title = make_abstract_title(direct_paragraphs[1])
    body_templates = [copy.deepcopy(p) for p in direct_paragraphs[2:6]]
    for paragraph, text in zip(body_templates, ABSTRACT_PARAGRAPHS, strict=True):
        set_paragraph_text(paragraph, text)
    elements = [abstract_title, *body_templates]
    for element in elements:
        strip_template_bookmarks(element)
    return elements


def patch_document(base: Path, draft: Path, output: Path) -> None:
    with ZipFile(draft) as archive:
        abstract_elements = build_abstract_elements(archive.read("word/document.xml"))

    with ZipFile(base) as archive:
        document_xml = archive.read("word/document.xml")
        root = etree.fromstring(document_xml)
        body = root.find(W + "body")
        if body is None:
            raise RuntimeError("Base document has no body")
        direct_paragraphs = body.findall(W + "p")
        if not direct_paragraphs:
            raise RuntimeError("Base document has no title paragraph")

        first_text = "".join(direct_paragraphs[0].itertext())
        if "面向商业银行非法行为识别" not in first_text:
            raise RuntimeError("Unexpected first paragraph; refusing to insert abstract")

        for offset, element in enumerate(abstract_elements, start=1):
            body.insert(offset, element)

        updated_xml = etree.tostring(
            root,
            xml_declaration=True,
            encoding="UTF-8",
            standalone="yes",
        )

        output.parent.mkdir(parents=True, exist_ok=True)
        with ZipFile(output, "w", ZIP_DEFLATED) as out:
            for item in archive.infolist():
                payload = updated_xml if item.filename == "word/document.xml" else archive.read(item.filename)
                out.writestr(item, payload)


def main() -> None:
    if len(sys.argv) != 4:
        raise SystemExit("Usage: add_abstract.py BASE.docx DRAFT.docx OUTPUT.docx")
    patch_document(Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]))


if __name__ == "__main__":
    main()
