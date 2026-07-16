"""Composable static visualizations for common algorithm data structures.

The module intentionally performs rendering only: callers provide the data and
algorithm state, while this file handles layout, annotations, and output.

Every public ``draw_*`` function supports four output modes:

``file``   save to ``output/`` (or to the supplied path) and return its path;
``figure`` return the Matplotlib Figure without closing it;
``bytes``  return encoded image bytes (PNG by default);
``axes``   return the Axes, useful when composing several views on one canvas.

Pass an existing ``ax`` together with ``output="axes"`` to compose structures.
The legacy ``datastruct_viz.py`` is deliberately independent from this module.
"""

from __future__ import annotations

from bisect import bisect_right
from collections import defaultdict, deque
from dataclasses import dataclass, field
from io import BytesIO
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence
import math
import unicodedata

import matplotlib

matplotlib.use("Agg")
import matplotlib.font_manager as fm
import matplotlib.patches as patches
import matplotlib.pyplot as plt
from matplotlib.axes import Axes
from matplotlib.figure import Figure
from matplotlib.path import Path as MplPath
from matplotlib.patches import Circle, FancyArrowPatch, FancyBboxPatch, Polygon


OUTPUT_DIR = Path("./output")
DPI = 150


@dataclass(frozen=True)
class VizTheme:
    """Colors and typography shared by every renderer."""

    primary: str = "#4A90D9"
    secondary: str = "#6C757D"
    current: str = "#E85D75"
    visited: str = "#8E7CC3"
    candidate: str = "#F5A623"
    selected: str = "#00A6A6"
    success: str = "#66B447"
    error: str = "#D64541"
    path: str = "#FF8C42"
    frontier: str = "#9B59B6"
    disabled: str = "#BDC3C7"
    background: str = "#F8F9FA"
    edge: str = "#34495E"
    text: str = "#2C3E50"
    grid: str = "#D8DEE4"
    empty: str = "#E9ECEF"
    font_size: int = 11
    small_font_size: int = 9

    def state_color(self, state: str | None) -> str:
        if not state:
            return self.primary
        return {
            "default": self.primary,
            "current": self.current,
            "visited": self.visited,
            "candidate": self.candidate,
            "selected": self.selected,
            "success": self.success,
            "error": self.error,
            "path": self.path,
            "frontier": self.frontier,
            "disabled": self.disabled,
            "empty": self.empty,
        }.get(state, state if _looks_like_color(state) else self.primary)


DEFAULT_THEME = VizTheme()


def _looks_like_color(value: str) -> bool:
    return value.startswith("#") or value in {
        "red", "blue", "green", "orange", "purple", "black", "white",
        "gray", "grey", "yellow", "pink", "cyan", "magenta",
    }


def _display_width(value: Any) -> int:
    """Approximate terminal-style width for Latin, CJK, and emoji labels."""
    return sum(
        2 if unicodedata.east_asian_width(char) in {"W", "F"} else 1
        for char in str(value)
    )


def _configure_font() -> None:
    candidates = [
        "Microsoft YaHei", "SimHei", "PingFang SC", "Noto Sans CJK SC",
        "Source Han Sans SC", "WenQuanYi Micro Hei", "Arial Unicode MS",
    ]
    available = {font.name for font in fm.fontManager.ttflist}
    for name in candidates:
        if name in available:
            plt.rcParams["font.family"] = name
            break
    plt.rcParams["axes.unicode_minus"] = False


_configure_font()


def create_canvas(
    rows: int = 1,
    cols: int = 1,
    *,
    figsize: tuple[float, float] = (8, 5),
    title: str | None = None,
) -> tuple[Figure, Any]:
    """Create a canvas for composing several data-structure views."""
    fig, axes = plt.subplots(rows, cols, figsize=figsize, squeeze=False)
    if title:
        fig.suptitle(title, fontsize=15, fontweight="bold", color=DEFAULT_THEME.text)
    return fig, axes


def save_figure(
    fig: Figure,
    filename: str | Path,
    *,
    dpi: int = DPI,
    transparent: bool = False,
    close: bool = True,
) -> str:
    """Save a Figure and return its absolute path."""
    path = Path(filename)
    if not path.is_absolute() and path.parent == Path("."):
        path = OUTPUT_DIR / path
    path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(
        path,
        dpi=dpi,
        bbox_inches="tight",
        facecolor="none" if transparent else "white",
        edgecolor="none",
        transparent=transparent,
    )
    if close:
        plt.close(fig)
    return str(path.resolve())


def figure_to_bytes(
    fig: Figure,
    *,
    format: str = "png",
    dpi: int = DPI,
    transparent: bool = False,
    close: bool = False,
) -> bytes:
    """Encode a Figure in memory without requiring a temporary file."""
    stream = BytesIO()
    fig.savefig(
        stream,
        format=format,
        dpi=dpi,
        bbox_inches="tight",
        facecolor="none" if transparent else "white",
        edgecolor="none",
        transparent=transparent,
    )
    result = stream.getvalue()
    if close:
        plt.close(fig)
    return result


def _prepare_axes(
    ax: Axes | None,
    *,
    figsize: tuple[float, float],
    title: str,
    equal: bool = True,
) -> tuple[Figure, Axes, bool]:
    owns_figure = ax is None
    if ax is None:
        fig, ax = plt.subplots(figsize=figsize)
    else:
        fig = ax.figure
    ax.clear()
    ax.axis("off")
    if equal:
        ax.set_aspect("equal", adjustable="box")
    if title:
        ax.set_title(title, fontsize=14, fontweight="bold", color=DEFAULT_THEME.text, pad=12)
    return fig, ax, owns_figure


def _finish(
    fig: Figure,
    ax: Axes,
    owns_figure: bool,
    *,
    filename: str | Path,
    output: str,
    dpi: int,
    transparent: bool,
) -> Any:
    if output == "axes":
        return ax
    if output == "figure":
        return fig
    if output == "bytes":
        return figure_to_bytes(fig, dpi=dpi, transparent=transparent, close=owns_figure)
    if output == "file":
        return save_figure(fig, filename, dpi=dpi, transparent=transparent, close=owns_figure)
    raise ValueError("output must be one of: file, figure, bytes, axes")


def _normalize_states(states: Mapping[Any, Any] | None) -> dict[Any, str]:
    """Accept either ``item -> state`` or ``state -> iterable[items]``."""
    result: dict[Any, str] = {}
    if not states:
        return result
    for key, value in states.items():
        if isinstance(value, str):
            result[key] = value
        elif isinstance(value, Iterable):
            for item in value:
                result[item] = str(key)
        else:
            result[key] = str(value)
    return result


def _draw_box(
    ax: Axes,
    x: float,
    y: float,
    text: Any,
    *,
    width: float = 0.9,
    height: float = 0.68,
    state: str | None = None,
    color: str | None = None,
    text_color: str = "white",
    theme: VizTheme = DEFAULT_THEME,
    fontsize: int | None = None,
    radius: float = 0.06,
    zorder: int = 3,
) -> FancyBboxPatch:
    face = color or theme.state_color(state)
    box = FancyBboxPatch(
        (x - width / 2, y - height / 2), width, height,
        boxstyle=f"round,pad={radius}", facecolor=face, edgecolor="white",
        linewidth=1.5, zorder=zorder,
    )
    ax.add_patch(box)
    ax.text(
        x, y, str(text), ha="center", va="center", color=text_color,
        fontsize=fontsize or theme.font_size,
        fontweight="bold" if state in {"current", "selected", "success", "path"} else "normal",
        zorder=zorder + 1,
    )
    return box


def _arrow(
    ax: Axes,
    start: tuple[float, float],
    end: tuple[float, float],
    *,
    color: str,
    directed: bool = True,
    curved: float = 0.0,
    dashed: bool = False,
    linewidth: float = 1.6,
    mutation_scale: float = 12,
    zorder: int = 2,
) -> FancyArrowPatch:
    arrow = FancyArrowPatch(
        start,
        end,
        arrowstyle="-|>" if directed else "-",
        mutation_scale=mutation_scale,
        color=color,
        linewidth=linewidth,
        linestyle="--" if dashed else "-",
        connectionstyle=f"arc3,rad={curved}",
        shrinkA=0,
        shrinkB=0,
        zorder=zorder,
    )
    ax.add_patch(arrow)
    return arrow


def _label(
    ax: Axes,
    x: float,
    y: float,
    text: Any,
    *,
    color: str,
    size: int,
    ha: str = "center",
    weight: str = "normal",
    zorder: int = 5,
) -> None:
    ax.text(x, y, str(text), ha=ha, va="center", color=color, fontsize=size,
            fontweight=weight, zorder=zorder)


def _parse_range(item: Any) -> dict[str, Any]:
    if isinstance(item, Mapping):
        result = dict(item)
        result.setdefault("label", "")
        result.setdefault("state", "selected")
        return result
    if len(item) == 2:
        return {"start": item[0], "end": item[1], "label": "", "state": "selected"}
    if len(item) == 3:
        return {"start": item[0], "end": item[1], "label": item[2], "state": "selected"}
    raise ValueError("range must be a mapping or a 2/3-item sequence")


def draw_array(
    values: Sequence[Any],
    *,
    states: Mapping[Any, Any] | None = None,
    pointers: Mapping[str, int] | None = None,
    ranges: Sequence[Any] | None = None,
    index_labels: Sequence[Any] | None = None,
    row_label: str | None = None,
    title: str = "Array",
    filename: str | Path = "array.png",
    ax: Axes | None = None,
    output: str = "file",
    theme: VizTheme = DEFAULT_THEME,
    dpi: int = DPI,
    transparent: bool = False,
) -> Any:
    """Draw a one-dimensional array with semantic states, pointers, and ranges."""
    n = len(values)
    width = max(4.5, n * 1.05 + 2.0)
    fig, ax, owns = _prepare_axes(ax, figsize=(width, 3.3), title=title)
    state_map = _normalize_states(states)
    labels = list(index_labels) if index_labels is not None else list(range(n))
    if len(labels) != n:
        raise ValueError("index_labels must have the same length as values")

    for raw_range in ranges or []:
        spec = _parse_range(raw_range)
        start, end = int(spec["start"]), int(spec["end"])
        if start > end:
            start, end = end, start
        start, end = max(start, 0), min(end, n - 1)
        if start <= end:
            color = theme.state_color(str(spec.get("state", "selected")))
            rect = patches.Rectangle(
                (start - 0.5, -0.42), end - start + 1.0, 0.84,
                facecolor=color, edgecolor=color, alpha=0.13, linewidth=2,
                zorder=0,
            )
            ax.add_patch(rect)
            ax.plot([start - 0.42, start - 0.42, end + 0.42, end + 0.42],
                    [-0.62, -0.78, -0.78, -0.62], color=color, linewidth=1.6)
            if spec.get("label"):
                _label(ax, (start + end) / 2, -0.96, spec["label"],
                       color=color, size=theme.small_font_size, weight="bold")

    for index, value in enumerate(values):
        _draw_box(ax, index, 0, value, state=state_map.get(index), theme=theme)
        _label(ax, index, -0.58, labels[index], color=theme.secondary,
               size=theme.small_font_size)

    grouped: dict[int, list[str]] = defaultdict(list)
    for name, index in (pointers or {}).items():
        if not 0 <= index < n:
            raise ValueError(f"pointer {name!r} index is outside the array")
        grouped[index].append(name)
    max_pointer_rows = 0
    for index, names in grouped.items():
        max_pointer_rows = max(max_pointer_rows, len(names))
        for row, name in enumerate(names):
            y = 0.78 + row * 0.34
            _arrow(ax, (index, y), (index, 0.4), color=theme.candidate)
            _label(ax, index, y + 0.14, name, color=theme.candidate,
                   size=theme.small_font_size, weight="bold")

    if row_label:
        _label(ax, -0.62, 0, row_label, color=theme.text,
               size=theme.font_size, ha="right", weight="bold")
    if n == 0:
        _label(ax, 0, 0, "(empty)", color=theme.disabled, size=theme.font_size, weight="bold")
    ax.set_xlim(-1.0 if row_label else -0.7, max(n - 0.3, 1.1))
    ax.set_ylim(-1.15, max(1.2, 1.25 + max_pointer_rows * 0.34))
    return _finish(fig, ax, owns, filename=filename, output=output, dpi=dpi, transparent=transparent)


def draw_string(
    text: str,
    *,
    pattern: str | None = None,
    pattern_offset: int = 0,
    states: Mapping[Any, Any] | None = None,
    pointers: Mapping[str, int] | None = None,
    ranges: Sequence[Any] | None = None,
    title: str = "String",
    filename: str | Path = "string.png",
    ax: Axes | None = None,
    output: str = "file",
    theme: VizTheme = DEFAULT_THEME,
    dpi: int = DPI,
    transparent: bool = False,
) -> Any:
    """Draw an indexed string and optionally an aligned pattern string."""
    if pattern is None:
        return draw_array(
            list(text), states=states, pointers=pointers, ranges=ranges,
            row_label="text", title=title, filename=filename, ax=ax,
            output=output, theme=theme, dpi=dpi, transparent=transparent,
        )
    total = max(len(text), pattern_offset + len(pattern))
    fig, ax, owns = _prepare_axes(ax, figsize=(max(5, total * 0.95 + 2), 4), title=title)
    state_map = _normalize_states(states)
    for i, char in enumerate(text):
        _draw_box(ax, i, 0.55, char, state=state_map.get(i), theme=theme)
        _label(ax, i, 0.0, i, color=theme.secondary, size=theme.small_font_size)
    for j, char in enumerate(pattern):
        _draw_box(ax, pattern_offset + j, -0.85, char, color=theme.selected, theme=theme)
    _label(ax, -0.62, 0.55, "text", color=theme.text, size=theme.font_size, ha="right", weight="bold")
    _label(ax, -0.62, -0.85, "pattern", color=theme.text, size=theme.font_size, ha="right", weight="bold")
    for name, index in (pointers or {}).items():
        _arrow(ax, (index, 1.45), (index, 0.92), color=theme.candidate)
        _label(ax, index, 1.62, name, color=theme.candidate, size=theme.small_font_size, weight="bold")
    for raw_range in ranges or []:
        spec = _parse_range(raw_range)
        start, end = int(spec["start"]), int(spec["end"])
        color = theme.state_color(str(spec.get("state", "selected")))
        ax.plot([start - 0.4, start - 0.4, end + 0.4, end + 0.4],
                [-0.05, -0.22, -0.22, -0.05], color=color, linewidth=1.5)
        if spec.get("label"):
            _label(ax, (start + end) / 2, -0.38, spec["label"], color=color, size=theme.small_font_size)
    ax.set_xlim(-1.25, max(total - 0.25, 1.5))
    ax.set_ylim(-1.45, 1.95)
    return _finish(fig, ax, owns, filename=filename, output=output, dpi=dpi, transparent=transparent)


def draw_multi_array(
    rows: Mapping[str, Sequence[Any]] | Sequence[Sequence[Any]],
    *,
    states: Mapping[tuple[int, int], str] | None = None,
    column_labels: Sequence[Any] | None = None,
    title: str = "Aligned Arrays",
    filename: str | Path = "multi_array.png",
    ax: Axes | None = None,
    output: str = "file",
    theme: VizTheme = DEFAULT_THEME,
    dpi: int = DPI,
    transparent: bool = False,
) -> Any:
    """Draw several arrays aligned to shared columns."""
    if isinstance(rows, Mapping):
        names, values = list(rows.keys()), [list(v) for v in rows.values()]
    else:
        values = [list(v) for v in rows]
        names = [f"row {i}" for i in range(len(values))]
    cols = max((len(row) for row in values), default=0)
    fig, ax, owns = _prepare_axes(
        ax, figsize=(max(5, cols * 1.0 + 2), max(2.8, len(values) * 1.0 + 1.6)), title=title,
    )
    state_map = dict(states or {})
    for r, row in enumerate(values):
        y = -r
        _label(ax, -0.65, y, names[r], color=theme.text, size=theme.small_font_size, ha="right", weight="bold")
        for c in range(cols):
            if c < len(row):
                _draw_box(ax, c, y, row[c], state=state_map.get((r, c)), theme=theme)
            else:
                _draw_box(ax, c, y, "", state="empty", text_color=theme.secondary, theme=theme)
    for c, label in enumerate(column_labels or range(cols)):
        _label(ax, c, 0.55, label, color=theme.secondary, size=theme.small_font_size)
    if not values:
        _label(ax, 0, 0, "(empty)", color=theme.disabled, size=theme.font_size)
    ax.set_xlim(-1.4, max(cols - 0.3, 1.2))
    ax.set_ylim(-max(len(values) - 0.4, 1.0), 0.9)
    return _finish(fig, ax, owns, filename=filename, output=output, dpi=dpi, transparent=transparent)


def draw_matrix(
    matrix: Sequence[Sequence[Any]],
    *,
    states: Mapping[tuple[int, int], str] | None = None,
    row_labels: Sequence[Any] | None = None,
    col_labels: Sequence[Any] | None = None,
    cell_annotations: Mapping[tuple[int, int], str] | None = None,
    title: str = "Matrix",
    filename: str | Path = "matrix.png",
    ax: Axes | None = None,
    output: str = "file",
    theme: VizTheme = DEFAULT_THEME,
    dpi: int = DPI,
    transparent: bool = False,
) -> Any:
    """Draw a rectangular matrix with row/column labels and cell states."""
    rows = len(matrix)
    cols = max((len(row) for row in matrix), default=0)
    if any(len(row) != cols for row in matrix):
        raise ValueError("matrix must be rectangular")
    fig, ax, owns = _prepare_axes(
        ax, figsize=(max(4, cols * 0.85 + 2), max(3, rows * 0.78 + 2)), title=title,
    )
    state_map = dict(states or {})
    annotations = dict(cell_annotations or {})
    for r, row in enumerate(matrix):
        for c, value in enumerate(row):
            y = rows - 1 - r
            state = state_map.get((r, c))
            color = theme.state_color(state) if state else "white"
            text_color = "white" if state else theme.text
            rect = patches.Rectangle(
                (c - 0.48, y - 0.4), 0.96, 0.8,
                facecolor=color, edgecolor=theme.grid, linewidth=1.2, zorder=1,
            )
            ax.add_patch(rect)
            _label(ax, c, y + (0.08 if (r, c) in annotations else 0), value,
                   color=text_color, size=theme.font_size,
                   weight="bold" if state else "normal")
            if (r, c) in annotations:
                _label(ax, c, y - 0.24, annotations[(r, c)], color=text_color,
                       size=max(6, theme.small_font_size - 2))
    for r, label in enumerate(row_labels or range(rows)):
        _label(ax, -0.7, rows - 1 - r, label, color=theme.secondary,
               size=theme.small_font_size, ha="right")
    for c, label in enumerate(col_labels or range(cols)):
        _label(ax, c, rows - 0.35, label, color=theme.secondary, size=theme.small_font_size)
    if rows == 0 or cols == 0:
        _label(ax, 0, 0, "(empty matrix)", color=theme.disabled, size=theme.font_size)
    ax.set_xlim(-1.1, max(cols - 0.45, 1.0))
    ax.set_ylim(-0.7, max(rows + 0.1, 1.2))
    return _finish(fig, ax, owns, filename=filename, output=output, dpi=dpi, transparent=transparent)


