"""Unstructured text cleaning and document-level chunking."""

from __future__ import annotations

import re
from typing import Any, Dict, List, Tuple


class UnstructuredTextProcessor:
    """Clean analysis text without using report chapter labels as boundaries."""

    def __init__(self, chunk_size: int = 1200, overlap: int = 160) -> None:
        self.chunk_size = chunk_size
        self.overlap = overlap

    def process(self, state: Dict[str, Any]) -> Dict[str, Any]:
        preprocessed: Dict[str, str] = {}
        sections: Dict[str, List[Dict[str, Any]]] = {}
        chunks: Dict[str, List[str]] = {}
        report: Dict[str, Any] = {}

        for text_name, text in (state.get("analysis_texts") or {}).items():
            cleaned, cleaning_steps = self.clean_text(text)
            document_units = self._build_document_units(cleaned)
            text_chunks = self.chunk_text(document_units)

            preprocessed[text_name] = cleaned
            sections[text_name] = document_units
            chunks[text_name] = text_chunks
            report[text_name] = {
                "original_length": len(text or ""),
                "cleaned_length": len(cleaned),
                "document_unit_count": len(document_units),
                "chunk_count": len(text_chunks),
                "cleaning_steps": cleaning_steps,
                "segmentation_strategy": "全文级处理；不使用【一】至【十】章节标签切分",
            }

        return {
            "preprocessed_texts": preprocessed,
            "structured_sections": sections,
            "text_chunks": chunks,
            "text_cleaning_report": report,
        }

    def clean_text(self, text: str) -> Tuple[str, List[str]]:
        steps: List[str] = []
        result = text or ""
        before = result
        result = result.replace("\r\n", "\n").replace("\r", "\n")
        if result != before:
            steps.append("统一换行符")

        before = result
        result = re.sub(r"[“”\"']", "", result)
        result = re.sub(r"[ \t]+", " ", result)
        result = re.sub(r"\n{2,}", "\n", result)
        if result != before:
            steps.append("清理引号、空白和空行")

        before = result
        result = self._normalize_common_ocr_noise(result)
        if result != before:
            steps.append("修正常见 OCR/录入噪声")

        return result.strip(), steps

    def _normalize_common_ocr_noise(self, text: str) -> str:
        replacements = {
            "涉沙": "涉赌",
            "规律律性": "规律性",
            "金额37.75万元元": "金额37.75万元",
            "2024-10-013": "2024-10-13",
        }
        for old, new in replacements.items():
            text = text.replace(old, new)
        return text

    def _build_document_units(self, text: str) -> List[Dict[str, Any]]:
        """Return one document unit for compatibility with downstream state keys.

        Chapter labels such as ``【一】`` remain ordinary text and never define
        extraction boundaries.
        """

        return [
            {
                "section_id": "document_001",
                "section_title": "全文",
                "section_name": "全文",
                "text": text.strip(),
            }
        ]

    def chunk_text(self, sections: List[Dict[str, Any]]) -> List[str]:
        chunks: List[str] = []
        current: List[str] = []
        current_length = 0

        for section in sections:
            paragraphs = self._split_paragraphs(section["text"])
            for paragraph in paragraphs:
                if current and current_length + len(paragraph) > self.chunk_size:
                    chunks.append("\n".join(current))
                    overlap_text = chunks[-1][-self.overlap :] if self.overlap else ""
                    current = [overlap_text] if overlap_text else []
                    current_length = len(overlap_text)
                current.append(paragraph)
                current_length += len(paragraph)

        if current:
            chunks.append("\n".join(item for item in current if item))
        return chunks

    def _split_paragraphs(self, text: str) -> List[str]:
        raw_parts = re.split(r"(?<=[。；;])", text)
        return [part.strip() for part in raw_parts if part.strip()]
