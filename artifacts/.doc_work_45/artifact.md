# Template execution contract

## Reference

- Path: `/Users/sunnytearlie/Desktop/西安邮储/《面向商业银行非法行为识别的知识模型构建技术方案》.docx`
- SHA-256: `8b0ad607d5c96a3690e3d740916b790308822760ce61b63ff302d6f0f898a1f8`
- Evidence: `/tmp/datagraph-tech-doc-45/evidence/style.json`
- Render: `/tmp/datagraph-tech-doc-45/reference-render`
- Page count: 14
- Section count: 1

## Page system

- A4 portrait, 8.27 x 11.69 inches.
- Margins: left/right 1.25 inches; top/bottom 1.00 inch.
- Header distance 0.591 inch; footer distance 0.689 inch.
- One continuous section; no distinct first/even/odd-page headers; headers and footers are empty.

## Typography and paragraph rhythm

- Normal: Chinese Song type role, English Times New Roman, 14 pt, justified, 1.5 line spacing, first-line indent 2 Chinese characters (10 pt in the source's direct paragraph formatting).
- Heading 1: Chinese Hei type role, 16 pt, bold, centered, keep with next and keep together. Source paragraphs carry a direct first-line indent; appended chapters shall normalize it to zero while preserving the visual role.
- Heading 2: 15 pt, bold, left aligned, keep with next and keep together, no first-line indent.
- Heading 3: 14 pt, bold, left aligned, keep with next and keep together, no first-line indent.
- Title block: centered 18 pt bold Hei type role; preserve the original first paragraph unchanged.
- The source uses `宋体` and `黑体`, which render as missing glyph boxes in the available LibreOffice runtime. The final copy may map these roles to macOS-compatible `Songti SC` and `Heiti SC` while preserving size, weight, hierarchy, and layout intent.

## Lists and tables

- The reference contains no tables and no numbered-list definitions used by the body.
- New API and architecture comparison material may use simple black-grid tables because the user requested an API/interface manual and repeated row/column lookup is the clearest form.
- New tables use explicit A4 usable-width geometry (5.77 inches / 8309 DXA), left indent 0, fixed column grids, repeating header rows, 10.5 pt Songti SC body, 10.5 pt bold Heiti SC header, centered short fields, left-aligned narrative fields, and at least 0.08 inch cell padding.
- Tables must not use fixed row heights and may break across pages only with repeated headers.

## Components and content flow

- Existing content order is title block, Chapter 1, Chapter 2, Chapter 3.
- Append Chapter 4 and Chapter 5 after the final Chapter 3 paragraph and before the existing section properties.
- Insert a page break before Chapter 4 and before Chapter 5.
- Use prose sections for functional and architectural explanation; use compact tables only for input mappings, stage/output mappings, technology stack, API group overview, key endpoints, error codes, and configuration interfaces.
- No cover redesign, decorative header, footer, image, caption, footnote, endnote, content control, or field is introduced.

## Slot map

- Preserve: all existing title and Chapters 1-3 paragraphs, order, wording, styles, section geometry, custom XML, theme, settings, footnote/endnote containers, and package relationships.
- Editable: append-only slot immediately before the document section properties.
- Added content: Chapter 4 and Chapter 5, including their Heading 1/2/3 paragraphs, explanatory body paragraphs, and API lookup tables.
- Allowed style adjustment: map Chinese font roles in copied and appended content to installed compatible fonts for deterministic visual QA.

## Package preservation

- Preserve all original package parts and relationships. Python-docx is permitted because the task appends substantial structured content.
- Expected modified parts: `word/document.xml`, `word/styles.xml`, `word/fontTable.xml`, and document property parts that the library updates.
- Preserve-only parts: `customXml/**`, `_rels/.rels`, `word/_rels/document.xml.rels`, `word/theme/theme1.xml`, `word/settings.xml`, `word/footnotes.xml`, and `word/endnotes.xml` unless a verified library serialization change is structurally equivalent.

## Fidelity gates

- Reference file SHA-256 must remain unchanged.
- Chapters 1-3 text and heading order must remain unchanged.
- Final section count and page geometry must match the reference.
- Final pages must show readable Chinese glyphs, no clipping or overlap, clean table wrapping, and consistent source-derived hierarchy.
- API paths, methods, state values, and technology versions must be verified against the current repository before delivery.