def draw_grid(
    grid: Sequence[Sequence[Any]],
    *,
    states: Mapping[tuple[int, int], str] | None = None,
    start: tuple[int, int] | None = None,
    end: tuple[int, int] | None = None,
    obstacles: Iterable[tuple[int, int]] | None = None,
    path: Sequence[tuple[int, int]] | None = None,
    arrows: Sequence[tuple[tuple[int, int], tuple[int, int]]] | None = None,
    show_coordinates: bool = True,
    title: str = "Grid",
    filename: str | Path = "grid.png",
    ax: Axes | None = None,
    output: str = "file",
    theme: VizTheme = DEFAULT_THEME,
    dpi: int = DPI,
    transparent: bool = False,
) -> Any:
    """Draw a search grid, including obstacles, endpoints, path, and moves."""
    rows = len(grid)
    cols = max((len(row) for row in grid), default=0)
    if any(len(row) != cols for row in grid):
        raise ValueError("grid must be rectangular")
    state_map = dict(states or {})
    obstacle_cells = set(obstacles or [])
    for cell in obstacle_cells:
        state_map[cell] = "disabled"
    for cell in path or []:
        state_map[cell] = "path"
    if start is not None:
        state_map[start] = "success"
    if end is not None:
        state_map[end] = "error"
    fig, ax, owns = _prepare_axes(
        ax, figsize=(max(4, cols * 0.8 + 2), max(3, rows * 0.8 + 2)), title=title,
    )
    for r, row in enumerate(grid):
        for c, value in enumerate(row):
            y = rows - 1 - r
            state = state_map.get((r, c))
            color = theme.state_color(state) if state else "white"
            rect = patches.Rectangle(
                (c - 0.48, y - 0.48), 0.96, 0.96,
                facecolor=color, edgecolor=theme.grid, linewidth=1.3, zorder=1,
            )
            ax.add_patch(rect)
            marker = "S" if (r, c) == start else "E" if (r, c) == end else value
            if (r, c) in obstacle_cells:
                marker = "■" if value in (None, "", 0) else value
            _label(ax, c, y, marker, color="white" if state else theme.text,
                   size=theme.font_size, weight="bold" if state else "normal")
    for source, target in arrows or []:
        sr, sc = source
        tr, tc = target
        _arrow(ax, (sc, rows - 1 - sr), (tc, rows - 1 - tr),
               color=theme.candidate, linewidth=2.0, zorder=4)
    if show_coordinates:
        for r in range(rows):
            _label(ax, -0.7, rows - 1 - r, r, color=theme.secondary,
                   size=theme.small_font_size, ha="right")
        for c in range(cols):
            _label(ax, c, rows - 0.3, c, color=theme.secondary, size=theme.small_font_size)
    if rows == 0 or cols == 0:
        _label(ax, 0, 0, "(empty grid)", color=theme.disabled, size=theme.font_size)
    ax.set_xlim(-1.0, max(cols - 0.45, 1.0))
    ax.set_ylim(-0.7, max(rows + 0.05, 1.2))
    return _finish(fig, ax, owns, filename=filename, output=output, dpi=dpi, transparent=transparent)


def draw_stack(
    values: Sequence[Any],
    *,
    states: Mapping[Any, Any] | None = None,
    pointers: Mapping[str, int] | None = None,
    capacity: int | None = None,
    top_label: str = "top",
    title: str = "Stack",
    filename: str | Path = "stack.png",
    ax: Axes | None = None,
    output: str = "file",
    theme: VizTheme = DEFAULT_THEME,
    dpi: int = DPI,
    transparent: bool = False,
) -> Any:
    """Draw a bottom-to-top stack, including empty capacity slots."""
    size = len(values)
    capacity = size if capacity is None else capacity
    if capacity < size:
        raise ValueError("capacity cannot be smaller than the stack size")
    longest = max((_display_width(value) for value in values), default=1)
    cell_width = max(1.5, min(5.5, 0.72 + longest * 0.13))
    fig, ax, owns = _prepare_axes(
        ax, figsize=(max(4.2, cell_width * 1.65 + 2), max(3, capacity * 0.72 + 2)), title=title,
    )
    state_map = _normalize_states(states)
    for i in range(capacity):
        if i < size:
            _draw_box(ax, 0, i, values[i], width=cell_width, state=state_map.get(i), theme=theme,
                      fontsize=theme.small_font_size if longest > 16 else theme.font_size)
        else:
            _draw_box(ax, 0, i, "", width=cell_width, state="empty",
                      text_color=theme.secondary, theme=theme)
    if size:
        top = size - 1
        right_edge = cell_width / 2
        _arrow(ax, (right_edge + 0.95, top), (right_edge + 0.08, top), color=theme.candidate)
        _label(ax, right_edge + 1.12, top, top_label, color=theme.candidate,
               size=theme.small_font_size, ha="left", weight="bold")
        _label(ax, -cell_width / 2 - 0.3, 0, "bottom", color=theme.secondary,
               size=theme.small_font_size, ha="right")
    else:
        _label(ax, 0, 0, "(empty)", color=theme.disabled, size=theme.font_size, weight="bold")
    for name, index in (pointers or {}).items():
        if not 0 <= index < size:
            raise ValueError(f"pointer {name!r} index is outside the stack")
        left_edge = -cell_width / 2
        _arrow(ax, (left_edge - 0.95, index), (left_edge - 0.08, index), color=theme.selected)
        _label(ax, left_edge - 1.12, index, name, color=theme.selected,
               size=theme.small_font_size, ha="right", weight="bold")
    boundary = patches.Rectangle(
        (-cell_width / 2 - 0.12, -0.45), cell_width + 0.24, max(capacity - 0.1, 0.9), fill=False,
        edgecolor=theme.grid, linestyle="--", linewidth=1.3, zorder=0,
    )
    ax.add_patch(boundary)
    ax.set_xlim(-cell_width / 2 - 1.8, cell_width / 2 + 2.0)
    ax.set_ylim(-0.8, max(capacity - 0.1, 1.2))
    return _finish(fig, ax, owns, filename=filename, output=output, dpi=dpi, transparent=transparent)


def draw_queue(
    values: Sequence[Any],
    *,
    states: Mapping[Any, Any] | None = None,
    pointers: Mapping[str, int] | None = None,
    front_label: str = "front",
    rear_label: str = "rear",
    title: str = "Queue",
    filename: str | Path = "queue.png",
    ax: Axes | None = None,
    output: str = "file",
    theme: VizTheme = DEFAULT_THEME,
    dpi: int = DPI,
    transparent: bool = False,
) -> Any:
    """Draw a FIFO queue with enqueue/dequeue directions."""
    n = len(values)
    fig, ax, owns = _prepare_axes(ax, figsize=(max(5, n * 1.0 + 3), 3.2), title=title)
    state_map = _normalize_states(states)
    for i, value in enumerate(values):
        _draw_box(ax, i, 0, value, state=state_map.get(i), theme=theme)
    if n:
        _label(ax, 0, -0.65, front_label, color=theme.current,
               size=theme.small_font_size, weight="bold")
        _label(ax, n - 1, 0.65, rear_label, color=theme.candidate,
               size=theme.small_font_size, weight="bold")
        _arrow(ax, (n + 0.65, 0.55), (n - 0.45, 0.55), color=theme.candidate)
        _label(ax, n + 0.8, 0.55, "enqueue", color=theme.candidate,
               size=theme.small_font_size, ha="left")
        _arrow(ax, (-0.45, -0.55), (-1.45, -0.55), color=theme.success)
        _label(ax, -1.6, -0.55, "dequeue", color=theme.success,
               size=theme.small_font_size, ha="right")
    else:
        _label(ax, 0, 0, "(empty)", color=theme.disabled, size=theme.font_size, weight="bold")
    for name, index in (pointers or {}).items():
        if not 0 <= index < n:
            raise ValueError(f"pointer {name!r} index is outside the queue")
        _arrow(ax, (index, 1.25), (index, 0.4), color=theme.selected)
        _label(ax, index, 1.42, name, color=theme.selected,
               size=theme.small_font_size, weight="bold")
    ax.set_xlim(-2.0, max(n + 1.8, 2.2))
    ax.set_ylim(-1.1, 1.75)
    return _finish(fig, ax, owns, filename=filename, output=output, dpi=dpi, transparent=transparent)


def draw_deque(
    values: Sequence[Any],
    *,
    states: Mapping[Any, Any] | None = None,
    pointers: Mapping[str, int] | None = None,
    front_label: str = "front",
    rear_label: str = "rear",
    show_operations: bool = True,
    title: str = "Deque",
    filename: str | Path = "deque.png",
    ax: Axes | None = None,
    output: str = "file",
    theme: VizTheme = DEFAULT_THEME,
    dpi: int = DPI,
    transparent: bool = False,
) -> Any:
    """Draw a double-ended queue with operations at both ends."""
    n = len(values)
    longest = max((_display_width(value) for value in values), default=1)
    cell_width = max(0.9, min(3.6, 0.55 + longest * 0.12))
    spacing = cell_width + 0.12
    last_x = (n - 1) * spacing if n else 0
    side_margin = 2.2 if show_operations else 1.0
    fig, ax, owns = _prepare_axes(
        ax, figsize=(max(5.5, last_x + cell_width + side_margin * 2), 3.3), title=title,
    )
    state_map = _normalize_states(states)
    for i, value in enumerate(values):
        _draw_box(ax, i * spacing, 0, value, width=cell_width,
                  state=state_map.get(i), theme=theme,
                  fontsize=theme.small_font_size if longest > 9 else theme.font_size)
    if n:
        front_color = theme.state_color(state_map[0]) if 0 in state_map else theme.current
        rear_color = theme.state_color(state_map[n - 1]) if n - 1 in state_map else theme.candidate
        _label(ax, 0, -0.65, front_label, color=front_color,
               size=theme.small_font_size, weight="bold")
        _label(ax, last_x, -0.65, rear_label, color=rear_color,
               size=theme.small_font_size, weight="bold")
        if show_operations:
            left_edge = -cell_width / 2
            right_edge = last_x + cell_width / 2
            _arrow(ax, (left_edge - 1.15, 0.48), (left_edge - 0.08, 0.48), color=theme.success)
            _arrow(ax, (left_edge - 0.08, -0.48), (left_edge - 1.15, -0.48), color=theme.success)
            _arrow(ax, (right_edge + 1.15, 0.48), (right_edge + 0.08, 0.48), color=theme.candidate)
            _arrow(ax, (right_edge + 0.08, -0.48), (right_edge + 1.15, -0.48), color=theme.candidate)
            _label(ax, left_edge - 1.3, 0.48, "push", color=theme.success,
                   size=theme.small_font_size, ha="right")
            _label(ax, left_edge - 1.3, -0.48, "pop", color=theme.success,
                   size=theme.small_font_size, ha="right")
            _label(ax, right_edge + 1.3, 0.48, "push", color=theme.candidate,
                   size=theme.small_font_size, ha="left")
            _label(ax, right_edge + 1.3, -0.48, "pop", color=theme.candidate,
                   size=theme.small_font_size, ha="left")
    else:
        _label(ax, 0, 0, "(empty)", color=theme.disabled, size=theme.font_size, weight="bold")
    for name, index in (pointers or {}).items():
        if not 0 <= index < n:
            raise ValueError(f"pointer {name!r} index is outside the deque")
        pointer_x = index * spacing
        _arrow(ax, (pointer_x, 1.25), (pointer_x, 0.4), color=theme.selected)
        _label(ax, pointer_x, 1.42, name, color=theme.selected,
               size=theme.small_font_size, weight="bold")
    ax.set_xlim(-cell_width / 2 - side_margin, max(last_x + cell_width / 2 + side_margin, 1.8))
    ax.set_ylim(-1.1, 1.75)
    return _finish(fig, ax, owns, filename=filename, output=output, dpi=dpi, transparent=transparent)


def draw_linked_list(
    values: Sequence[Any],
    *,
    states: Mapping[Any, Any] | None = None,
    pointers: Mapping[str, int | None] | None = None,
    doubly: bool = False,
    circular: bool = False,
    random_links: Sequence[tuple[int, int | None]] | None = None,
    title: str = "Linked List",
    filename: str | Path = "linked_list.png",
    ax: Axes | None = None,
    output: str = "file",
    theme: VizTheme = DEFAULT_THEME,
    dpi: int = DPI,
    transparent: bool = False,
) -> Any:
    """Draw singly, doubly, circular, or random-pointer linked lists."""
    n = len(values)
    spacing = 1.85 if doubly else 1.65
    fig, ax, owns = _prepare_axes(
        ax, figsize=(max(5, n * spacing + 2.5), 4 if (circular or random_links) else 3), title=title,
    )
    state_map = _normalize_states(states)
    node_centers: list[float] = []
    for i, value in enumerate(values):
        x = i * spacing
        node_centers.append(x)
        if doubly:
            _draw_box(ax, x - 0.48, 0, "prev", width=0.55, height=0.55,
                      color=theme.secondary, theme=theme, fontsize=theme.small_font_size - 1)
            _draw_box(ax, x + 0.05, 0, value, width=0.75, height=0.68,
                      state=state_map.get(i), theme=theme)
            _draw_box(ax, x + 0.65, 0, "next", width=0.5, height=0.55,
                      color=theme.secondary, theme=theme, fontsize=theme.small_font_size - 1)
        else:
            _draw_box(ax, x - 0.18, 0, value, width=0.82, state=state_map.get(i), theme=theme)
            _draw_box(ax, x + 0.55, 0, "next", width=0.48, height=0.52,
                      color=theme.secondary, theme=theme, fontsize=theme.small_font_size - 1)
        if i < n - 1:
            start_x = x + (0.92 if doubly else 0.82)
            end_x = (i + 1) * spacing - (0.78 if doubly else 0.62)
            _arrow(ax, (start_x, 0.1), (end_x, 0.1), color=theme.edge)
            if doubly:
                _arrow(ax, (end_x, -0.16), (start_x, -0.16), color=theme.secondary)
    if n:
        if circular:
            start_x = node_centers[-1] + (0.92 if doubly else 0.82)
            end_x = node_centers[0] - (0.78 if doubly else 0.62)
            _arrow(ax, (start_x, -0.08), (end_x, -0.08),
                   color=theme.path, curved=-0.55, linewidth=1.8)
        else:
            tail_x = node_centers[-1] + (0.95 if doubly else 0.85)
            _arrow(ax, (tail_x, 0), (tail_x + 0.55, 0), color=theme.edge)
            _label(ax, tail_x + 0.68, 0, "NULL", color=theme.disabled,
                   size=theme.small_font_size, ha="left", weight="bold")
            if doubly:
                _label(ax, node_centers[0] - 1.0, 0, "NULL", color=theme.disabled,
                       size=theme.small_font_size, ha="right", weight="bold")
        for row, (name, index) in enumerate((pointers or {}).items()):
            if index is None:
                continue
            if not 0 <= index < n:
                raise ValueError(f"pointer {name!r} index is outside the linked list")
            x = node_centers[index]
            y = 0.88 + row * 0.3
            _arrow(ax, (x, y), (x, 0.37), color=theme.candidate)
            _label(ax, x, y + 0.13, name, color=theme.candidate,
                   size=theme.small_font_size, weight="bold")
    else:
        _label(ax, 0, 0, "head → NULL", color=theme.disabled, size=theme.font_size, weight="bold")
    for source, target in random_links or []:
        if not 0 <= source < n or (target is not None and not 0 <= target < n):
            raise ValueError("random link index is outside the linked list")
        sx = node_centers[source]
        if target is None:
            tx = node_centers[-1] + 1.5
            _label(ax, tx + 0.1, -1.15, "NULL", color=theme.disabled,
                   size=theme.small_font_size, ha="left")
        else:
            tx = node_centers[target]
        curve = -0.35 if target is None or target >= source else 0.35
        _arrow(ax, (sx, -0.38), (tx, -0.38), color=theme.visited,
               curved=curve, dashed=True)
        _label(ax, (sx + tx) / 2, -1.0 if curve < 0 else 1.0, "random",
               color=theme.visited, size=theme.small_font_size - 1)
    ax.set_xlim(-1.4, max((n - 1) * spacing + 2.1, 2.0))
    ax.set_ylim(-1.6, max(1.5, 1.2 + 0.3 * len(pointers or {})))
    return _finish(fig, ax, owns, filename=filename, output=output, dpi=dpi, transparent=transparent)


def draw_doubly_linked_list(values: Sequence[Any], **kwargs: Any) -> Any:
    """Convenience wrapper for :func:`draw_linked_list` with backward links."""
    kwargs.setdefault("title", "Doubly Linked List")
    kwargs.setdefault("filename", "doubly_linked_list.png")
    return draw_linked_list(values, doubly=True, **kwargs)


@dataclass
class TreeNode:
    """Input model accepted by :func:`draw_tree`."""

    label: Any
    children: list["TreeNode"] = field(default_factory=list)
    id: Any | None = None
    annotation: str | None = None


def _coerce_tree(node: Any) -> TreeNode:
    if isinstance(node, TreeNode):
        return node
    if isinstance(node, Mapping):
        label = node.get("label", node.get("value", node.get("id", "")))
        children_data = node.get("children")
        if children_data is None:
            children_data = [node.get("left"), node.get("right")]
        return TreeNode(
            label=label,
            children=[_coerce_tree(child) for child in children_data if child is not None],
            id=node.get("id", label),
            annotation=node.get("annotation"),
        )
    return TreeNode(label=node, id=node)


def _layout_tree(root: TreeNode) -> tuple[list[tuple[TreeNode, float, int]], list[tuple[TreeNode, TreeNode]]]:
    positions: list[tuple[TreeNode, float, int]] = []
    edges: list[tuple[TreeNode, TreeNode]] = []
    leaf_cursor = 0.0

    def visit(node: TreeNode, depth: int) -> float:
        nonlocal leaf_cursor
        child_x: list[float] = []
        for child in node.children:
            edges.append((node, child))
            child_x.append(visit(child, depth + 1))
        if child_x:
            x = (child_x[0] + child_x[-1]) / 2
        else:
            x = leaf_cursor
            leaf_cursor += 1.2
        positions.append((node, x, depth))
        return x

    visit(root, 0)
    return positions, edges


