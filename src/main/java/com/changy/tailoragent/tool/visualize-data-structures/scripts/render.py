#!/usr/bin/env python3
"""Render a data-structure image from a JSON specification."""

from __future__ import annotations

import argparse
from contextlib import redirect_stdout
import importlib
import inspect
from io import StringIO
import json
import math
from pathlib import Path
import sys
from typing import Any


SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import algorithm_viz


MODERN_RENDERERS = (
    "draw_array",
    "draw_string",
    "draw_multi_array",
    "draw_matrix",
    "draw_grid",
    "draw_stack",
    "draw_queue",
    "draw_deque",
    "draw_circular_queue",
    "draw_linked_list",
    "draw_doubly_linked_list",
    "draw_tree",
    "draw_binary_tree",
    "draw_heap",
    "draw_binary_heap",
    "draw_graph",
    "draw_table",
    "draw_dp_table",
    "draw_intervals",
    "draw_timeline",
    "draw_mapping",
    "draw_set",
    "draw_buckets",
    "draw_histogram",
    "draw_hash_table_chaining",
    "draw_hash_table_open_addressing",
    "draw_trie",
    "draw_bplus_tree",
    "draw_union_find",
    "draw_segment_tree",
    "draw_fenwick_tree",
    "draw_binary_indexed_tree",
    "draw_monotonic_stack",
    "draw_monotonic_queue",
    "draw_adjacency_list",
    "draw_adjacency_matrix",
    "draw_edge_list",
    "draw_graph_representations",
    "draw_dag",
    "draw_bitmask",
    "draw_bits",
    "draw_coordinate_plane",
)

_missing_modern = [
    name for name in MODERN_RENDERERS
    if name not in algorithm_viz.__all__ or not callable(getattr(algorithm_viz, name, None))
]
if _missing_modern:
    raise RuntimeError(f"renderer registry is out of sync: {_missing_modern}")

LEGACY_RENDERERS = (
    "draw_array",
    "draw_stack",
    "draw_queue",
    "draw_linked_list",
    "draw_binary_tree",
    "draw_graph",
    "draw_binary_heap",
    "draw_hash_table_chaining",
    "draw_hash_table_open_addr",
    "draw_trie",
    "draw_skip_list",
    "draw_bplus_tree",
)

ALLOWED_TOP_LEVEL_FIELDS = {"engine", "renderer", "args", "kwargs", "output"}
RESERVED_KWARGS = {"filename", "output", "ax", "axes", "theme"}
ALLOWED_SUFFIXES = {".png", ".svg", ".pdf"}
MAX_JSON_BYTES = 2 * 1024 * 1024
MAX_JSON_DEPTH = 64
MAX_JSON_ITEMS = 200_000


def _reject_json_constant(value: str) -> None:
    raise ValueError(f"non-standard JSON constant is not allowed: {value}")


def _object_without_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON object key: {key!r}")
        result[key] = value
    return result


def _validate_value_budget(value: Any) -> None:
    stack: list[tuple[Any, int]] = [(value, 0)]
    item_count = 0
    while stack:
        item, depth = stack.pop()
        item_count += 1
        if item_count > MAX_JSON_ITEMS:
            raise ValueError(f"request exceeds the {MAX_JSON_ITEMS} item limit")
        if depth > MAX_JSON_DEPTH:
            raise ValueError(f"request exceeds the nesting depth limit of {MAX_JSON_DEPTH}")
        if isinstance(item, float) and not math.isfinite(item):
            raise ValueError("all numeric values must be finite")
        if isinstance(item, dict):
            stack.extend((key, depth + 1) for key in item)
            stack.extend((child, depth + 1) for child in item.values())
        elif isinstance(item, (list, tuple, set, frozenset)):
            stack.extend((child, depth + 1) for child in item)


