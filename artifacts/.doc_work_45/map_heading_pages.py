from __future__ import annotations

import argparse
import json
import re
import subprocess
from pathlib import Path

from docx import Document


PDFTOTEXT = Path(
    "/Users/sunnytearlie/.cache/codex-runtimes/codex-primary-runtime/"
    "dependencies/native/poppler/poppler/bin/pdftotext"
)


def normalize(text: str) -> str:
    return re.sub(r"\s+", "", text)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("docx", type=Path)
    parser.add_argument("pdf", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    headings = [
        p.text
        for p in Document(args.docx).paragraphs
        if p.style.name in {"Heading 1", "Heading 2", "Heading 3"}
    ]
    completed = subprocess.run(
        [str(PDFTOTEXT), "-layout", str(args.pdf), "-"],
        check=True,
        stdout=subprocess.PIPE,
    )
    pages = completed.stdout.decode("utf-8", errors="replace").split("\f")
    if pages and not pages[-1].strip():
        pages.pop()
    normalized_pages = [normalize(page) for page in pages]
    first = normalize(headings[0])
    matches = [i + 1 for i, page in enumerate(normalized_pages) if first in page]
    if len(matches) < 2:
        raise SystemExit(f"Could not identify body start from first heading: {matches}")
    body_start = max(matches)

    page_map: dict[str, int] = {}
    missing: list[str] = []
    for heading in headings:
        target = normalize(heading)
        found = None
        for physical_page in range(body_start, len(normalized_pages) + 1):
            if target in normalized_pages[physical_page - 1]:
                found = physical_page - body_start + 1
                break
        if found is None:
            missing.append(heading)
        else:
            page_map[heading] = found
    if missing:
        raise SystemExit("Missing headings in rendered PDF:\n" + "\n".join(missing))
    args.out.write_text(
        json.dumps(page_map, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(f"physical_pages={len(pages)} body_start={body_start} headings={len(page_map)}")


if __name__ == "__main__":
    main()