def draw_tree(
    tree: TreeNode | Mapping[str, Any] | Any,
    *,
    states: Mapping[Any, Any] | None = None,
    edge_labels: Mapping[tuple[Any, Any], str] | None = None,
    orientation: str = "vertical",
    title: str = "Tree",
    filename: str | Path = "tree.png",
    ax: Axes | None = None,
    output: str = "file",
    theme: VizTheme = DEFAULT_THEME,
    dpi: int = DPI,
    transparent: bool = False,
) -> Any:
    """Draw a general N-ary tree from nested mappings or ``TreeNode`` objects."""
    root = _coerce_tree(tree)
    positions, edges = _layout_tree(root)
    state_map = _normalize_states(states)
    max_depth = max((depth for _, _, depth in positions), default=0)
    max_x = max((x for _, x, _ in positions), default=0)
    fig, ax, owns = _prepare_axes(
        ax,
        figsize=(max(5, max_x * 0.9 + 3), max(3.5, max_depth * 1.35 + 2.5)),
        title=title,
    )
    coords: dict[int, tuple[float, float]] = {}
    for node, x, depth in positions:
        coords[id(node)] = (x, -depth * 1.25)
    if orientation == "horizontal":
        coords = {key: (-y, -x) for key, (x, y) in coords.items()}
    elif orientation != "vertical":
        raise ValueError("orientation must be vertical or horizontal")
    labels = dict(edge_labels or {})
    for parent, child in edges:
        px, py = coords[id(parent)]
        cx, cy = coords[id(child)]
        dx, dy = cx - px, cy - py
        dist = math.hypot(dx, dy) or 1
        _arrow(
            ax,
            (px + dx / dist * 0.32, py + dy / dist * 0.32),
            (cx - dx / dist * 0.32, cy - dy / dist * 0.32),
            color=theme.edge,
            directed=False,
        )
        parent_key = parent.id if parent.id is not None else parent.label
        child_key = child.id if child.id is not None else child.label
        if (parent_key, child_key) in labels:
            _label(ax, (px + cx) / 2, (py + cy) / 2, labels[(parent_key, child_key)],
                   color=theme.candidate, size=theme.small_font_size)
    for node, _, _ in positions:
        x, y = coords[id(node)]
        key = node.id if node.id is not None else node.label
        state = state_map.get(key, state_map.get(node.label))
        circle = Circle((x, y), 0.32, facecolor=theme.state_color(state),
                        edgecolor="white", linewidth=1.6, zorder=3)
        ax.add_patch(circle)
        _label(ax, x, y, node.label, color="white", size=theme.font_size,
               weight="bold" if state else "normal")
        if node.annotation:
            _label(ax, x + 0.43, y - 0.12, node.annotation, color=theme.secondary,
                   size=theme.small_font_size - 1, ha="left")
    all_x = [xy[0] for xy in coords.values()] or [0]
    all_y = [xy[1] for xy in coords.values()] or [0]
    ax.set_xlim(min(all_x) - 1.0, max(all_x) + 1.0)
    ax.set_ylim(min(all_y) - 0.9, max(all_y) + 0.9)
    return _finish(fig, ax, owns, filename=filename, output=output, dpi=dpi, transparent=transparent)


def _binary_from_level(values: Sequence[Any], index: int = 0) -> TreeNode | None:
    if index >= len(values) or values[index] is None:
        return None
    children = [
        child for child in (
            _binary_from_level(values, 2 * index + 1),
            _binary_from_level(values, 2 * index + 2),
        ) if child is not None
    ]
    return TreeNode(label=values[index], id=index, children=children, annotation=f"[{index}]")


def draw_binary_tree(
    tree: Sequence[Any] | Mapping[str, Any] | TreeNode,
    *,
    states: Mapping[Any, Any] | None = None,
    title: str = "Binary Tree",
    filename: str | Path = "binary_tree.png",
    ax: Axes | None = None,
    output: str = "file",
    theme: VizTheme = DEFAULT_THEME,
    dpi: int = DPI,
    transparent: bool = False,
) -> Any:
    """Draw a binary tree from a level-order array or nested tree model."""
    if isinstance(tree, Sequence) and not isinstance(tree, (str, bytes, bytearray)):
        root = _binary_from_level(tree)
        if root is None:
            root = TreeNode("∅", id=0, annotation="empty tree")
    else:
        root = _coerce_tree(tree)
    return draw_tree(
        root, states=states, title=title, filename=filename, ax=ax,
        output=output, theme=theme, dpi=dpi, transparent=transparent,
    )


def draw_heap(
    values: Sequence[Any],
    *,
    heap_type: str = "min",
    states: Mapping[Any, Any] | None = None,
    title: str = "Binary Heap",
    filename: str | Path = "heap.png",
    output: str = "file",
    theme: VizTheme = DEFAULT_THEME,
    dpi: int = DPI,
    transparent: bool = False,
) -> Any:
    """Draw the array and complete-binary-tree views of a heap together."""
    fig, axes = create_canvas(2, 1, figsize=(max(6, len(values) * 0.95 + 2), 7), title=title)
    draw_array(
        values, states=states, row_label="heap", title="Array representation",
        ax=axes[0, 0], output="axes", theme=theme,
    )
    tree_states = dict(_normalize_states(states))
    if values:
        tree_states.setdefault(0, "current")
    draw_binary_tree(
        values, states=tree_states,
        title=f"{heap_type.capitalize()}-heap tree representation",
        ax=axes[1, 0], output="axes", theme=theme,
    )
    fig.tight_layout(rect=(0, 0, 1, 0.96))
    return _finish(fig, axes[1, 0], True, filename=filename, output=output,
                   dpi=dpi, transparent=transparent)


def _parse_edges(edges: Sequence[Any]) -> list[tuple[Any, Any, Any | None]]:
    parsed: list[tuple[Any, Any, Any | None]] = []
    for edge in edges:
        if isinstance(edge, Mapping):
            parsed.append((edge["source"], edge["target"], edge.get("weight", edge.get("label"))))
        elif len(edge) == 2:
            parsed.append((edge[0], edge[1], None))
        elif len(edge) == 3:
            parsed.append((edge[0], edge[1], edge[2]))
        else:
            raise ValueError("each edge must have source, target, and optional weight")
    return parsed


def _hierarchical_graph_layout(nodes: Sequence[Any], edges: Sequence[tuple[Any, Any, Any]]) -> dict[Any, tuple[float, float]]:
    incoming = {node: 0 for node in nodes}
    outgoing: dict[Any, list[Any]] = defaultdict(list)
    for source, target, _ in edges:
        outgoing[source].append(target)
        incoming[target] = incoming.get(target, 0) + 1
    queue = deque(node for node in nodes if incoming.get(node, 0) == 0)
    level = {node: 0 for node in queue}
    seen: set[Any] = set()
    while queue:
        node = queue.popleft()
        seen.add(node)
        for target in outgoing[node]:
            level[target] = max(level.get(target, 0), level[node] + 1)
            incoming[target] -= 1
            if incoming[target] == 0:
                queue.append(target)
    for node in nodes:
        if node not in seen:
            level.setdefault(node, 0)
    grouped: dict[int, list[Any]] = defaultdict(list)
    for node in nodes:
        grouped[level[node]].append(node)
    positions: dict[Any, tuple[float, float]] = {}
    max_width = max((len(group) for group in grouped.values()), default=1)
    for depth, group in grouped.items():
        for index, node in enumerate(group):
            positions[node] = ((index - (len(group) - 1) / 2) * 1.7, -depth * 1.4)
    if max_width == 1 and len(nodes) > 1:
        positions = {node: (x, y) for node, (x, y) in positions.items()}
    return positions


def draw_graph(
    edges: Sequence[Any],
    *,
    nodes: Sequence[Any] | None = None,
    directed: bool = True,
    layout: str = "circular",
    positions: Mapping[Any, tuple[float, float]] | None = None,
    node_states: Mapping[Any, Any] | None = None,
    edge_states: Mapping[tuple[Any, Any], str] | None = None,
    path: Sequence[Any] | None = None,
    node_annotations: Mapping[Any, str] | None = None,
    title: str = "Graph",
    filename: str | Path = "graph.png",
    ax: Axes | None = None,
    output: str = "file",
    theme: VizTheme = DEFAULT_THEME,
    dpi: int = DPI,
    transparent: bool = False,
) -> Any:
    """Draw directed, undirected, weighted, and algorithm-state graphs."""
    parsed = _parse_edges(edges)
    if nodes is None:
        discovered: list[Any] = []
        for source, target, _ in parsed:
            if source not in discovered:
                discovered.append(source)
            if target not in discovered:
                discovered.append(target)
        nodes = discovered
    else:
        nodes = list(nodes)
    try:
        if len(set(nodes)) != len(nodes):
            raise ValueError("nodes cannot contain duplicates")
    except TypeError as exc:
        raise TypeError("graph nodes must be hashable") from exc
    for source, target, _ in parsed:
        if source not in nodes or target not in nodes:
            raise ValueError("every edge endpoint must be present in nodes")
    node_radii = {
        node: max(0.36, min(0.9, 0.24 + _display_width(node) * 0.045))
        for node in nodes
    }
    max_node_radius = max(node_radii.values(), default=0.36)
    if positions is not None:
        pos = dict(positions)
        missing = [node for node in nodes if node not in pos]
        if missing:
            raise ValueError(f"positions missing nodes: {missing}")
    elif layout == "hierarchical":
        pos = _hierarchical_graph_layout(nodes, parsed)
        horizontal_scale = max(1.0, max_node_radius / 0.36 * 1.15)
        vertical_scale = max(1.0, (2 * max_node_radius + 0.65) / 1.4)
        pos = {
            node: (x * horizontal_scale, y * vertical_scale)
            for node, (x, y) in pos.items()
        }
    elif layout == "circular":
        radius = max(1.6 + max_node_radius, len(nodes) * 0.34 + max_node_radius)
        pos = {
            node: (
                radius * math.cos(2 * math.pi * i / max(len(nodes), 1) - math.pi / 2),
                radius * math.sin(2 * math.pi * i / max(len(nodes), 1) - math.pi / 2),
            ) for i, node in enumerate(nodes)
        }
    else:
        raise ValueError("layout must be circular or hierarchical, or positions must be supplied")
    width = max(5, (max((x for x, _ in pos.values()), default=1) -
                    min((x for x, _ in pos.values()), default=-1)) * 1.3 + 3)
    height = max(4, (max((y for _, y in pos.values()), default=1) -
                     min((y for _, y in pos.values()), default=-1)) * 1.2 + 3)
    fig, ax, owns = _prepare_axes(ax, figsize=(width, height), title=title)
    distinct_y = sorted(set(y for _, y in pos.values()))
    level_gap = min(
        (right - left for left, right in zip(distinct_y, distinct_y[1:])),
        default=0,
    )
    graph_left = min((x for x, _ in pos.values()), default=0.0)
    graph_right = max((x for x, _ in pos.values()), default=0.0)
    graph_center = (graph_left + graph_right) / 2
    node_state_map = _normalize_states(node_states)
    edge_state_map = dict(edge_states or {})
    path_nodes = list(path or [])
    path_edges = set(zip(path_nodes, path_nodes[1:]))
    for node in path_nodes:
        node_state_map[node] = "path"
    for source, target, weight in parsed:
        x1, y1 = pos[source]
        x2, y2 = pos[target]
        state = edge_state_map.get((source, target))
        if (source, target) in path_edges or (not directed and (target, source) in path_edges):
            state = "path"
        color = theme.state_color(state) if state else theme.edge
        if source == target:
            node_radius = node_radii[source]
            loop = FancyArrowPatch(
                (x1 - node_radius * 0.5, y1 + node_radius * 0.82),
                (x1 + node_radius * 0.5, y1 + node_radius * 0.82),
                arrowstyle="-|>" if directed else "-", mutation_scale=12,
                connectionstyle="arc3,rad=-1.7", color=color,
                linewidth=2.6 if state else 1.5, zorder=1,
            )
            ax.add_patch(loop)
            if weight is not None:
                _label(ax, x1, y1 + node_radius + 0.55, weight,
                       color=color, size=theme.small_font_size)
            continue
        dx, dy = x2 - x1, y2 - y1
        dist = math.hypot(dx, dy) or 1
        start_radius = node_radii[source] + 0.03
        end_radius = node_radii[target] + 0.03
        start = (x1 + dx / dist * start_radius, y1 + dy / dist * start_radius)
        end = (x2 - dx / dist * end_radius, y2 - dy / dist * end_radius)
        label_position = ((x1 + x2) / 2, (y1 + y2) / 2)
        long_hierarchical_edge = (
            layout == "hierarchical"
            and level_gap
            and abs(y2 - y1) > level_gap * 1.4
        )
        if long_hierarchical_edge:
            # A single shallow arc can disappear behind an intermediate node.
            # Route skip-level edges through an outside corridor instead.  The
            # source corridor sits between ranks; the route then descends on
            # the outside and enters a distinct side of the target node.
            vertical_direction = -1 if y2 < y1 else 1
            outside_side = 1 if (x1 + x2) / 2 >= graph_center else -1
            outside_x = (
                graph_right + max_node_radius + 0.65
                if outside_side > 0
                else graph_left - max_node_radius - 0.65
            )
            corridor_offset = max_node_radius + 0.22
            source_lane_y = y1 + vertical_direction * corridor_offset
            target_lane_y = y2
            routed_start = (x1, y1 + vertical_direction * start_radius)
            approach = (
                x2 + outside_side * (end_radius + 0.3),
                target_lane_y,
            )
            approach_dx, approach_dy = approach[0] - x2, approach[1] - y2
            approach_distance = math.hypot(approach_dx, approach_dy) or 1
            routed_end = (
                x2 + approach_dx / approach_distance * end_radius,
                y2 + approach_dy / approach_distance * end_radius,
            )
            route = MplPath(
                [
                    routed_start,
                    (x1, source_lane_y),
                    (outside_x, source_lane_y),
                    (outside_x, target_lane_y),
                    approach,
                    routed_end,
                ],
                [MplPath.MOVETO] + [MplPath.LINETO] * 5,
            )
            routed_arrow = FancyArrowPatch(
                path=route,
                arrowstyle="-|>" if directed else "-",
                mutation_scale=12,
                color=color,
                linewidth=2.6 if state else 1.5,
                zorder=1,
            )
            ax.add_patch(routed_arrow)
            label_position = (
                outside_x - outside_side * 0.13,
                (source_lane_y + target_lane_y) / 2,
            )
        else:
            _arrow(ax, start, end, color=color, directed=directed,
                   linewidth=2.6 if state else 1.5, zorder=1)
        if weight is not None:
            mx, my = label_position
            ax.text(mx, my, str(weight), ha="center", va="center",
                    fontsize=theme.small_font_size, color=color,
                    bbox={"boxstyle": "round,pad=0.2", "fc": "white", "ec": color, "alpha": 0.95},
                    zorder=4)
    annotations = dict(node_annotations or {})
    for node in nodes:
        x, y = pos[node]
        state = node_state_map.get(node)
        node_radius = node_radii[node]
        circle = Circle((x, y), node_radius, facecolor=theme.state_color(state),
                        edgecolor="white", linewidth=1.8, zorder=3)
        ax.add_patch(circle)
        label_size = theme.small_font_size if _display_width(node) > 7 else theme.font_size
        _label(ax, x, y, node, color="white", size=label_size, weight="bold")
        if node in annotations:
            _label(ax, x, y - node_radius - 0.24, annotations[node], color=theme.secondary,
                   size=theme.small_font_size - 1)
    if not nodes:
        _label(ax, 0, 0, "(empty graph)", color=theme.disabled, size=theme.font_size)
        pos = {None: (0, 0)}
    xs, ys = [x for x, _ in pos.values()], [y for _, y in pos.values()]
    ax.set_xlim(min(xs) - max_node_radius - 0.9, max(xs) + max_node_radius + 0.9)
    ax.set_ylim(min(ys) - max_node_radius - 0.75, max(ys) + max_node_radius + 0.75)
    return _finish(fig, ax, owns, filename=filename, output=output, dpi=dpi, transparent=transparent)


def draw_circular_queue(
    slots: Sequence[Any | None],
    *,
    front: int | None = None,
    rear: int | None = None,
    states: Mapping[Any, Any] | None = None,
    title: str = "Circular Queue",
    filename: str | Path = "circular_queue.png",
    ax: Axes | None = None,
    output: str = "file",
    theme: VizTheme = DEFAULT_THEME,
    dpi: int = DPI,
    transparent: bool = False,
) -> Any:
    """Draw fixed-capacity queue slots arranged around a ring."""
    n = len(slots)
    if n == 0:
        raise ValueError("circular queue must contain at least one slot")
    for name, index in (("front", front), ("rear", rear)):
        if index is not None and not 0 <= index < n:
            raise ValueError(f"{name} index is outside the circular queue")
    radius = max(1.8, n * 0.19)
    fig, ax, owns = _prepare_axes(ax, figsize=(6, 6), title=title)
    state_map = _normalize_states(states)
    coords: list[tuple[float, float]] = []
    for i, value in enumerate(slots):
        angle = math.pi / 2 - 2 * math.pi * i / n
        x, y = radius * math.cos(angle), radius * math.sin(angle)
        coords.append((x, y))
        state = state_map.get(i, "empty" if value is None else None)
        _draw_box(
            ax, x, y, "EMPTY" if value is None else value,
            width=0.82, height=0.58, state=state,
            text_color=theme.secondary if value is None else "white",
            theme=theme, fontsize=theme.small_font_size,
        )
        _label(ax, x, y - 0.48, f"[{i}]", color=theme.secondary,
               size=theme.small_font_size - 1)
    for name, index, color, offset in (
        ("front", front, theme.current, 0.0),
        ("rear", rear, theme.candidate, 0.22),
    ):
        if index is None:
            continue
        x, y = coords[index]
        norm = math.hypot(x, y) or 1
        outer = ((radius + 1.0 + offset) * x / norm, (radius + 1.0 + offset) * y / norm)
        inner = ((radius + 0.48) * x / norm, (radius + 0.48) * y / norm)
        _arrow(ax, outer, inner, color=color)
        _label(ax, outer[0], outer[1] + 0.18, name, color=color,
               size=theme.small_font_size, weight="bold")
    circle = Circle((0, 0), radius + 0.12, fill=False, edgecolor=theme.grid,
                    linewidth=1.2, linestyle="--", zorder=0)
    ax.add_patch(circle)
    limit = radius + 1.7
    ax.set_xlim(-limit, limit)
    ax.set_ylim(-limit, limit)
    return _finish(fig, ax, owns, filename=filename, output=output, dpi=dpi, transparent=transparent)


def draw_table(
    table: Sequence[Sequence[Any]],
    **kwargs: Any,
) -> Any:
    """Semantic alias for :func:`draw_matrix`."""
    kwargs.setdefault("title", "Table")
    kwargs.setdefault("filename", "table.png")
    return draw_matrix(table, **kwargs)