def _decode_tagged(value: Any) -> Any:
    """Decode JSON-safe tagged tuples, sets, and mappings recursively."""
    if isinstance(value, list):
        return [_decode_tagged(item) for item in value]
    if not isinstance(value, dict):
        return value
    if set(value) == {"$tuple"}:
        items = value["$tuple"]
        if not isinstance(items, list):
            raise ValueError("$tuple must contain a JSON array")
        return tuple(_decode_tagged(item) for item in items)
    if set(value) == {"$set"}:
        items = value["$set"]
        if not isinstance(items, list):
            raise ValueError("$set must contain a JSON array")
        try:
            return {_decode_tagged(item) for item in items}
        except TypeError as exc:
            raise ValueError("every decoded $set item must be hashable") from exc
    if set(value) == {"$map"}:
        pairs = value["$map"]
        if not isinstance(pairs, list):
            raise ValueError("$map must contain an array of [key, value] pairs")
        result: dict[Any, Any] = {}
        for pair in pairs:
            if not isinstance(pair, list) or len(pair) != 2:
                raise ValueError("every $map entry must be a two-item JSON array")
            key = _decode_tagged(pair[0])
            mapped_value = _decode_tagged(pair[1])
            try:
                if key in result:
                    raise ValueError(f"duplicate decoded $map key: {key!r}")
                result[key] = mapped_value
            except TypeError as exc:
                raise ValueError(f"decoded $map key is not hashable: {key!r}") from exc
        return result
    return {key: _decode_tagged(item) for key, item in value.items()}


def _read_spec(*, spec_path: str | None, inline_json: str | None) -> dict[str, Any]:
    if spec_path is not None:
        text = sys.stdin.read() if spec_path == "-" else Path(spec_path).read_text(encoding="utf-8")
    elif inline_json is not None:
        text = inline_json
    else:
        raise ValueError("provide --spec, --json, or --list")
    if len(text.encode("utf-8")) > MAX_JSON_BYTES:
        raise ValueError(f"JSON input exceeds the {MAX_JSON_BYTES} byte limit")
    try:
        raw = json.loads(
            text,
            parse_constant=_reject_json_constant,
            object_pairs_hook=_object_without_duplicates,
        )
    except json.JSONDecodeError as exc:
        raise ValueError(f"invalid JSON at line {exc.lineno}, column {exc.colno}: {exc.msg}") from exc
    except RecursionError as exc:
        raise ValueError("JSON input is nested too deeply") from exc
    _validate_value_budget(raw)
    decoded = _decode_tagged(raw)
    _validate_value_budget(decoded)
    if not isinstance(decoded, dict):
        raise ValueError("the top-level JSON specification must be an object")
    return decoded


def _resolve_output(value: Any, renderer: str) -> Path:
    if value is None:
        output_path = Path.cwd() / "output" / f"{renderer.removeprefix('draw_')}.png"
    elif isinstance(value, str) and value.strip():
        output_path = Path(value).expanduser()
        if not output_path.is_absolute():
            output_path = Path.cwd() / output_path
    else:
        raise ValueError("output must be a non-empty path string")
    output_path = output_path.resolve()
    if output_path.suffix.lower() not in ALLOWED_SUFFIXES:
        raise ValueError(f"output suffix must be one of: {sorted(ALLOWED_SUFFIXES)}")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    return output_path


