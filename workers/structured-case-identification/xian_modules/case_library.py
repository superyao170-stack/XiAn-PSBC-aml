#!/usr/bin/env python3
"""Create and inspect the local extracted-case library."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from datagraph_bank.case_library import CaseLibrary, discover_final_case_files


PROJECT_ROOT = Path(__file__).resolve().parent


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="管理 final_case.json 文件型案例库。")
    parser.add_argument(
        "--library-dir",
        type=Path,
        default=PROJECT_ROOT / "data" / "case_library",
        help="案例库目录，默认 data/case_library。",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    import_parser = subparsers.add_parser("import", help="批量导入 final_case.json。")
    import_parser.add_argument("--input-root", type=Path, required=True)
    import_parser.add_argument(
        "--on-conflict",
        choices=("error", "skip", "replace"),
        default="error",
        help="同 case_id 内容不同时的处理方式。",
    )
    subparsers.add_parser("stats", help="显示案例库统计。")
    subparsers.add_parser("list", help="列出当前案例。")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    library = CaseLibrary(args.library_dir)
    if args.command == "import":
        paths = discover_final_case_files(args.input_root)
        if not paths:
            raise SystemExit(f"未发现可导入的 final_case.json: {args.input_root}")
        report = library.import_paths(paths, on_conflict=args.on_conflict)
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 1 if report["failure_count"] else 0

    manifest = library.manifest()
    if args.command == "stats":
        print(
            json.dumps(
                {
                    "library_root": str(library.root),
                    "case_count": manifest["case_count"],
                    "updated_at": manifest["updated_at"],
                },
                ensure_ascii=False,
                indent=2,
            )
        )
    else:
        print(json.dumps(manifest["cases"], ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