def draw_dp_table(
    table: Sequence[Sequence[Any]],
    *,
    states: Mapping[tuple[int, int], str] | None = None,
    row_labels: Sequence[Any] | None = None,
    col_labels: Sequence[Any] | None = None,
    dependencies: Sequence[tuple[tuple[int, int], tuple[int, int]]] | None = None,
    current: tuple[int, int] | None = None,
    answer: tuple[int, int] | None = None,
    formulas: Mapping[tuple[int, int], str] | None = None,
    title: str = "Dynamic Programming Table",
    filename: str | Path = "dp_table.png",
    ax: Axes | None = None,
    output: str = "file",
    theme: VizTheme = DEFAULT_THEME,
    dpi: int = DPI,
    transparent: bool = False,
) -> Any:
    """Draw a DP table with state transitions and formula annotations."""
    rows = len(table)
    cols = max((len(row) for row in table), default=0)
    if any(len(row) != cols for row in table):
        raise ValueError("DP table must be rectangular")
    state_map = dict(states or {})
    if current is not None:
        state_map[current] = "current"
    if answer is not None:
        state_map[answer] = "success"
    fig, ax, owns = _prepare_axes(
        ax, figsize=(max(5, cols * 0.92 + 2), max(3.5, rows * 0.82 + 2)), title=title,
    )
    formula_map = dict(formulas or {})
    for r, row in enumerate(table):
        for c, value in enumerate(row):
            y = rows - 1 - r
            state = state_map.get((r, c))
            color = theme.state_color(state) if state else "white"
            text_color = "white" if state else theme.text
            rect = patches.Rectangle(
                (c - 0.48, y - 0.4), 0.96, 0.8,
                facecolor=color, edgecolor=theme.grid, linewidth=1.2, zorder=1,
            )
            ax.add_patch(rect)
            _label(ax, c, y + (0.09 if (r, c) in formula_map else 0), value,
                   color=text_color, size=theme.font_size,
                   weight="bold" if state else "normal")
            if (r, c) in formula_map:
                _label(ax, c, y - 0.23, formula_map[(r, c)], color=text_color,
                       size=max(6, theme.small_font_size - 3))
    for r, label in enumerate(row_labels or range(rows)):
        _label(ax, -0.7, rows - 1 - r, label, color=theme.secondary,
               size=theme.small_font_size, ha="right")
    for c, label in enumerate(col_labels or range(cols)):
        _label(ax, c, rows - 0.35, label, color=theme.secondary,
               size=theme.small_font_size)
    for source, target in dependencies or []:
        sr, sc = source
        tr, tc = target
        if not (0 <= sr < rows and 0 <= tr < rows and 0 <= sc < cols and 0 <= tc < cols):
            raise ValueError("DP dependency cell is outside the table")
        start = (sc, rows - 1 - sr)
        end = (tc, rows - 1 - tr)
        dx, dy = end[0] - start[0], end[1] - start[1]
        dist = math.hypot(dx, dy) or 1
        _arrow(
            ax,
            (start[0] + dx / dist * 0.25, start[1] + dy / dist * 0.25),
            (end[0] - dx / dist * 0.3, end[1] - dy / dist * 0.3),
            color=theme.path,
            curved=0.12,
            linewidth=2.0,
            zorder=4,
        )
    if rows == 0 or cols == 0:
        _label(ax, 0, 0, "(empty DP table)", color=theme.disabled, size=theme.font_size)
    ax.set_xlim(-1.1, max(cols - 0.4, 1.0))
    ax.set_ylim(-0.7, max(rows + 0.1, 1.2))
    return _finish(fig, ax, owns, filename=filename, output=output, dpi=dpi, transparent=transparent)


def _parse_interval(item: Any, index: int) -> dict[str, Any]:
    if isinstance(item, Mapping):
        spec = dict(item)
    elif len(item) == 2:
        spec = {"start": item[0], "end": item[1]}
    elif len(item) == 3:
        spec = {"start": item[0], "end": item[1], "label": item[2]}
    else:
        raise ValueError("interval must be a mapping or a 2/3-item sequence")
    if "start" not in spec or "end" not in spec:
        raise ValueError("interval requires start and end")
    if spec["start"] > spec["end"]:
        raise ValueError("interval start cannot be greater than end")
    spec.setdefault("label", f"I{index}")
    spec.setdefault("state", "default")
    spec.setdefault("closed", (True, True))
    spec.setdefault("lane", index)
    return spec


def draw_intervals(
    intervals: Sequence[Any],
    *,
    scan_position: float | None = None,
    axis_label: str | None = None,
    title: str = "Intervals",
    filename: str | Path = "intervals.png",
    ax: Axes | None = None,
    output: str = "file",
    theme: VizTheme = DEFAULT_THEME,
    dpi: int = DPI,
    transparent: bool = False,
) -> Any:
    """Draw closed/open intervals, scheduling lanes, and a sweep position."""
    specs = [_parse_interval(item, i) for i, item in enumerate(intervals)]
    lanes = max((int(spec["lane"]) for spec in specs), default=0) + 1
    values = [float(spec[key]) for spec in specs for key in ("start", "end")]
    if scan_position is not None:
        values.append(float(scan_position))
    xmin, xmax = (min(values), max(values)) if values else (0.0, 1.0)
    span = max(xmax - xmin, 1.0)
    fig, ax, owns = _prepare_axes(
        ax, figsize=(max(6, span * 0.65 + 3), max(3, lanes * 0.75 + 2)), title=title, equal=False,
    )
    baseline = -0.55
    ax.plot([xmin - span * 0.06, xmax + span * 0.06], [baseline, baseline],
            color=theme.edge, linewidth=1.5, zorder=1)
    for tick in sorted(set(values)):
        ax.plot([tick, tick], [baseline - 0.08, baseline + 0.08], color=theme.edge, linewidth=1)
        _label(ax, tick, baseline - 0.28, f"{tick:g}", color=theme.secondary,
               size=theme.small_font_size)
    for spec in specs:
        start, end = float(spec["start"]), float(spec["end"])
        lane = int(spec["lane"])
        y = lane * 0.65
        color = theme.state_color(str(spec["state"]))
        ax.plot([start, end], [y, y], color=color, linewidth=7, solid_capstyle="butt", zorder=2)
        left_closed, right_closed = spec["closed"]
        for x, closed in ((start, left_closed), (end, right_closed)):
            marker = Circle((x, y), span * 0.012 + 0.035,
                            facecolor=color if closed else "white", edgecolor=color,
                            linewidth=2, zorder=3)
            ax.add_patch(marker)
        _label(ax, (start + end) / 2, y + 0.25, spec["label"], color=color,
               size=theme.small_font_size, weight="bold")
    if scan_position is not None:
        ax.axvline(scan_position, color=theme.current, linewidth=2, linestyle="--", zorder=4)
        _label(ax, scan_position, lanes * 0.65 + 0.15, "scan", color=theme.current,
               size=theme.small_font_size, weight="bold")
    if axis_label:
        _label(ax, xmax + span * 0.08, baseline, axis_label, color=theme.text,
               size=theme.small_font_size, ha="left")
    if not specs:
        _label(ax, 0.5, 0, "(no intervals)", color=theme.disabled, size=theme.font_size)
    ax.set_xlim(xmin - span * 0.12, xmax + span * 0.16)
    ax.set_ylim(baseline - 0.55, max(lanes * 0.65 + 0.35, 1.0))
    return _finish(fig, ax, owns, filename=filename, output=output, dpi=dpi, transparent=transparent)


def draw_timeline(intervals: Sequence[Any], **kwargs: Any) -> Any:
    """Semantic alias for interval scheduling visualizations."""
    kwargs.setdefault("title", "Timeline")
    kwargs.setdefault("filename", "timeline.png")
    kwargs.setdefault("axis_label", "time")
    return draw_intervals(intervals, **kwargs)


def draw_mapping(
    mapping: Mapping[Any, Any],
    *,
    key_states: Mapping[Any, Any] | None = None,
    value_states: Mapping[Any, Any] | None = None,
    title: str = "Mapping",
    filename: str | Path = "mapping.png",
    ax: Axes | None = None,
    output: str = "file",
    theme: VizTheme = DEFAULT_THEME,
    dpi: int = DPI,
    transparent: bool = False,
) -> Any:
    """Draw a logical key-to-value mapping without exposing hash internals."""
    items = list(mapping.items())
    fig, ax, owns = _prepare_axes(
        ax, figsize=(5.5, max(3, len(items) * 0.82 + 1.8)), title=title,
    )
    key_state_map = _normalize_states(key_states)
    value_state_map = _normalize_states(value_states)
    for row, (key, value) in enumerate(items):
        y = len(items) - 1 - row
        _draw_box(ax, -1.0, y, key, width=1.35, state=key_state_map.get(key), theme=theme)
        _draw_box(ax, 1.15, y, value, width=1.45, state=value_state_map.get(key), theme=theme)
        _arrow(ax, (-0.3, y), (0.4, y), color=theme.edge)
    _label(ax, -1.0, len(items) + 0.05, "key", color=theme.secondary,
           size=theme.small_font_size, weight="bold")
    _label(ax, 1.15, len(items) + 0.05, "value", color=theme.secondary,
           size=theme.small_font_size, weight="bold")
    if not items:
        _label(ax, 0, 0, "{}", color=theme.disabled, size=theme.font_size, weight="bold")
    ax.set_xlim(-2.1, 2.25)
    ax.set_ylim(-0.8, max(len(items) + 0.4, 1.2))
    return _finish(fig, ax, owns, filename=filename, output=output, dpi=dpi, transparent=transparent)


def draw_set(
    values: Iterable[Any],
    *,
    states: Mapping[Any, Any] | None = None,
    title: str = "Set",
    filename: str | Path = "set.png",
    ax: Axes | None = None,
    output: str = "file",
    theme: VizTheme = DEFAULT_THEME,
    dpi: int = DPI,
    transparent: bool = False,
) -> Any:
    """Draw the logical contents of a set."""
    unique = list(dict.fromkeys(values))
    if isinstance(values, (set, frozenset)):
        unique.sort(key=str)
    state_map = _normalize_states(states)
    cols = max(1, math.ceil(math.sqrt(len(unique))))
    rows = math.ceil(len(unique) / cols) if unique else 1
    fig, ax, owns = _prepare_axes(
        ax, figsize=(max(4, cols * 1.35 + 2), max(3, rows * 1.1 + 2)), title=title,
    )
    for i, value in enumerate(unique):
        row, col = divmod(i, cols)
        x, y = col * 1.25, -row
        circle = Circle((x, y), 0.39, facecolor=theme.state_color(state_map.get(value)),
                        edgecolor="white", linewidth=1.6, zorder=3)
        ax.add_patch(circle)
        _label(ax, x, y, value, color="white", size=theme.font_size, weight="bold")
    boundary = patches.Ellipse(
        ((cols - 1) * 1.25 / 2, -(rows - 1) / 2),
        max(2.0, cols * 1.35), max(1.6, rows * 1.15),
        fill=False, edgecolor=theme.grid, linewidth=1.6, linestyle="--", zorder=0,
    )
    ax.add_patch(boundary)
    if not unique:
        _label(ax, 0, 0, "(empty)", color=theme.disabled, size=theme.font_size, weight="bold")
    ax.set_xlim(-1.2, max((cols - 1) * 1.25 + 1.2, 1.2))
    ax.set_ylim(-max(rows - 1, 0) - 1.0, 1.0)
    return _finish(fig, ax, owns, filename=filename, output=output, dpi=dpi, transparent=transparent)


def draw_buckets(
    buckets: Mapping[Any, Sequence[Any]] | Sequence[Sequence[Any]],
    *,
    states: Mapping[tuple[Any, int], str] | None = None,
    title: str = "Buckets",
    filename: str | Path = "buckets.png",
    ax: Axes | None = None,
    output: str = "file",
    theme: VizTheme = DEFAULT_THEME,
    dpi: int = DPI,
    transparent: bool = False,
) -> Any:
    """Draw bucket contents for counting, radix, or bucket-sort diagrams."""
    if isinstance(buckets, Mapping):
        items = [(key, list(values)) for key, values in buckets.items()]
    else:
        items = [(i, list(values)) for i, values in enumerate(buckets)]
    max_depth = max((len(values) for _, values in items), default=0)
    fig, ax, owns = _prepare_axes(
        ax, figsize=(max(5, len(items) * 1.1 + 2), max(3, max_depth * 0.72 + 2.5)), title=title,
    )
    state_map = dict(states or {})
    for col, (key, values) in enumerate(items):
        _label(ax, col, 0.65, f"[{key}]", color=theme.text,
               size=theme.small_font_size, weight="bold")
        for depth, value in enumerate(values):
            _draw_box(ax, col, -depth * 0.72, value, width=0.78, height=0.55,
                      state=state_map.get((key, depth)), theme=theme,
                      fontsize=theme.small_font_size)
        if not values:
            _draw_box(ax, col, 0, "empty", width=0.78, height=0.55,
                      state="empty", text_color=theme.secondary, theme=theme)
    if not items:
        _label(ax, 0, 0, "(no buckets)", color=theme.disabled, size=theme.font_size)
    ax.set_xlim(-0.9, max(len(items) - 0.1, 1.2))
    ax.set_ylim(-max(max_depth - 0.2, 1) * 0.72, 1.0)
    return _finish(fig, ax, owns, filename=filename, output=output, dpi=dpi, transparent=transparent)


def draw_histogram(
    heights: Sequence[float],
    *,
    states: Mapping[Any, Any] | None = None,
    pointers: Mapping[str, int] | None = None,
    rectangle: tuple[int, int, float] | None = None,
    water_levels: Sequence[float] | None = None,
    title: str = "Histogram",
    filename: str | Path = "histogram.png",
    ax: Axes | None = None,
    output: str = "file",
    theme: VizTheme = DEFAULT_THEME,
    dpi: int = DPI,
    transparent: bool = False,
) -> Any:
    """Draw bars plus monotonic-stack rectangle or trapped-water overlays."""
    if any(height < 0 for height in heights):
        raise ValueError("histogram heights must be non-negative")
    n = len(heights)
    max_height = max(heights, default=1)
    fig, ax, owns = _prepare_axes(
        ax, figsize=(max(5, n * 0.72 + 2), 5), title=title, equal=False,
    )
    state_map = _normalize_states(states)
    for i, height in enumerate(heights):
        color = theme.state_color(state_map.get(i))
        bar = patches.Rectangle((i - 0.42, 0), 0.84, height,
                                facecolor=color, edgecolor="white", linewidth=1.2, zorder=2)
        ax.add_patch(bar)
        _label(ax, i, max(height / 2, 0.12), f"{height:g}", color="white",
               size=theme.small_font_size, weight="bold")
        _label(ax, i, -max_height * 0.08 - 0.08, i, color=theme.secondary,
               size=theme.small_font_size)
    if water_levels is not None:
        if len(water_levels) != n:
            raise ValueError("water_levels must have the same length as heights")
        for i, level in enumerate(water_levels):
            if level > heights[i]:
                water = patches.Rectangle(
                    (i - 0.42, heights[i]), 0.84, level - heights[i],
                    facecolor="#5DADE2", edgecolor="none", alpha=0.55, zorder=3,
                )
                ax.add_patch(water)
        max_height = max(max_height, max(water_levels, default=0))
    if rectangle is not None:
        left, right, height = rectangle
        if not (0 <= left <= right < n):
            raise ValueError("rectangle indices are outside the histogram")
        overlay = patches.Rectangle(
            (left - 0.45, 0), right - left + 0.9, height,
            fill=False, edgecolor=theme.path, linewidth=2.5, linestyle="--", zorder=5,
        )
        ax.add_patch(overlay)
        _label(ax, (left + right) / 2, height + max_height * 0.07,
               f"area={(right - left + 1) * height:g}", color=theme.path,
               size=theme.small_font_size, weight="bold")
        max_height = max(max_height, height)
    for row, (name, index) in enumerate((pointers or {}).items()):
        if not 0 <= index < n:
            raise ValueError(f"pointer {name!r} index is outside the histogram")
        y = max_height * (1.12 + row * 0.09)
        _arrow(ax, (index, y), (index, heights[index] + max_height * 0.03), color=theme.candidate)
        _label(ax, index, y + max_height * 0.04, name, color=theme.candidate,
               size=theme.small_font_size, weight="bold")
    if not heights:
        _label(ax, 0, 0, "(empty histogram)", color=theme.disabled, size=theme.font_size)
    ax.set_xlim(-0.8, max(n - 0.2, 1.2))
    ax.set_ylim(-max_height * 0.15 - 0.1, max_height * 1.38 + 0.2)
    return _finish(fig, ax, owns, filename=filename, output=output, dpi=dpi, transparent=transparent)


def draw_hash_table_chaining(
    table: Mapping[int, Sequence[tuple[Any, Any]]],
    *,
    capacity: int = 8,
    title: str = "Hash Table — Chaining",
    filename: str | Path = "hash_chaining.png",
    ax: Axes | None = None,
    output: str = "file",
    theme: VizTheme = DEFAULT_THEME,
    dpi: int = DPI,
    transparent: bool = False,
) -> Any:
    """Draw hash buckets as rows with collision chains extending rightward."""
    if capacity <= 0:
        raise ValueError("capacity must be positive")
    normalized: dict[int, list[tuple[Any, Any]]] = {}
    for bucket, entries in table.items():
        if not isinstance(bucket, int) or not 0 <= bucket < capacity:
            raise ValueError(f"bucket index {bucket!r} is outside the table")
        normalized[bucket] = []
        for entry in entries:
            if len(entry) != 2:
                raise ValueError("each chained hash entry must contain key and value")
            normalized[bucket].append((entry[0], entry[1]))
    labels = [f"{key}:{value}" for entries in normalized.values() for key, value in entries]
    longest = max((len(label) for label in labels), default=5)
    node_width = max(1.15, min(4.8, 0.55 + longest * 0.13))
    max_chain = max((len(entries) for entries in normalized.values()), default=0)
    x_span = 1.5 + max_chain * (node_width + 0.62)
    fig, ax, owns = _prepare_axes(
        ax,
        figsize=(max(6, x_span * 0.9 + 2), max(4, capacity * 0.68 + 1.8)),
        title=title,
    )
    slot_width = 0.82
    row_gap = 0.78
    for bucket in range(capacity):
        y = (capacity - 1 - bucket) * row_gap
        entries = normalized.get(bucket, [])
        _label(ax, -0.72, y, f"[{bucket}]", color=theme.text,
               size=theme.small_font_size, ha="right", weight="bold")
        _draw_box(
            ax, 0, y, "" if entries else "empty", width=slot_width, height=0.52,
            color=theme.primary if entries else theme.empty,
            text_color=theme.secondary if not entries else "white",
            theme=theme, fontsize=theme.small_font_size - 1,
        )
        previous_right = slot_width / 2
        for index, (key, value) in enumerate(entries):
            x = 1.25 + index * (node_width + 0.62)
            left_edge = x - node_width / 2
            _arrow(ax, (previous_right + 0.08, y), (left_edge - 0.08, y), color=theme.edge)
            _draw_box(
                ax, x, y, f"{key}:{value}", width=node_width, height=0.56,
                state="current" if index == 0 else None, theme=theme,
                fontsize=theme.small_font_size,
            )
            previous_right = x + node_width / 2
        if entries:
            _arrow(ax, (previous_right + 0.08, y), (previous_right + 0.58, y), color=theme.edge)
            _label(ax, previous_right + 0.72, y, "NULL", color=theme.disabled,
                   size=theme.small_font_size, ha="left")
    right = 1.4 if max_chain == 0 else 1.25 + (max_chain - 1) * (node_width + 0.62) + node_width / 2 + 1.3
    ax.set_xlim(-1.2, right)
    ax.set_ylim(-0.65, max((capacity - 1) * row_gap + 0.65, 1.0))
    return _finish(fig, ax, owns, filename=filename, output=output, dpi=dpi, transparent=transparent)