def render_spec(spec: dict[str, Any]) -> dict[str, Any]:
    """Render one specification and return JSON-serializable result metadata."""
    _validate_value_budget(spec)
    if any(not isinstance(key, str) for key in spec):
        raise ValueError("every top-level field name must be a string")
    unknown_fields = set(spec) - ALLOWED_TOP_LEVEL_FIELDS
    if unknown_fields:
        raise ValueError(f"unknown top-level fields: {sorted(unknown_fields)}")
    engine = spec.get("engine", "modern")
    renderer = spec.get("renderer")
    args = spec.get("args", [])
    kwargs = spec.get("kwargs", {})
    if engine not in {"modern", "legacy"}:
        raise ValueError("engine must be 'modern' or 'legacy'")
    if not isinstance(renderer, str):
        raise ValueError("renderer must be a draw_* function name")
    if not isinstance(args, list):
        raise ValueError("args must be a JSON array")
    if not isinstance(kwargs, dict):
        raise ValueError("kwargs must be a JSON object or a tagged $map")
    if any(not isinstance(key, str) for key in kwargs):
        raise ValueError("every kwargs key must be a string")
    reserved = sorted(RESERVED_KWARGS.intersection(kwargs))
    if reserved:
        raise ValueError(
            f"reserved kwargs are controlled by render.py: {reserved}; use top-level output"
        )
    if "dpi" in kwargs:
        dpi = kwargs["dpi"]
        if not isinstance(dpi, int) or isinstance(dpi, bool) or not 36 <= dpi <= 600:
            raise ValueError("dpi must be an integer within 36..600")

    output_path = _resolve_output(spec.get("output"), renderer)
    call_kwargs = dict(kwargs)
    call_kwargs["filename"] = str(output_path)

    legacy_log = ""
    if engine == "modern":
        if renderer not in MODERN_RENDERERS:
            raise ValueError(f"unknown modern renderer {renderer!r}; use --list to inspect choices")
        function = getattr(algorithm_viz, renderer)
        call_kwargs["output"] = "file"
        inspect.signature(function).bind(*args, **call_kwargs)
        result = function(*args, **call_kwargs)
    else:
        if renderer not in LEGACY_RENDERERS:
            raise ValueError(f"unknown legacy renderer {renderer!r}; use --list to inspect choices")
        legacy_module = importlib.import_module("datastruct_viz")
        function = getattr(legacy_module, renderer)
        inspect.signature(function).bind(*args, **call_kwargs)
        captured = StringIO()
        with redirect_stdout(captured):
            result = function(*args, **call_kwargs)
        legacy_log = captured.getvalue().strip()
    result_path = Path(result).resolve() if isinstance(result, (str, Path)) else output_path
    if result_path != output_path:
        raise RuntimeError(
            f"renderer returned an unexpected output path: {result_path}; expected {output_path}"
        )
    if not result_path.is_file():
        raise RuntimeError(f"renderer returned without creating the expected file: {result_path}")
    with result_path.open("rb") as stream:
        header = stream.read(512)
    suffix = result_path.suffix.lower()
    if suffix == ".png" and not header.startswith(b"\x89PNG\r\n\x1a\n"):
        raise RuntimeError("renderer output is not a valid PNG file")
    if suffix == ".pdf" and not header.startswith(b"%PDF"):
        raise RuntimeError("renderer output is not a valid PDF file")
    if suffix == ".svg" and b"<svg" not in header.lower():
        raise RuntimeError("renderer output is not a valid SVG file")
    metadata = {
        "engine": engine,
        "renderer": renderer,
        "path": str(result_path),
        "size_bytes": result_path.stat().st_size,
    }
    if legacy_log:
        metadata["legacy_log"] = legacy_log
    return metadata


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Render an algorithm data structure from a JSON specification.",
    )
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--spec", help="JSON file path, or '-' to read JSON from stdin")
    source.add_argument("--json", dest="inline_json", help="inline JSON specification")
    parser.add_argument("--list", action="store_true", help="list allowed renderer names")
    parser.add_argument("--debug", action="store_true", help="show a traceback on failure")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = _build_parser()
    options = parser.parse_args(argv)
    if options.list:
        print(json.dumps(
            {"modern": list(MODERN_RENDERERS), "legacy": list(LEGACY_RENDERERS)},
            ensure_ascii=False,
            indent=2,
        ))
        return 0
    if options.spec is None and options.inline_json is None:
        parser.error("provide --spec, --json, or --list")
    try:
        spec = _read_spec(spec_path=options.spec, inline_json=options.inline_json)
        print(json.dumps(render_spec(spec), ensure_ascii=False))
        return 0
    except Exception as exc:
        if options.debug:
            raise
        print(f"render error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