def draw_hash_table_open_addressing(
    table: Sequence[tuple[Any, Any] | None],
    *,
    capacity: int | None = None,
    probe_sequences: Mapping[Any, Sequence[int]] | None = None,
    title: str = "Hash Table — Open Addressing",
    filename: str | Path = "hash_open_addressing.png",
    ax: Axes | None = None,
    output: str = "file",
    theme: VizTheme = DEFAULT_THEME,
    dpi: int = DPI,
    transparent: bool = False,
) -> Any:
    """Draw open-addressed slots with probe arcs above, away from cell text."""
    capacity = len(table) if capacity is None else capacity
    if capacity < len(table) or capacity <= 0:
        raise ValueError("capacity must be positive and no smaller than table length")
    slots = list(table) + [None] * (capacity - len(table))
    labels = ["EMPTY" if item is None else f"{item[0]}:{item[1]}" for item in slots]
    longest = max((_display_width(label) for label in labels), default=5)
    cell_width = max(0.92, min(2.6, 0.5 + longest * 0.12))
    spacing = cell_width + 0.12
    fig, ax, owns = _prepare_axes(
        ax,
        figsize=(max(6, capacity * spacing * 0.82 + 2), 4.2),
        title=title,
    )
    for index, item in enumerate(slots):
        x = index * spacing
        _label(ax, x, 0.72, f"[{index}]", color=theme.text,
               size=theme.small_font_size, weight="bold")
        _draw_box(
            ax, x, 0, labels[index], width=cell_width, height=0.66,
            color=theme.primary if item is not None else theme.disabled,
            text_color="white" if item is not None else theme.text,
            theme=theme, fontsize=theme.small_font_size,
        )

    highest_arc = 1.15
    for sequence_number, (target, sequence) in enumerate((probe_sequences or {}).items(), start=1):
        if not sequence:
            continue
        for index in sequence:
            if not 0 <= index < capacity:
                raise ValueError(f"probe index {index} is outside the table")
        if isinstance(target, int) and not 0 <= target < capacity:
            raise ValueError(f"probe target {target} is outside the table")
        for step, (source, destination) in enumerate(zip(sequence, sequence[1:]), start=1):
            source_x, destination_x = source * spacing, destination * spacing
            distance = abs(destination - source)
            rad = -min(0.65, 0.24 + distance * 0.045)
            arrow = FancyArrowPatch(
                (source_x, 0.38), (destination_x, 0.38),
                arrowstyle="-|>", mutation_scale=13,
                connectionstyle=f"arc3,rad={rad}",
                color=theme.candidate, linewidth=1.8, zorder=5,
            )
            ax.add_patch(arrow)
            label_y = 1.0 + distance * 0.10 + (sequence_number - 1) * 0.22
            highest_arc = max(highest_arc, label_y + 0.25)
            _label(
                ax, (source_x + destination_x) / 2, label_y,
                f"probe {step}", color=theme.candidate,
                size=max(6, theme.small_font_size - 1), weight="bold",
            )
    right = (capacity - 1) * spacing if capacity else 0
    ax.set_xlim(-cell_width, right + cell_width)
    ax.set_ylim(-0.75, max(1.55, highest_arc))
    return _finish(fig, ax, owns, filename=filename, output=output, dpi=dpi, transparent=transparent)


@dataclass
class _TrieNode:
    char: str
    prefix: str
    terminal: bool = False
    children: dict[str, "_TrieNode"] = field(default_factory=dict)


def _make_trie(words: Sequence[str]) -> _TrieNode:
    root = _TrieNode(char="", prefix="")
    for word in dict.fromkeys(words):
        if not isinstance(word, str):
            raise TypeError("every Trie word must be a string")
        node = root
        prefix = ""
        for char in word:
            prefix += char
            node.children.setdefault(char, _TrieNode(char=char, prefix=prefix))
            node = node.children[char]
        node.terminal = True
    return root


def _layout_trie(root: _TrieNode) -> tuple[dict[int, tuple[float, float]], list[tuple[_TrieNode, _TrieNode]], int]:
    positions: dict[int, tuple[float, float]] = {}
    edges: list[tuple[_TrieNode, _TrieNode]] = []
    leaf_cursor = 0.0
    max_depth = 0

    def visit(node: _TrieNode, depth: int) -> float:
        nonlocal leaf_cursor, max_depth
        max_depth = max(max_depth, depth)
        child_x: list[float] = []
        for char in sorted(node.children):
            child = node.children[char]
            edges.append((node, child))
            child_x.append(visit(child, depth + 1))
        if child_x:
            x = (child_x[0] + child_x[-1]) / 2
        else:
            x = leaf_cursor
            leaf_cursor += 1.35
        positions[id(node)] = (x, -depth * 1.2)
        return x

    visit(root, 0)
    return positions, edges, max_depth


def draw_trie(
    words: Sequence[str],
    *,
    states: Mapping[str, str] | None = None,
    highlight_word: str | None = None,
    highlight_prefix: str | None = None,
    show_prefixes: bool = False,
    title: str = "Trie (Prefix Tree)",
    filename: str | Path = "trie.png",
    ax: Axes | None = None,
    output: str = "file",
    theme: VizTheme = DEFAULT_THEME,
    dpi: int = DPI,
    transparent: bool = False,
) -> Any:
    """Draw a prefix tree with characters in nodes and terminal double rings.

    ``states`` maps complete prefixes (for example ``"app"``) to semantic
    states. ``highlight_word`` highlights one root-to-word path, while
    ``highlight_prefix`` highlights the existing path of a search prefix.
    """
    if isinstance(words, (str, bytes, bytearray)):
        raise TypeError("words must be an iterable of strings, not a single string")
    try:
        word_list = list(words)
    except TypeError as exc:
        raise TypeError("words must be an iterable of strings") from exc
    root = _make_trie(word_list)
    positions, edges, max_depth = _layout_trie(root)
    all_nodes = [root] + [child for _, child in edges]
    xs = [positions[id(node)][0] for node in all_nodes]
    span = max(xs, default=0) - min(xs, default=0)
    fig, ax, owns = _prepare_axes(
        ax,
        figsize=(max(5, span * 1.05 + 2.6), max(3.5, max_depth * 1.05 + 2.4)),
        title=title,
    )
    state_map = dict(states or {})
    path_text = highlight_word if highlight_word is not None else highlight_prefix

    def node_state(node: _TrieNode) -> str | None:
        if node.prefix in state_map:
            return state_map[node.prefix]
        if path_text is not None and (node.prefix == "" or path_text.startswith(node.prefix)):
            return "success" if node.prefix == highlight_word else "path"
        return "success" if node.terminal else None

    # Edges use only actual parent-child relationships.
    for parent, child in edges:
        px, py = positions[id(parent)]
        cx, cy = positions[id(child)]
        dx, dy = cx - px, cy - py
        distance = math.hypot(dx, dy) or 1
        state = node_state(child)
        color = theme.path if state in {"path", "success"} and path_text is not None else theme.edge
        _arrow(
            ax,
            (px + dx / distance * 0.33, py + dy / distance * 0.33),
            (cx - dx / distance * 0.29, cy - dy / distance * 0.29),
            color=color,
            directed=False,
            linewidth=2.3 if color == theme.path else 1.45,
            zorder=1,
        )

    for node in all_nodes:
        x, y = positions[id(node)]
        if node is root:
            circle = Circle((x, y), 0.38, facecolor=theme.text,
                            edgecolor="white", linewidth=1.8, zorder=3)
            ax.add_patch(circle)
            _label(ax, x, y, "root", color="white", size=theme.small_font_size - 1, weight="bold")
            if node.terminal:
                terminal_ring = Circle((x, y), 0.46, fill=False, edgecolor=theme.success,
                                       linewidth=2.0, zorder=2)
                ax.add_patch(terminal_ring)
            continue
        state = node_state(node)
        color = theme.state_color(state)
        circle = Circle((x, y), 0.29, facecolor=color, edgecolor="white",
                        linewidth=1.7, zorder=3)
        ax.add_patch(circle)
        _label(ax, x, y, node.char, color="white", size=theme.font_size, weight="bold")
        if node.terminal:
            terminal_ring = Circle((x, y), 0.36, fill=False, edgecolor=theme.success,
                                   linewidth=2.0, zorder=2)
            ax.add_patch(terminal_ring)
        if show_prefixes:
            _label(ax, x, y - 0.48, node.prefix, color=theme.secondary,
                   size=max(6, theme.small_font_size - 2))

    if not word_list:
        _label(ax, positions[id(root)][0], -0.8, "(empty)", color=theme.disabled,
               size=theme.small_font_size)
    margin_x = 0.9
    ax.set_xlim(min(xs, default=0) - margin_x, max(xs, default=0) + margin_x)
    ax.set_ylim(-max_depth * 1.2 - 0.8, 0.8)
    return _finish(fig, ax, owns, filename=filename, output=output, dpi=dpi, transparent=transparent)


def _bplus_keys(node: Mapping[str, Any]) -> list[Any]:
    keys = node.get("keys", [])
    if isinstance(keys, (str, bytes, bytearray)) or not isinstance(keys, Sequence):
        raise TypeError("every B+ tree node requires a sequence of keys")
    return list(keys)


def _validate_bplus_tree(tree: Mapping[str, Any]) -> tuple[list[Mapping[str, Any]], int]:
    leaves: list[Mapping[str, Any]] = []
    leaf_depths: set[int] = set()
    active: set[int] = set()
    seen: set[int] = set()

    def visit(node: Any, depth: int) -> None:
        if not isinstance(node, Mapping):
            raise TypeError("every B+ tree node must be a mapping")
        node_id = id(node)
        if node_id in active:
            raise ValueError("B+ tree contains a cycle")
        if node_id in seen:
            raise ValueError("a B+ tree node cannot be shared by multiple parents")
        active.add(node_id)
        seen.add(node_id)
        keys = _bplus_keys(node)
        try:
            sorted_keys = sorted(keys)
        except TypeError as exc:
            raise ValueError("keys inside a B+ tree node must be mutually comparable") from exc
        if keys != sorted_keys:
            raise ValueError("keys inside each B+ tree node must be sorted")
        is_leaf = bool(node.get("leaf", False))
        children = node.get("children", [])
        if is_leaf:
            if children:
                raise ValueError("a B+ tree leaf cannot contain children")
            leaves.append(node)
            leaf_depths.add(depth)
            active.remove(node_id)
            return
        if not isinstance(children, Sequence) or isinstance(children, (str, bytes, bytearray)):
            raise TypeError("B+ tree children must be a sequence")
        if len(children) != len(keys) + 1:
            raise ValueError("an internal B+ tree node must have len(keys) + 1 children")
        for child in children:
            visit(child, depth + 1)
        active.remove(node_id)

    visit(tree, 0)
    if len(leaf_depths) > 1:
        raise ValueError("all B+ tree leaves must be at the same depth")
    return leaves, next(iter(leaf_depths), 0)


def _bplus_width(node: Mapping[str, Any]) -> tuple[float, float]:
    keys = _bplus_keys(node)
    longest = max((_display_width(key) for key in keys), default=1)
    per_key = max(0.62, min(4.5, 0.32 + longest * 0.13))
    return max(0.75, len(keys) * per_key), per_key


def _layout_bplus(
    tree: Mapping[str, Any],
    level_gap: float = 1.5,
    sibling_gap: float = 0.68,
) -> tuple[dict[int, tuple[float, float, float, float]], list[tuple[Mapping[str, Any], Mapping[str, Any]]]]:
    positions: dict[int, tuple[float, float, float, float]] = {}
    edges: list[tuple[Mapping[str, Any], Mapping[str, Any]]] = []
    own_geometry: dict[int, tuple[float, float]] = {}
    subtree_width: dict[int, float] = {}

    def measure(node: Mapping[str, Any]) -> float:
        width, per_key = _bplus_width(node)
        own_geometry[id(node)] = (width, per_key)
        children = list(node.get("children", []))
        if not children:
            measured = width
        else:
            child_widths = [measure(child) for child in children]
            measured = max(width, sum(child_widths) + sibling_gap * (len(children) - 1))
        subtree_width[id(node)] = measured
        return measured

    def place(node: Mapping[str, Any], left: float, depth: int) -> None:
        measured = subtree_width[id(node)]
        width, per_key = own_geometry[id(node)]
        center = left + measured / 2
        positions[id(node)] = (center, -depth * level_gap, width, per_key)
        children = list(node.get("children", []))
        if not children:
            return
        children_span = sum(subtree_width[id(child)] for child in children)
        children_span += sibling_gap * (len(children) - 1)
        cursor = left + (measured - children_span) / 2
        for child in children:
            edges.append((node, child))
            place(child, cursor, depth + 1)
            cursor += subtree_width[id(child)] + sibling_gap

    measure(tree)
    place(tree, 0.0, 0)
    return positions, edges


def _draw_bplus_node(
    ax: Axes,
    node: Mapping[str, Any],
    geometry: tuple[float, float, float, float],
    *,
    color: str,
    theme: VizTheme,
    height: float = 0.56,
) -> None:
    x, y, width, per_key = geometry
    keys = _bplus_keys(node)
    box = FancyBboxPatch(
        (x - width / 2, y - height / 2), width, height,
        boxstyle="round,pad=0.045", facecolor=color, edgecolor="white",
        linewidth=1.7, zorder=3,
    )
    ax.add_patch(box)
    if not keys:
        _label(ax, x, y, "empty", color="white", size=theme.small_font_size)
        return
    for index, key in enumerate(keys):
        key_x = x - width / 2 + (index + 0.5) * per_key
        _label(ax, key_x, y, key, color="white", size=theme.small_font_size,
               weight="bold")
        if index < len(keys) - 1:
            separator_x = x - width / 2 + (index + 1) * per_key
            ax.plot([separator_x, separator_x], [y - height / 2 + 0.06, y + height / 2 - 0.06],
                    color="white", linewidth=1.1, alpha=0.75, zorder=4)


def draw_bplus_tree(
    tree: Mapping[str, Any] | None,
    *,
    order: int = 3,
    search_key: Any | None = None,
    show_leaf_links: bool = True,
    title: str = "B+ Tree",
    filename: str | Path = "bplus_tree.png",
    ax: Axes | None = None,
    output: str = "file",
    theme: VizTheme = DEFAULT_THEME,
    dpi: int = DPI,
    transparent: bool = False,
) -> Any:
    """Draw a validated B+ tree with aligned levels and a leaf chain."""
    if order < 3:
        raise ValueError("B+ tree order must be at least 3")
    if not tree:
        fig, ax, owns = _prepare_axes(ax, figsize=(4.5, 2.6), title=title)
        _label(ax, 0, 0, "(empty B+ tree)", color=theme.disabled, size=theme.font_size)
        ax.set_xlim(-1.5, 1.5)
        ax.set_ylim(-1, 1)
        return _finish(fig, ax, owns, filename=filename, output=output, dpi=dpi, transparent=transparent)

    leaves, leaf_depth = _validate_bplus_tree(tree)
    positions, edges = _layout_bplus(tree)
    all_nodes: list[Mapping[str, Any]] = [tree] + [child for _, child in edges]
    path_ids: set[int] = set()
    result_leaf_id: int | None = None
    if search_key is not None:
        node = tree
        while True:
            path_ids.add(id(node))
            if node.get("leaf"):
                result_leaf_id = id(node)
                break
            child_index = bisect_right(_bplus_keys(node), search_key)
            node = node["children"][child_index]

    left = min(positions[id(node)][0] - positions[id(node)][2] / 2 for node in all_nodes)
    right = max(positions[id(node)][0] + positions[id(node)][2] / 2 for node in all_nodes)
    span = max(right - left, 1.0)
    fig, ax, owns = _prepare_axes(
        ax,
        figsize=(max(6, span * 0.92 + 2.0), max(3.5, leaf_depth * 1.4 + 2.5)),
        title=title,
    )
    node_height = 0.56

    for parent, child in edges:
        px, py, _, _ = positions[id(parent)]
        cx, cy, _, _ = positions[id(child)]
        is_path = id(parent) in path_ids and id(child) in path_ids
        _arrow(
            ax,
            (px, py - node_height / 2),
            (cx, cy + node_height / 2),
            color=theme.path if is_path else theme.edge,
            directed=False,
            linewidth=2.4 if is_path else 1.5,
            zorder=1,
        )

    for node in all_nodes:
        is_leaf = bool(node.get("leaf"))
        if id(node) == result_leaf_id:
            color = theme.success
        elif id(node) in path_ids:
            color = theme.path
        else:
            color = theme.success if is_leaf else theme.primary
        _draw_bplus_node(ax, node, positions[id(node)], color=color, theme=theme,
                         height=node_height)

    if show_leaf_links and len(leaves) > 1:
        for current, following in zip(leaves, leaves[1:]):
            x1, y1, width1, _ = positions[id(current)]
            x2, y2, width2, _ = positions[id(following)]
            _arrow(
                ax,
                (x1 + width1 / 2 + 0.04, y1),
                (x2 - width2 / 2 - 0.04, y2),
                color=theme.candidate,
                directed=True,
                linewidth=1.7,
                zorder=2,
            )
        leaf_y = positions[id(leaves[0])][1]
        _label(ax, (left + right) / 2, leaf_y - 0.58, "linked leaves",
               color=theme.success, size=theme.small_font_size, weight="bold")

    if search_key is not None:
        _label(ax, left, 0.6, f"search: {search_key}", color=theme.path,
               size=theme.small_font_size, ha="left", weight="bold")
    ax.set_xlim(left - 0.7, right + 0.7)
    ax.set_ylim(-leaf_depth * 1.5 - 0.9, 0.9)
    return _finish(fig, ax, owns, filename=filename, output=output, dpi=dpi, transparent=transparent)


def _union_find_roots(parent: Sequence[int]) -> list[int]:
    n = len(parent)
    roots: list[int] = []
    for start in range(n):
        current = start
        seen_on_path: set[int] = set()
        while True:
            if not isinstance(parent[current], int) or not 0 <= parent[current] < n:
                raise ValueError(f"parent[{current}]={parent[current]!r} is outside 0..{n - 1}")
            if current in seen_on_path:
                raise ValueError("union-find parent array contains a cycle without a self root")
            seen_on_path.add(current)
            next_node = parent[current]
            if next_node == current:
                roots.append(current)
                break
            current = next_node
    return roots


def draw_union_find(
    parent: Sequence[int],
    *,
    previous_parent: Sequence[int] | None = None,
    labels: Sequence[Any] | None = None,
    ranks: Sequence[Any] | Mapping[int, Any] | None = None,
    sizes: Sequence[Any] | Mapping[int, Any] | None = None,
    states: Mapping[Any, Any] | None = None,
    highlight_path: Sequence[int] | None = None,
    title: str = "Disjoint Set Union (Union-Find)",
    filename: str | Path = "union_find.png",
    ax: Axes | None = None,
    output: str = "file",
    theme: VizTheme = DEFAULT_THEME,
    dpi: int = DPI,
    transparent: bool = False,
) -> Any:
    """Draw the parent array and forest representation of a disjoint-set state."""
    parent = list(parent)
    n = len(parent)
    labels = list(range(n)) if labels is None else list(labels)
    if len(labels) != n:
        raise ValueError("labels must have the same length as parent")
    roots_for_node = _union_find_roots(parent) if n else []
    previous = list(previous_parent) if previous_parent is not None else None
    if previous is not None:
        if len(previous) != n:
            raise ValueError("previous_parent must have the same length as parent")
        previous_roots = _union_find_roots(previous) if n else []
        if previous_roots != roots_for_node:
            raise ValueError("previous_parent must describe the same disjoint sets")
    unique_roots = list(dict.fromkeys(roots_for_node))
    children: dict[int, list[int]] = defaultdict(list)
    for node, parent_node in enumerate(parent):
        if node != parent_node:
            children[parent_node].append(node)
    for child_list in children.values():
        child_list.sort(key=lambda item: str(labels[item]))

    forest_positions: dict[int, tuple[float, float]] = {}
    leaf_cursor = 0.0

    def place(node: int, depth: int) -> float:
        nonlocal leaf_cursor
        child_x = [place(child, depth + 1) for child in children.get(node, [])]
        if child_x:
            x = (child_x[0] + child_x[-1]) / 2
        else:
            x = leaf_cursor
            leaf_cursor += 1.15
        forest_positions[node] = (x, -depth * 1.15)
        return x

    for root in unique_roots:
        place(root, 0)
        leaf_cursor += 0.65

    forest_width = max((x for x, _ in forest_positions.values()), default=0)
    content_width = max(n - 1, forest_width, 1)
    max_depth = max((-y / 1.15 for _, y in forest_positions.values()), default=0)
    fig, ax, owns = _prepare_axes(
        ax,
        figsize=(max(6, content_width * 0.85 + 3), max(4, max_depth * 1.0 + 4.2)),
        title=title,
    )
    state_map = _normalize_states(states)
    highlighted = set(highlight_path or [])
    for node in highlighted:
        if not 0 <= node < n:
            raise ValueError("highlight_path contains a node outside the parent array")
    if highlight_path:
        path = list(highlight_path)
        path_parent = previous if previous is not None else parent
        if any(path_parent[source] != target for source, target in zip(path, path[1:])):
            raise ValueError("highlight_path must follow child-to-parent links")
        if path_parent[path[-1]] != path[-1]:
            raise ValueError("highlight_path must end at a self-root")

    # Parent array is intentionally rendered in the same Axes for composability.
    array_y = 2.0
    for index, parent_node in enumerate(parent):
        changed = previous is not None and previous[index] != parent_node
        automatic_state = "success" if changed else "path" if index in highlighted else None
        state = state_map.get(index, automatic_state)
        cell_text = f"{previous[index]}→{parent_node}" if changed else parent_node
        _draw_box(ax, index, array_y, cell_text, width=0.78, height=0.58,
                  state=state, theme=theme, fontsize=theme.small_font_size)
        _label(ax, index, array_y - 0.48, index, color=theme.secondary,
               size=theme.small_font_size - 1)
    _label(ax, -0.62, array_y, "parent", color=theme.text,
           size=theme.small_font_size, ha="right", weight="bold")

    component_colors = [
        theme.primary, theme.selected, theme.visited, theme.success,
        theme.candidate, theme.frontier,
    ]
    root_to_color = {
        root: component_colors[index % len(component_colors)]
        for index, root in enumerate(unique_roots)
    }
    if previous is not None:
        for node, old_parent in enumerate(previous):
            if old_parent == parent[node] or node == old_parent:
                continue
            x, y = forest_positions[node]
            px, py = forest_positions[old_parent]
            dx, dy = px - x, py - y
            distance = math.hypot(dx, dy) or 1
            _arrow(
                ax,
                (x + dx / distance * 0.34, y + dy / distance * 0.34),
                (px - dx / distance * 0.34, py - dy / distance * 0.34),
                color=theme.disabled,
                directed=True,
                dashed=True,
                linewidth=1.4,
                zorder=0,
            )
        _label(ax, content_width + 0.95, 1.35, "dashed = old parent",
               color=theme.disabled, size=max(6, theme.small_font_size - 1), ha="right")
    for node, parent_node in enumerate(parent):
        if node == parent_node:
            continue
        x, y = forest_positions[node]
        px, py = forest_positions[parent_node]
        dx, dy = px - x, py - y
        distance = math.hypot(dx, dy) or 1
        edge_color = theme.path if node in highlighted and parent_node in highlighted else theme.edge
        _arrow(
            ax,
            (x + dx / distance * 0.31, y + dy / distance * 0.31),
            (px - dx / distance * 0.31, py - dy / distance * 0.31),
            color=edge_color,
            directed=True,
            linewidth=2.3 if edge_color == theme.path else 1.5,
            zorder=1,
        )
    rank_values = dict(ranks) if isinstance(ranks, Mapping) else (
        {i: value for i, value in enumerate(ranks)} if ranks is not None else {}
    )
    size_values = dict(sizes) if isinstance(sizes, Mapping) else (
        {i: value for i, value in enumerate(sizes)} if sizes is not None else {}
    )
    if ranks is not None and not isinstance(ranks, Mapping) and len(ranks) != n:
        raise ValueError("ranks must have the same length as parent")
    if sizes is not None and not isinstance(sizes, Mapping) and len(sizes) != n:
        raise ValueError("sizes must have the same length as parent")
    if any(index not in range(n) for index in rank_values) or any(index not in range(n) for index in size_values):
        raise ValueError("rank/size mapping keys must be valid node indices")
    for node in range(n):
        x, y = forest_positions[node]
        root = roots_for_node[node]
        state = state_map.get(node, "path" if node in highlighted else None)
        color = theme.state_color(state) if state else root_to_color[root]
        circle = Circle((x, y), 0.31, facecolor=color, edgecolor="white",
                        linewidth=1.7, zorder=3)
        ax.add_patch(circle)
        _label(ax, x, y, labels[node], color="white", size=theme.small_font_size, weight="bold")
        annotations: list[str] = []
        if node in rank_values:
            annotations.append(f"rank={rank_values[node]}")
        if node in size_values:
            annotations.append(f"size={size_values[node]}")
        if annotations:
            _label(ax, x + 0.4, y - 0.13, ", ".join(annotations), color=theme.secondary,
                   size=max(6, theme.small_font_size - 2), ha="left")
        if node == parent[node]:
            _label(ax, x, y + 0.5, "root", color=color,
                   size=theme.small_font_size - 1, weight="bold")
    if not parent:
        _label(ax, 0, 0, "(empty union-find)", color=theme.disabled, size=theme.font_size)
    ax.set_xlim(-1.15, content_width + 1.15)
    ax.set_ylim(-max_depth * 1.15 - 0.85, 2.65)
    return _finish(fig, ax, owns, filename=filename, output=output, dpi=dpi, transparent=transparent)


@dataclass
class SegmentTreeNode:
    """Renderer input for a segment-tree node; no aggregation is performed."""

    interval: tuple[int, int]
    value: Any
    children: list["SegmentTreeNode"] = field(default_factory=list)
    lazy: Any | None = None
    id: Any | None = None


def _coerce_segment_tree(
    node: Any,
    _active: set[int] | None = None,
    _seen: set[int] | None = None,
) -> SegmentTreeNode:
    active = set() if _active is None else _active
    seen = set() if _seen is None else _seen
    node_identity = id(node)
    if node_identity in active:
        raise ValueError("segment tree contains a cycle")
    if node_identity in seen:
        raise ValueError("a segment-tree node cannot be shared by multiple parents")
    active.add(node_identity)
    seen.add(node_identity)
    if isinstance(node, SegmentTreeNode):
        interval = node.interval
        children_data = node.children
        value, lazy, node_id = node.value, node.lazy, node.id
    elif isinstance(node, Mapping):
        interval = node.get("interval", node.get("range"))
        children_data = node.get("children")
        if children_data is None:
            children_data = [node.get("left"), node.get("right")]
        value, lazy, node_id = node.get("value"), node.get("lazy"), node.get("id")
    else:
        raise TypeError("segment tree must use SegmentTreeNode or nested mappings")
    if (
        interval is None
        or not isinstance(interval, Sequence)
        or isinstance(interval, (str, bytes, bytearray))
        or len(interval) != 2
    ):
        raise ValueError("each segment-tree node requires interval=(left, right)")
    if not isinstance(children_data, Sequence) or isinstance(children_data, (str, bytes, bytearray)):
        raise TypeError("segment-tree children must be a sequence")
    if any(not isinstance(bound, int) or isinstance(bound, bool) for bound in interval):
        raise TypeError("segment-tree interval bounds must be integers")
    result = SegmentTreeNode(
        interval=(interval[0], interval[1]),
        value=value,
        children=[
            _coerce_segment_tree(child, active, seen)
            for child in children_data if child is not None
        ],
        lazy=lazy,
        id=node_id,
    )
    active.remove(node_identity)
    left, right = result.interval
    if left > right:
        raise ValueError("segment-tree interval left cannot exceed right")
    if len(result.children) > 2:
        raise ValueError("a segment-tree node can have at most two children")
    if len(result.children) == 1:
        raise ValueError("a segment-tree node must be collapsed or contain exactly two children")
    if left == right and result.children:
        raise ValueError("a single-element segment-tree interval cannot have children")
    for child in result.children:
        cl, cr = child.interval
        if cl < left or cr > right:
            raise ValueError("a child interval must be contained in its parent interval")
    if len(result.children) == 2:
        midpoint = (left + right) // 2
        expected = [(left, midpoint), (midpoint + 1, right)]
        if [child.interval for child in result.children] != expected:
            raise ValueError("segment-tree children must split the parent at its midpoint")
    return result


def _segment_node_width(node: SegmentTreeNode) -> float:
    left, right = node.interval
    return max(
        1.05,
        0.62 + max(_display_width(f"[{left},{right}]"), _display_width(node.value)) * 0.08,
    )


def _layout_segment_tree(root: SegmentTreeNode) -> tuple[dict[int, tuple[float, float]], list[tuple[SegmentTreeNode, SegmentTreeNode]], int]:
    positions: dict[int, tuple[float, float]] = {}
    edges: list[tuple[SegmentTreeNode, SegmentTreeNode]] = []
    subtree_width: dict[int, float] = {}
    max_depth = 0
    sibling_gap = 0.62

    def measure(node: SegmentTreeNode) -> float:
        own_width = _segment_node_width(node)
        if not node.children:
            measured = own_width
        else:
            child_widths = [measure(child) for child in node.children]
            measured = max(own_width, sum(child_widths) + sibling_gap * (len(child_widths) - 1))
        subtree_width[id(node)] = measured
        return measured

    def place(node: SegmentTreeNode, left: float, depth: int) -> None:
        nonlocal max_depth
        max_depth = max(max_depth, depth)
        measured = subtree_width[id(node)]
        positions[id(node)] = (left + measured / 2, -depth * 1.25)
        if not node.children:
            return
        children_span = sum(subtree_width[id(child)] for child in node.children)
        children_span += sibling_gap * (len(node.children) - 1)
        cursor = left + (measured - children_span) / 2
        for child in node.children:
            edges.append((node, child))
            place(child, cursor, depth + 1)
            cursor += subtree_width[id(child)] + sibling_gap

    measure(root)
    place(root, 0.0, 0)
    return positions, edges, max_depth


def draw_segment_tree(
    tree: SegmentTreeNode | Mapping[str, Any] | None,
    *,
    original: Sequence[Any] | None = None,
    query_range: tuple[int, int] | None = None,
    update_index: int | None = None,
    states: Mapping[Any, Any] | None = None,
    title: str = "Segment Tree",
    filename: str | Path = "segment_tree.png",
    ax: Axes | None = None,
    output: str = "file",
    theme: VizTheme = DEFAULT_THEME,
    dpi: int = DPI,
    transparent: bool = False,
) -> Any:
    """Draw caller-provided segment-tree state, query coverage, and lazy tags."""
    if tree is None:
        fig, ax, owns = _prepare_axes(ax, figsize=(5, 3), title=title)
        _label(ax, 0, 0, "(empty segment tree)", color=theme.disabled, size=theme.font_size)
        ax.set_xlim(-1.5, 1.5)
        ax.set_ylim(-1, 1)
        return _finish(fig, ax, owns, filename=filename, output=output, dpi=dpi, transparent=transparent)
    root = _coerce_segment_tree(tree)
    positions, edges, max_depth = _layout_segment_tree(root)
    nodes = [root] + [child for _, child in edges]
    state_map = _normalize_states(states)
    if query_range is not None and query_range[0] > query_range[1]:
        raise ValueError("query_range left cannot exceed right")
    root_left, root_right = root.interval
    if query_range is not None and not (root_left <= query_range[0] <= query_range[1] <= root_right):
        raise ValueError("query_range must be contained in the root interval")
    if update_index is not None and not root_left <= update_index <= root_right:
        raise ValueError("update_index must be contained in the root interval")

    query_states: dict[int, str | None] = {}
    if query_range is not None:
        ql, qr = query_range

        def classify_query(node: SegmentTreeNode, covered_by_parent: bool = False) -> None:
            left, right = node.interval
            if covered_by_parent:
                # A normal segment-tree query stops after a full-cover node.
                # Keep its descendants neutral so the minimal decomposition is
                # visible instead of coloring every in-range leaf as a result.
                automatic_state: str | None = None
            elif right < ql or left > qr:
                automatic_state = "disabled"
            elif ql <= left and right <= qr:
                automatic_state = "success"
            else:
                automatic_state = "current"
            query_states[id(node)] = automatic_state
            for child in node.children:
                classify_query(child, covered_by_parent or automatic_state == "success")

        classify_query(root)

    def interval_state(node: SegmentTreeNode) -> str | None:
        key = node.id if node.id is not None else node.interval
        if key in state_map:
            return state_map[key]
        left, right = node.interval
        if update_index is not None and left <= update_index <= right:
            return "path"
        if query_range is not None:
            return query_states[id(node)]
        return None

    xs = [positions[id(node)][0] for node in nodes]
    max_x = max(xs, default=0)
    original_y = -(max_depth + 1) * 1.25 - 0.5
    width = max(max_x, (len(original) - 1 if original is not None else 0), 1)
    fig, ax, owns = _prepare_axes(
        ax,
        figsize=(max(6, width * 0.9 + 3), max(4, max_depth * 1.1 + (2.5 if original is not None else 1.8))),
        title=title,
    )
    node_height = 0.66
    for parent_node, child in edges:
        px, py = positions[id(parent_node)]
        cx, cy = positions[id(child)]
        child_state = interval_state(child)
        edge_color = theme.path if child_state == "path" else theme.edge
        _arrow(ax, (px, py - node_height / 2), (cx, cy + node_height / 2),
               color=edge_color, directed=False,
               linewidth=2.3 if edge_color == theme.path else 1.4, zorder=1)
    for node in nodes:
        x, y = positions[id(node)]
        state = interval_state(node)
        left, right = node.interval
        text = f"[{left},{right}]\n{node.value}"
        box_width = _segment_node_width(node)
        _draw_box(ax, x, y, text, width=box_width, height=node_height,
                  state=state, theme=theme, fontsize=theme.small_font_size)
        if node.lazy is not None:
            _label(ax, x + box_width / 2 + 0.08, y - 0.1, f"lazy={node.lazy}",
                   color=theme.candidate, size=max(6, theme.small_font_size - 2), ha="left", weight="bold")
    if original is not None:
        original = list(original)
        if len(original) != root_right - root_left + 1:
            raise ValueError("original length must match the root interval length")
        for index, value in enumerate(original):
            actual_index = root_left + index
            state = None
            if update_index == actual_index:
                state = "path"
            elif query_range is not None and query_range[0] <= actual_index <= query_range[1]:
                state = "selected"
            _draw_box(ax, index, original_y, value, width=0.78, height=0.56,
                      state=state, theme=theme, fontsize=theme.small_font_size)
            _label(ax, index, original_y - 0.46, actual_index, color=theme.secondary,
                   size=theme.small_font_size - 1)
        _label(ax, -0.62, original_y, "array", color=theme.text,
               size=theme.small_font_size, ha="right", weight="bold")
    min_y = original_y - 0.8 if original is not None else -max_depth * 1.25 - 0.8
    ax.set_xlim(-1.0, width + 1.0)
    ax.set_ylim(min_y, 0.85)
    return _finish(fig, ax, owns, filename=filename, output=output, dpi=dpi, transparent=transparent)


def draw_fenwick_tree(
    tree_values: Sequence[Any],
    *,
    original: Sequence[Any] | None = None,
    query_path: Sequence[int] | None = None,
    update_path: Sequence[int] | None = None,
    query_index: int | None = None,
    update_index: int | None = None,
    includes_sentinel: bool = False,
    show_ranges: bool = True,
    show_binary: bool = False,
    states: Mapping[Any, Any] | None = None,
    title: str = "Fenwick Tree (Binary Indexed Tree)",
    filename: str | Path = "fenwick_tree.png",
    ax: Axes | None = None,
    output: str = "file",
    theme: VizTheme = DEFAULT_THEME,
    dpi: int = DPI,
    transparent: bool = False,
) -> Any:
    """Draw a one-based Fenwick array, lowbit coverage, and query/update paths."""
    raw_values = list(tree_values)
    values = raw_values[1:] if includes_sentinel and raw_values else raw_values
    n = len(values)
    original_values = list(original) if original is not None else None
    if original_values is not None and len(original_values) != n:
        raise ValueError("original and tree_values must have the same length")
    if query_index is not None and query_path is not None:
        raise ValueError("provide query_index or query_path, not both")
    if update_index is not None and update_path is not None:
        raise ValueError("provide update_index or update_path, not both")
    for name, index in (("query_index", query_index), ("update_index", update_index)):
        if index is not None and (not isinstance(index, int) or isinstance(index, bool) or not 1 <= index <= n):
            raise ValueError(f"{name} must be a one-based index within the Fenwick tree")
    query = list(query_path or [])
    if query_index is not None:
        current = query_index
        while current > 0:
            query.append(current)
            current -= current & -current
    update = list(update_path or [])
    if update_index is not None:
        current = update_index
        while current <= n:
            update.append(current)
            current += current & -current
    for name, path in (("query_path", query), ("update_path", update)):
        if any(not isinstance(index, int) or not 1 <= index <= n for index in path):
            raise ValueError(f"{name} must contain one-based indices within the Fenwick tree")
    max_level = int(math.log2(n)) if n else 0
    route_base_y = -0.78 if show_binary else -0.58
    route_step = 0.18
    route_specs: list[tuple[int, int, str, float]] = []
    route_group_y: dict[str, float] = {}
    route_cursor = 0
    for route_name, path, color in (
        ("query", query, theme.current),
        ("update", update, theme.path),
    ):
        if not path:
            continue
        route_group_y[route_name] = route_base_y - route_cursor * route_step
        path_edges = list(zip(path, path[1:]))
        if not path_edges:
            route_cursor += 1
            continue
        for source, destination in path_edges:
            lane_y = route_base_y - route_cursor * route_step
            route_specs.append((source, destination, color, lane_y))
            route_cursor += 1
    lowest_route_y = (
        route_base_y - max(route_cursor - 1, 0) * route_step
        if route_group_y
        else -0.35
    )
    original_y = min(-1.45, lowest_route_y - 0.55) if original_values is not None else -1.45
    extra_height = max(0.0, -lowest_route_y - 0.95) * 0.55
    fig, ax, owns = _prepare_axes(
        ax,
        figsize=(
            max(6, n * 0.88 + 2.5),
            (5.2 if original_values is not None else 4.4) + extra_height,
        ),
        title=title,
    )
    state_map = _normalize_states(states)
    bit_y = 0.15
    for index, value in enumerate(values, start=1):
        state = None
        if index in update:
            state = "path"
        elif index in query:
            state = "current"
        if index in state_map:
            state = state_map[index]
        _draw_box(ax, index - 1, bit_y, value, width=0.78, height=0.58,
                  state=state, theme=theme, fontsize=theme.small_font_size)
        _label(ax, index - 1, bit_y - 0.48, index, color=theme.secondary,
               size=theme.small_font_size - 1)
        if show_binary:
            bits = max(1, n.bit_length())
            _label(ax, index - 1, bit_y - 0.68, f"{index:0{bits}b}", color=theme.secondary,
                   size=max(6, theme.small_font_size - 3))
    _label(ax, -0.62, bit_y, "BIT", color=theme.text,
           size=theme.small_font_size, ha="right", weight="bold")

    # Each bracket states exactly which one-based source interval tree[i] covers.
    if show_ranges:
        for index in range(1, n + 1):
            lowbit = index & -index
            left = index - lowbit + 1
            level = int(math.log2(lowbit)) if lowbit else 0
            y = 0.72 + level * 0.42
            x1, x2 = left - 1, index - 1
            color = theme.path if index in update else theme.current if index in query else theme.grid
            if x1 == x2:
                ax.plot([x1, x1], [0.47, y], color=color, linewidth=1.1, zorder=1)
            else:
                ax.plot([x1, x1, x2, x2], [y - 0.12, y, y, y - 0.12],
                        color=color, linewidth=1.4, zorder=1)
            _label(ax, (x1 + x2) / 2, y + 0.14, f"[{left},{index}]",
                   color=color if color != theme.grid else theme.secondary,
                   size=max(6, theme.small_font_size - 3))

    box_bottom = bit_y - 0.34
    for source, destination, color, lane_y in route_specs:
        direction = 1 if destination > source else -1
        source_x = source - 1
        destination_x = destination - 1
        routed_start = (source_x + direction * 0.32, box_bottom)
        routed_end = (destination_x - direction * 0.32, box_bottom)
        approach = (routed_end[0] - direction * 0.22, box_bottom)
        route = MplPath(
            [
                routed_start,
                (routed_start[0], lane_y),
                (approach[0], lane_y),
                approach,
                routed_end,
            ],
            [MplPath.MOVETO] + [MplPath.LINETO] * 4,
        )
        arrow = FancyArrowPatch(
            path=route,
            arrowstyle="-|>",
            mutation_scale=15,
            color=color,
            linewidth=2.0,
            zorder=4,
        )
        ax.add_patch(arrow)
    if query:
        _label(ax, -0.62, route_group_y["query"], "query", color=theme.current,
               size=theme.small_font_size, ha="right", weight="bold")
    if update:
        _label(ax, -0.62, route_group_y["update"], "update", color=theme.path,
               size=theme.small_font_size, ha="right", weight="bold")

    if original_values is not None:
        for index, value in enumerate(original_values, start=1):
            _draw_box(ax, index - 1, original_y, value, width=0.78, height=0.55,
                      color=theme.secondary, theme=theme, fontsize=theme.small_font_size)
            _label(ax, index - 1, original_y - 0.45, index, color=theme.secondary,
                   size=theme.small_font_size - 1)
        _label(ax, -0.62, original_y, "array", color=theme.text,
               size=theme.small_font_size, ha="right", weight="bold")
    if not values:
        _label(ax, 0, 0, "(empty Fenwick tree)", color=theme.disabled, size=theme.font_size)
    ax.set_xlim(-1.1, max(n - 0.2, 1.2))
    lower_limit = (
        original_y - 0.6
        if original_values is not None
        else min(-0.95, lowest_route_y - 0.3)
    )
    ax.set_ylim(lower_limit,
                max(1.65, 0.72 + max_level * 0.42 + 0.45))
    return _finish(fig, ax, owns, filename=filename, output=output, dpi=dpi, transparent=transparent)


def _prepare_two_panel_canvas(
    axes: Sequence[Axes] | None,
    *,
    figsize: tuple[float, float],
    title: str,
) -> tuple[Figure, tuple[Axes, Axes], bool]:
    if axes is None:
        fig, grid = create_canvas(2, 1, figsize=figsize, title=title)
        return fig, (grid[0, 0], grid[1, 0]), True
    flat = list(axes)
    if len(flat) != 2 or not all(isinstance(item, Axes) for item in flat):
        raise ValueError("axes must contain exactly two Matplotlib Axes")
    if flat[0].figure is not flat[1].figure:
        raise ValueError("both composite axes must belong to the same Figure")
    fig = flat[0].figure
    if title:
        fig.suptitle(title, fontsize=15, fontweight="bold", color=DEFAULT_THEME.text)
    return fig, (flat[0], flat[1]), False


def _finish_composite(
    fig: Figure,
    axes: tuple[Axes, Axes],
    owns_figure: bool,
    *,
    filename: str | Path,
    output: str,
    dpi: int,
    transparent: bool,
) -> Any:
    fig.tight_layout(rect=(0, 0, 1, 0.96), h_pad=1.6)
    if output == "axes":
        return axes
    return _finish(fig, axes[-1], owns_figure, filename=filename, output=output,
                   dpi=dpi, transparent=transparent)


def draw_monotonic_stack(
    values: Sequence[Any],
    stack_indices: Sequence[int],
    *,
    current_index: int | None = None,
    active_range: tuple[int, int] | None = None,
    popped_indices: Sequence[int] | None = None,
    direction: str = "increasing",
    title: str = "Monotonic Stack State",
    filename: str | Path = "monotonic_stack.png",
    axes: Sequence[Axes] | None = None,
    output: str = "file",
    theme: VizTheme = DEFAULT_THEME,
    dpi: int = DPI,
    transparent: bool = False,
) -> Any:
    """Draw an input array and an index-identified monotonic stack together."""
    values = list(values)
    indices = list(stack_indices)
    popped = list(popped_indices or [])
    n = len(values)
    for name, sequence in (("stack_indices", indices), ("popped_indices", popped)):
        if len(set(sequence)) != len(sequence):
            raise ValueError(f"{name} cannot contain duplicate indices")
        if any(not isinstance(index, int) or not 0 <= index < n for index in sequence):
            raise ValueError(f"{name} contains an index outside values")
    if current_index is not None and not 0 <= current_index < n:
        raise ValueError("current_index is outside values")
    if active_range is not None:
        if not 0 <= active_range[0] <= active_range[1] < n:
            raise ValueError("active_range is outside values")
    if direction not in {"increasing", "decreasing"}:
        raise ValueError("direction must be increasing or decreasing")
    fig, panels, owns = _prepare_two_panel_canvas(
        axes, figsize=(max(7, n * 0.85 + 3), 7), title=title,
    )
    array_states: dict[int, str] = {index: "selected" for index in indices}
    array_states.update({index: "disabled" for index in popped})
    if current_index is not None:
        array_states[current_index] = "current"
    pointers = {"i": current_index} if current_index is not None else None
    ranges = [(*active_range, "active range")] if active_range is not None else None
    draw_array(
        values, states=array_states, pointers=pointers, ranges=ranges,
        title="Input array", ax=panels[0], output="axes", theme=theme,
    )
    stack_values = [f"[{index}] {values[index]}" for index in indices]
    stack_states: dict[int, str] = {position: "candidate" for position in range(len(indices))}
    for position, original_index in enumerate(indices):
        if original_index in popped:
            stack_states[position] = "error"
        if current_index == original_index:
            stack_states[position] = "current"
    draw_stack(
        stack_values, states=stack_states,
        top_label="top", title=f"{direction.capitalize()} stack (bottom → top)",
        ax=panels[1], output="axes", theme=theme,
    )
    if popped:
        _label(
            panels[1], panels[1].get_xlim()[1] - 0.15, panels[1].get_ylim()[0] + 0.25,
            "popped: " + ", ".join(f"[{index}]={values[index]}" for index in popped),
            color=theme.secondary, size=theme.small_font_size, ha="right",
        )
    return _finish_composite(fig, panels, owns, filename=filename, output=output,
                             dpi=dpi, transparent=transparent)


def draw_monotonic_queue(
    values: Sequence[Any],
    deque_indices: Sequence[int],
    *,
    window: tuple[int, int] | None = None,
    current_index: int | None = None,
    expired_indices: Sequence[int] | None = None,
    direction: str = "decreasing",
    title: str = "Monotonic Queue State",
    filename: str | Path = "monotonic_queue.png",
    axes: Sequence[Axes] | None = None,
    output: str = "file",
    theme: VizTheme = DEFAULT_THEME,
    dpi: int = DPI,
    transparent: bool = False,
) -> Any:
    """Draw a sliding-window array and index-identified monotonic deque."""
    values = list(values)
    indices = list(deque_indices)
    expired = list(expired_indices or [])
    n = len(values)
    for name, sequence in (("deque_indices", indices), ("expired_indices", expired)):
        if len(set(sequence)) != len(sequence):
            raise ValueError(f"{name} cannot contain duplicate indices")
        if any(not isinstance(index, int) or not 0 <= index < n for index in sequence):
            raise ValueError(f"{name} contains an index outside values")
    if current_index is not None and not 0 <= current_index < n:
        raise ValueError("current_index is outside values")
    if window is not None and not 0 <= window[0] <= window[1] < n:
        raise ValueError("window is outside values")
    if direction not in {"increasing", "decreasing"}:
        raise ValueError("direction must be increasing or decreasing")
    fig, panels, owns = _prepare_two_panel_canvas(
        axes, figsize=(max(7, n * 0.85 + 3), 6.5), title=title,
    )
    array_states: dict[int, str] = {index: "selected" for index in indices}
    array_states.update({index: "disabled" for index in expired})
    if indices:
        array_states[indices[0]] = "success"
    if current_index is not None:
        array_states[current_index] = "current"
    pointers = {"i": current_index} if current_index is not None else None
    ranges = [(*window, "window")] if window is not None else None
    draw_array(
        values, states=array_states, pointers=pointers, ranges=ranges,
        title="Sliding window input", ax=panels[0], output="axes", theme=theme,
    )
    deque_values = [f"[{index}] {values[index]}" for index in indices]
    deque_states: dict[int, str] = {}
    if deque_values:
        deque_states[0] = "success"
        if len(deque_values) > 1:
            deque_states[len(deque_values) - 1] = "candidate"
    draw_deque(
        deque_values, states=deque_states,
        front_label="answer", rear_label="back", show_operations=False,
        title=f"{direction.capitalize()} deque (front → rear)",
        ax=panels[1], output="axes", theme=theme,
    )
    if expired:
        _label(
            panels[1], panels[1].get_xlim()[1] - 0.2, panels[1].get_ylim()[0] + 0.22,
            "expired: " + ", ".join(str(index) for index in expired),
            color=theme.secondary, size=theme.small_font_size, ha="right",
        )
    return _finish_composite(fig, panels, owns, filename=filename, output=output,
                             dpi=dpi, transparent=transparent)


def _graph_node_order(
    parsed_edges: Sequence[tuple[Any, Any, Any | None]],
    nodes: Sequence[Any] | None,
) -> list[Any]:
    if nodes is not None:
        order = list(nodes)
        if len(set(order)) != len(order):
            raise ValueError("nodes cannot contain duplicates")
    else:
        order = []
        for source, target, _ in parsed_edges:
            if source not in order:
                order.append(source)
            if target not in order:
                order.append(target)
    missing = [endpoint for source, target, _ in parsed_edges for endpoint in (source, target) if endpoint not in order]
    if missing:
        raise ValueError(f"edge endpoints missing from nodes: {missing}")
    return order


def draw_adjacency_list(
    edges: Sequence[Any],
    *,
    nodes: Sequence[Any] | None = None,
    directed: bool = True,
    title: str = "Adjacency List",
    filename: str | Path = "adjacency_list.png",
    ax: Axes | None = None,
    output: str = "file",
    theme: VizTheme = DEFAULT_THEME,
    dpi: int = DPI,
    transparent: bool = False,
) -> Any:
    """Draw adjacency rows while preserving zero-weight edges and node order."""
    parsed = _parse_edges(edges)
    order = _graph_node_order(parsed, nodes)
    adjacency: dict[Any, list[tuple[Any, Any | None]]] = {node: [] for node in order}
    for source, target, weight in parsed:
        adjacency[source].append((target, weight))
        if not directed and source != target:
            adjacency[target].append((source, weight))
    labels = [
        f"{neighbor}" if weight is None else f"{neighbor} ({weight})"
        for entries in adjacency.values() for neighbor, weight in entries
    ]
    neighbor_width = max(1.0, min(3.5, 0.55 + max((_display_width(label) for label in labels), default=3) * 0.12))
    max_degree = max((len(entries) for entries in adjacency.values()), default=0)
    fig, ax, owns = _prepare_axes(
        ax,
        figsize=(max(5.5, max_degree * (neighbor_width + 0.5) + 3), max(3, len(order) * 0.75 + 1.8)),
        title=title,
    )
    row_gap = 0.75
    for row, node in enumerate(order):
        y = (len(order) - 1 - row) * row_gap
        _draw_box(ax, 0, y, node, width=0.9, height=0.54,
                  color=theme.text, theme=theme, fontsize=theme.small_font_size)
        previous_right = 0.45
        entries = adjacency[node]
        for index, (neighbor, weight) in enumerate(entries):
            x = 1.35 + index * (neighbor_width + 0.5)
            label = str(neighbor) if weight is None else f"{neighbor} ({weight})"
            _arrow(ax, (previous_right + 0.08, y), (x - neighbor_width / 2 - 0.08, y),
                   color=theme.edge)
            _draw_box(ax, x, y, label, width=neighbor_width, height=0.54,
                      color=theme.primary, theme=theme, fontsize=theme.small_font_size)
            previous_right = x + neighbor_width / 2
        if entries:
            _arrow(ax, (previous_right + 0.08, y), (previous_right + 0.5, y), color=theme.edge)
            _label(ax, previous_right + 0.62, y, "NULL", color=theme.disabled,
                   size=theme.small_font_size, ha="left")
        else:
            _label(ax, 0.75, y, "empty", color=theme.disabled,
                   size=theme.small_font_size, ha="left")
    if not order:
        _label(ax, 0, 0, "(empty adjacency list)", color=theme.disabled, size=theme.font_size)
    right = 1.5 if max_degree == 0 else 1.35 + (max_degree - 1) * (neighbor_width + 0.5) + neighbor_width / 2 + 1.2
    ax.set_xlim(-0.8, right)
    ax.set_ylim(-0.65, max((len(order) - 1) * row_gap + 0.65, 1.0))
    return _finish(fig, ax, owns, filename=filename, output=output, dpi=dpi, transparent=transparent)


def draw_adjacency_matrix(
    edges: Sequence[Any],
    *,
    nodes: Sequence[Any] | None = None,
    directed: bool = True,
    absent: Any = "",
    title: str = "Adjacency Matrix",
    filename: str | Path = "adjacency_matrix.png",
    ax: Axes | None = None,
    output: str = "file",
    theme: VizTheme = DEFAULT_THEME,
    dpi: int = DPI,
    transparent: bool = False,
) -> Any:
    """Draw an adjacency matrix; zero-weight edges remain distinct from absence."""
    parsed = _parse_edges(edges)
    order = _graph_node_order(parsed, nodes)
    index = {node: position for position, node in enumerate(order)}
    matrix = [[absent for _ in order] for _ in order]
    states: dict[tuple[int, int], str] = {}
    occupied: set[tuple[int, int]] = set()
    for source, target, weight in parsed:
        row, col = index[source], index[target]
        canonical = (row, col) if directed or row == col else tuple(sorted((row, col)))
        if canonical in occupied:
            raise ValueError("adjacency matrix cannot represent parallel edges without loss")
        occupied.add(canonical)
        matrix[row][col] = 1 if weight is None else weight
        states[(row, col)] = "selected"
        if not directed:
            matrix[col][row] = 1 if weight is None else weight
            states[(col, row)] = "selected"
    return draw_matrix(
        matrix, states=states, row_labels=order, col_labels=order,
        title=title, filename=filename, ax=ax, output=output,
        theme=theme, dpi=dpi, transparent=transparent,
    )


def draw_edge_list(
    edges: Sequence[Any],
    *,
    title: str = "Edge List",
    filename: str | Path = "edge_list.png",
    ax: Axes | None = None,
    output: str = "file",
    theme: VizTheme = DEFAULT_THEME,
    dpi: int = DPI,
    transparent: bool = False,
) -> Any:
    """Draw source, target, and optional weight columns."""
    parsed = _parse_edges(edges)
    weighted = any(weight is not None for _, _, weight in parsed)
    table = [
        [source, target, weight if weight is not None else ""] if weighted else [source, target]
        for source, target, weight in parsed
    ]
    columns = ["source", "target", "weight"] if weighted else ["source", "target"]
    return draw_matrix(
        table, row_labels=range(len(table)), col_labels=columns,
        title=title, filename=filename, ax=ax, output=output,
        theme=theme, dpi=dpi, transparent=transparent,
    )


def draw_graph_representations(
    edges: Sequence[Any],
    *,
    nodes: Sequence[Any] | None = None,
    directed: bool = True,
    show: Sequence[str] = ("graph", "adjacency_list", "adjacency_matrix", "edge_list"),
    graph_layout: str = "circular",
    title: str = "Graph Representations",
    filename: str | Path = "graph_representations.png",
    axes: Sequence[Axes] | None = None,
    output: str = "file",
    theme: VizTheme = DEFAULT_THEME,
    dpi: int = DPI,
    transparent: bool = False,
) -> Any:
    """Render a graph alongside its adjacency list, matrix, and edge list."""
    allowed = {"graph", "adjacency_list", "adjacency_matrix", "edge_list"}
    requested = list(show)
    if not requested or len(set(requested)) != len(requested) or any(item not in allowed for item in requested):
        raise ValueError(f"show must contain unique values from {sorted(allowed)}")
    owns = axes is None
    count = len(requested)
    if axes is None:
        cols = 2 if count > 1 else 1
        rows = math.ceil(count / cols)
        fig, grid = plt.subplots(rows, cols, figsize=(cols * 7, rows * 5.8), squeeze=False)
        panels = list(grid.flat)
        for extra in panels[count:]:
            extra.axis("off")
        panels = panels[:count]
    else:
        panels = list(axes)
        if len(panels) != count or not all(isinstance(item, Axes) for item in panels):
            raise ValueError("axes count must match the requested representations")
        if any(panel.figure is not panels[0].figure for panel in panels):
            raise ValueError("all axes must belong to the same Figure")
        fig = panels[0].figure
    fig.suptitle(title, fontsize=15, fontweight="bold", color=theme.text)
    renderers = {
        "graph": lambda panel: draw_graph(
            edges, nodes=nodes, directed=directed, layout=graph_layout,
            title="Graph", ax=panel, output="axes", theme=theme,
        ),
        "adjacency_list": lambda panel: draw_adjacency_list(
            edges, nodes=nodes, directed=directed,
            title="Adjacency List", ax=panel, output="axes", theme=theme,
        ),
        "adjacency_matrix": lambda panel: draw_adjacency_matrix(
            edges, nodes=nodes, directed=directed,
            title="Adjacency Matrix", ax=panel, output="axes", theme=theme,
        ),
        "edge_list": lambda panel: draw_edge_list(
            edges, title="Edge List", ax=panel, output="axes", theme=theme,
        ),
    }
    for name, panel in zip(requested, panels):
        renderers[name](panel)
    fig.tight_layout(rect=(0, 0, 1, 0.965), h_pad=2, w_pad=1.5)
    if output == "axes":
        return tuple(panels)
    return _finish(fig, panels[-1], owns, filename=filename, output=output,
                   dpi=dpi, transparent=transparent)


def draw_dag(
    edges: Sequence[Any],
    *,
    nodes: Sequence[Any] | None = None,
    node_states: Mapping[Any, Any] | None = None,
    edge_states: Mapping[tuple[Any, Any], str] | None = None,
    path: Sequence[Any] | None = None,
    node_annotations: Mapping[Any, str] | None = None,
    title: str = "Directed Acyclic Graph",
    filename: str | Path = "dag.png",
    ax: Axes | None = None,
    output: str = "file",
    theme: VizTheme = DEFAULT_THEME,
    dpi: int = DPI,
    transparent: bool = False,
) -> Any:
    """Strict DAG renderer: reject cycles and use deterministic hierarchy."""
    parsed = _parse_edges(edges)
    order = _graph_node_order(parsed, nodes)
    indegree = {node: 0 for node in order}
    outgoing: dict[Any, list[Any]] = defaultdict(list)
    for source, target, _ in parsed:
        outgoing[source].append(target)
        indegree[target] += 1
    queue = deque(node for node in order if indegree[node] == 0)
    visited = 0
    while queue:
        node = queue.popleft()
        visited += 1
        for target in outgoing[node]:
            indegree[target] -= 1
            if indegree[target] == 0:
                queue.append(target)
    if visited != len(order):
        raise ValueError("draw_dag requires an acyclic directed graph")
    return draw_graph(
        edges, nodes=order, directed=True, layout="hierarchical",
        node_states=node_states, edge_states=edge_states, path=path,
        node_annotations=node_annotations, title=title, filename=filename,
        ax=ax, output=output, theme=theme, dpi=dpi, transparent=transparent,
    )


def draw_bitmask(
    masks: int | Mapping[Any, int],
    *,
    width: int | None = None,
    signed: bool = False,
    states: Mapping[Any, Any] | None = None,
    highlight_bits: Iterable[int] | Mapping[Any, Iterable[int]] | None = None,
    bit_labels: Mapping[int, str] | None = None,
    msb_left: bool = True,
    title: str = "Bitmask",
    filename: str | Path = "bitmask.png",
    ax: Axes | None = None,
    output: str = "file",
    theme: VizTheme = DEFAULT_THEME,
    dpi: int = DPI,
    transparent: bool = False,
) -> Any:
    """Draw one or several integer masks with logical LSB-based indices."""
    if isinstance(masks, Mapping):
        rows = list(masks.items())
    elif isinstance(masks, int) and not isinstance(masks, bool):
        rows = [("mask", masks)]
    else:
        raise TypeError("masks must be an int or a label-to-int mapping")
    if any(not isinstance(value, int) or isinstance(value, bool) for _, value in rows):
        raise TypeError("every mask value must be an integer")
    if width is None:
        if any(value < 0 for _, value in rows):
            raise ValueError("width is required when drawing negative masks")
        width = max(1, max((value.bit_length() for _, value in rows), default=1))
    if not isinstance(width, int) or isinstance(width, bool) or width <= 0:
        raise ValueError("width must be a positive integer")
    minimum = -(1 << (width - 1)) if signed else 0
    maximum = (1 << (width - 1)) - 1 if signed else (1 << width) - 1
    for label, value in rows:
        if not minimum <= value <= maximum:
            raise ValueError(f"mask {label!r}={value} does not fit in width={width}")
    state_map = _normalize_states(states)
    if isinstance(highlight_bits, Mapping):
        highlights = {label: set(bits) for label, bits in highlight_bits.items()}
    else:
        shared = set(highlight_bits or [])
        highlights = {label: shared for label, _ in rows}
    for label, bits in highlights.items():
        if any(not isinstance(bit, int) or not 0 <= bit < width for bit in bits):
            raise ValueError(f"highlight bits for {label!r} must be within 0..{width - 1}")
    fig, ax, owns = _prepare_axes(
        ax,
        figsize=(max(5, width * 0.82 + 3), max(2.8, len(rows) * 0.9 + 2)),
        title=title,
    )
    display_indices = list(range(width - 1, -1, -1)) if msb_left else list(range(width))
    mask_value = (1 << width) - 1
    for row_index, (label, value) in enumerate(rows):
        y = -row_index * 0.88
        encoded = value & mask_value
        for column, bit_index in enumerate(display_indices):
            bit = (encoded >> bit_index) & 1
            state = None
            if bit_index in highlights.get(label, set()):
                state = "selected"
            explicit_state = state_map.get((label, bit_index), state_map.get(bit_index))
            if explicit_state is not None:
                state = explicit_state
            color = theme.state_color(state) if state else (theme.primary if bit else theme.empty)
            text_color = "white" if bit or state else theme.text
            _draw_box(ax, column, y, bit, width=0.7, height=0.62,
                      color=color, text_color=text_color, theme=theme,
                      fontsize=theme.font_size)
        _label(ax, -0.62, y, label, color=theme.text,
               size=theme.small_font_size, ha="right", weight="bold")
        _label(ax, width - 0.2, y, f"{value} / 0x{encoded:X}", color=theme.secondary,
               size=theme.small_font_size, ha="left")
    for column, bit_index in enumerate(display_indices):
        _label(ax, column, 0.55, bit_index, color=theme.secondary,
               size=theme.small_font_size - 1)
        if bit_index in (bit_labels or {}):
            _label(ax, column, -len(rows) * 0.88 + 0.18, bit_labels[bit_index],
                   color=theme.candidate, size=max(6, theme.small_font_size - 2),
                   weight="bold")
    _label(ax, -0.62, 0.55, "bit", color=theme.secondary,
           size=theme.small_font_size - 1, ha="right")
    if not rows:
        _label(ax, 0, 0, "(no masks)", color=theme.disabled, size=theme.font_size)
    ax.set_xlim(-1.2, width + 1.8)
    ax.set_ylim(-max(len(rows) - 0.2, 1) * 0.88, 0.9)
    return _finish(fig, ax, owns, filename=filename, output=output, dpi=dpi, transparent=transparent)


def draw_bits(value: int | Mapping[Any, int], **kwargs: Any) -> Any:
    """Convenience alias for :func:`draw_bitmask`."""
    return draw_bitmask(value, **kwargs)


def _as_point(value: Any, name: str) -> tuple[float, float]:
    if not isinstance(value, Sequence) or isinstance(value, (str, bytes, bytearray)) or len(value) != 2:
        raise ValueError(f"{name} must be a numeric (x, y) pair")
    try:
        point = float(value[0]), float(value[1])
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{name} must be a numeric (x, y) pair") from exc
    if not all(math.isfinite(component) for component in point):
        raise ValueError(f"{name} coordinates must be finite")
    return point


def draw_coordinate_plane(
    points: Mapping[Any, Sequence[float]] | Sequence[Any] = (),
    *,
    segments: Sequence[Any] = (),
    vectors: Sequence[Any] = (),
    polygons: Sequence[Any] = (),
    circles: Sequence[Any] = (),
    scan_lines: Sequence[Any] = (),
    states: Mapping[Any, Any] | None = None,
    annotations: Mapping[Any, str] | None = None,
    title: str = "Coordinate Plane",
    filename: str | Path = "coordinate_plane.png",
    ax: Axes | None = None,
    output: str = "file",
    theme: VizTheme = DEFAULT_THEME,
    dpi: int = DPI,
    transparent: bool = False,
) -> Any:
    """Draw points, segments, vectors, polygons, circles, and sweep lines."""
    if isinstance(points, Mapping):
        point_items = [(label, _as_point(point, f"point {label!r}")) for label, point in points.items()]
    else:
        point_items = []
        for index, item in enumerate(points):
            if isinstance(item, Mapping):
                label = item.get("label", index)
                point = _as_point(item.get("point", item.get("position")), f"point {label!r}")
            elif len(item) == 3:
                point = _as_point(item[:2], f"point {index}")
                label = item[2]
            else:
                point = _as_point(item, f"point {index}")
                label = index
            point_items.append((label, point))
    state_map = _normalize_states(states)
    annotations = dict(annotations or {})
    extent_points: list[tuple[float, float]] = [point for _, point in point_items]

    parsed_segments: list[tuple[tuple[float, float], tuple[float, float], str, str | None]] = []
    for index, item in enumerate(segments):
        if isinstance(item, Mapping):
            start = _as_point(item.get("start"), f"segment {index} start")
            end = _as_point(item.get("end"), f"segment {index} end")
            label, state = str(item.get("label", "")), item.get("state")
        else:
            if len(item) not in (2, 3):
                raise ValueError("segment must contain start, end, and optional label")
            start = _as_point(item[0], f"segment {index} start")
            end = _as_point(item[1], f"segment {index} end")
            label, state = (str(item[2]), None) if len(item) == 3 else ("", None)
        parsed_segments.append((start, end, label, state))
        extent_points.extend((start, end))

    parsed_vectors: list[tuple[tuple[float, float], tuple[float, float], str, str | None]] = []
    for index, item in enumerate(vectors):
        if isinstance(item, Mapping):
            origin = _as_point(item.get("origin", (0, 0)), f"vector {index} origin")
            delta = _as_point(item.get("delta"), f"vector {index} delta")
            label, state = str(item.get("label", "")), item.get("state")
        else:
            if len(item) not in (2, 3):
                raise ValueError("vector must contain origin, delta, and optional label")
            origin = _as_point(item[0], f"vector {index} origin")
            delta = _as_point(item[1], f"vector {index} delta")
            label, state = (str(item[2]), None) if len(item) == 3 else ("", None)
        end = (origin[0] + delta[0], origin[1] + delta[1])
        parsed_vectors.append((origin, end, label, state))
        extent_points.extend((origin, end))

    parsed_polygons: list[tuple[list[tuple[float, float]], str, str | None]] = []
    for index, item in enumerate(polygons):
        if isinstance(item, Mapping):
            vertices_data = item.get("points", item.get("vertices"))
            label, state = str(item.get("label", "")), item.get("state")
        else:
            vertices_data, label, state = item, "", None
        vertices = [_as_point(point, f"polygon {index} vertex") for point in vertices_data]
        if len(vertices) < 3:
            raise ValueError("a polygon requires at least three vertices")
        parsed_polygons.append((vertices, label, state))
        extent_points.extend(vertices)

    parsed_circles: list[tuple[tuple[float, float], float, str, str | None]] = []
    for index, item in enumerate(circles):
        if isinstance(item, Mapping):
            center = _as_point(item.get("center"), f"circle {index} center")
            radius = float(item.get("radius"))
            label, state = str(item.get("label", "")), item.get("state")
        else:
            if len(item) not in (2, 3):
                raise ValueError("circle must contain center, radius, and optional label")
            center, radius = _as_point(item[0], f"circle {index} center"), float(item[1])
            label, state = (str(item[2]), None) if len(item) == 3 else ("", None)
        if radius <= 0:
            raise ValueError("circle radius must be positive")
        parsed_circles.append((center, radius, label, state))
        extent_points.extend([
            (center[0] - radius, center[1] - radius),
            (center[0] + radius, center[1] + radius),
        ])

    parsed_scan_lines: list[tuple[str, float, str, str | None]] = []
    for index, item in enumerate(scan_lines):
        if isinstance(item, Mapping):
            orientation = str(item.get("orientation", "x"))
            value = float(item.get("value"))
            label, state = str(item.get("label", "scan")), item.get("state", "current")
        else:
            if len(item) not in (2, 3):
                raise ValueError("scan line must contain orientation, value, and optional label")
            orientation, value = str(item[0]), float(item[1])
            label, state = (str(item[2]), "current") if len(item) == 3 else ("scan", "current")
        if orientation not in {"x", "y", "vertical", "horizontal"}:
            raise ValueError("scan-line orientation must be x/y/vertical/horizontal")
        parsed_scan_lines.append((orientation, value, label, state))

    xs = [point[0] for point in extent_points] or [-1, 1]
    ys = [point[1] for point in extent_points] or [-1, 1]
    if not extent_points:
        for orientation, value, _, _ in parsed_scan_lines:
            if orientation in {"x", "vertical"}:
                xs.append(value)
            else:
                ys.append(value)
    xmin, xmax = min(xs), max(xs)
    ymin, ymax = min(ys), max(ys)
    span = max(xmax - xmin, ymax - ymin, 2.0)
    margin = span * 0.14
    fig, ax, owns = _prepare_axes(
        ax,
        figsize=(7, 6),
        title=title,
    )
    ax.axis("on")
    ax.grid(True, color=theme.grid, linewidth=0.8, alpha=0.7)
    ax.axhline(0, color=theme.secondary, linewidth=1.1, zorder=0)
    ax.axvline(0, color=theme.secondary, linewidth=1.1, zorder=0)
    ax.set_xlabel("x", color=theme.text)
    ax.set_ylabel("y", color=theme.text)

    for vertices, label, state in parsed_polygons:
        color = theme.state_color(state) if state else theme.selected
        polygon = Polygon(vertices, closed=True, facecolor=color, edgecolor=color,
                          linewidth=1.8, alpha=0.2, zorder=1)
        ax.add_patch(polygon)
        if label:
            cx = sum(x for x, _ in vertices) / len(vertices)
            cy = sum(y for _, y in vertices) / len(vertices)
            _label(ax, cx, cy, label, color=color, size=theme.small_font_size, weight="bold")
    for center, radius, label, state in parsed_circles:
        color = theme.state_color(state) if state else theme.visited
        circle = Circle(center, radius, facecolor=color, edgecolor=color,
                        linewidth=1.8, alpha=0.18, zorder=1)
        ax.add_patch(circle)
        if label:
            _label(ax, center[0], center[1] + radius + margin * 0.15, label,
                   color=color, size=theme.small_font_size, weight="bold")
    for start, end, label, state in parsed_segments:
        color = theme.state_color(state) if state else theme.edge
        ax.plot([start[0], end[0]], [start[1], end[1]], color=color,
                linewidth=2.0, zorder=2)
        if label:
            _label(ax, (start[0] + end[0]) / 2, (start[1] + end[1]) / 2 + margin * 0.08,
                   label, color=color, size=theme.small_font_size, weight="bold")
    for origin, end, label, state in parsed_vectors:
        color = theme.state_color(state) if state else theme.candidate
        _arrow(ax, origin, end, color=color, directed=True, linewidth=2.0, zorder=3)
        if label:
            _label(ax, (origin[0] + end[0]) / 2, (origin[1] + end[1]) / 2 + margin * 0.08,
                   label, color=color, size=theme.small_font_size, weight="bold")
    for orientation, value, label, state in parsed_scan_lines:
        color = theme.state_color(state)
        if orientation in {"x", "vertical"}:
            ax.axvline(value, color=color, linewidth=2, linestyle="--", zorder=4)
            _label(ax, value + margin * 0.08, ymax + margin * 0.45, label, color=color,
                   size=theme.small_font_size, ha="left", weight="bold")
        else:
            ax.axhline(value, color=color, linewidth=2, linestyle="--", zorder=4)
            _label(ax, xmax + margin * 0.15, value + margin * 0.08, label, color=color,
                   size=theme.small_font_size, ha="left", weight="bold")
    point_radius = span * 0.025
    for index, (label, point) in enumerate(point_items):
        state = state_map.get(label, state_map.get(index))
        color = theme.state_color(state)
        marker = Circle(point, point_radius, facecolor=color, edgecolor="white",
                        linewidth=1.4, zorder=5)
        ax.add_patch(marker)
        text = annotations.get(label, str(label))
        _label(ax, point[0] + point_radius * 1.4, point[1] + point_radius * 1.4,
               text, color=color, size=theme.small_font_size, ha="left", weight="bold")
    ax.set_xlim(xmin - margin, xmax + margin)
    ax.set_ylim(ymin - margin, ymax + margin)
    return _finish(fig, ax, owns, filename=filename, output=output, dpi=dpi, transparent=transparent)


def draw_binary_heap(values: Sequence[Any], **kwargs: Any) -> Any:
    """Compatibility alias for :func:`draw_heap`."""
    return draw_heap(values, **kwargs)


def draw_binary_indexed_tree(values: Sequence[Any], **kwargs: Any) -> Any:
    """Compatibility alias for :func:`draw_fenwick_tree`."""
    return draw_fenwick_tree(values, **kwargs)


# Clear public names for ``from algorithm_viz import *`` callers.
__all__ = [
    "VizTheme", "DEFAULT_THEME", "TreeNode", "OUTPUT_DIR", "DPI",
    "create_canvas", "save_figure", "figure_to_bytes",
    "draw_array", "draw_string", "draw_multi_array", "draw_matrix", "draw_grid",
    "draw_stack", "draw_queue", "draw_deque", "draw_circular_queue",
    "draw_linked_list", "draw_doubly_linked_list",
    "draw_tree", "draw_binary_tree", "draw_heap", "draw_binary_heap", "draw_graph",
    "draw_table", "draw_dp_table", "draw_intervals", "draw_timeline",
    "draw_mapping", "draw_set", "draw_buckets", "draw_histogram",
    "draw_hash_table_chaining",
    "draw_hash_table_open_addressing",
    "draw_trie", "draw_bplus_tree",
    "draw_union_find", "SegmentTreeNode", "draw_segment_tree",
    "draw_fenwick_tree", "draw_binary_indexed_tree",
    "draw_monotonic_stack", "draw_monotonic_queue",
    "draw_adjacency_list", "draw_adjacency_matrix", "draw_edge_list",
    "draw_graph_representations", "draw_dag",
    "draw_bitmask", "draw_bits", "draw_coordinate_plane",
]
