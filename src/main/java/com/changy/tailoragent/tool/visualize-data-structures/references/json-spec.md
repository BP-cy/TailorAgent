# JSON rendering specification

## Contents

- [Top-level schema](#top-level-schema)
- [Tagged JSON values](#tagged-json-values)
- [Examples](#examples)

## Top-level schema

```json
{
  "engine": "modern",
  "renderer": "draw_array",
  "args": [[2, 7, 11, 15]],
  "kwargs": {
    "states": {"current": [1]},
    "pointers": {"left": 0, "right": 3}
  },
  "output": "output/two-sum.png"
}
```

| Field | Required | Meaning |
|---|---|---|
| `engine` | No | `modern` by default; use `legacy` narrowly |
| `renderer` | Yes | Allowlisted `draw_*` function name |
| `args` | No | Positional arguments as an array |
| `kwargs` | No | Keyword arguments; omit `filename` and `output` |
| `output` | No | `.png`, `.svg`, or `.pdf` destination; defaults to `output/<renderer>.png` under the current directory |

Unknown top-level fields are rejected. `filename`, `output`, `ax`, `axes`, and `theme` are reserved inside `kwargs`; use direct Python for Axes composition or a custom `VizTheme`. If supplied, `dpi` must be an integer in `36..600`.

Run a file specification:

```bash
python <skill-dir>/scripts/render.py --spec spec.json
```

Read a specification from stdin:

```bash
python <skill-dir>/scripts/render.py --spec -
```

On PowerShell, prefer `--spec <file>` or pipe a literal here-string to `--spec -`. The `--json` option is most reliable in POSIX shells because Windows command-line parsing can strip embedded double quotes.

The command prints one JSON object with `engine`, `renderer`, absolute `path`, and `size_bytes`. Legacy calls may also include the captured `legacy_log`; stdout still contains only the result JSON. It exits with code 2 and writes a concise error to stderr when validation or rendering fails. Add `--debug` only when a traceback is needed.

## Tagged JSON values

Plain JSON cannot represent tuples, sets, or non-string mapping keys. The CLI recursively decodes three exact tagged forms.

Tuple:

```json
{"$tuple": [1, 2]}
```

Set:

```json
{"$set": [1, 2, 3]}
```

Mapping with arbitrary hashable keys:

```json
{
  "$map": [
    [{"$tuple": ["A", "B"]}, "path"],
    [{"$tuple": ["B", "C"]}, "visited"]
  ]
}
```

Use a tagged mapping for tuple-key options such as `edge_states`, matrix cell states, and cell annotations. Duplicate decoded keys and unhashable keys are rejected.

When a renderer only iterates or unpacks a coordinate, a normal JSON array often suffices. Use `$tuple` when hashability or an exact tuple key is required.

## Examples

### Grid with coordinate states

```json
{
  "renderer": "draw_grid",
  "args": [[[0, 0, 0], [0, 1, 0], [0, 0, 0]]],
  "kwargs": {
    "start": [0, 0],
    "end": [2, 2],
    "obstacles": [[1, 1]],
    "path": [[0, 0], [0, 1], [0, 2], [1, 2], [2, 2]],
    "states": {
      "$map": [
        [{"$tuple": [0, 2]}, "current"]
      ]
    }
  },
  "output": "output/grid.png"
}
```

### Segment tree from nested mappings

```json
{
  "renderer": "draw_segment_tree",
  "args": [{
    "interval": [0, 3],
    "value": 10,
    "children": [
      {"interval": [0, 1], "value": 3, "children": [
        {"interval": [0, 0], "value": 1},
        {"interval": [1, 1], "value": 2}
      ]},
      {"interval": [2, 3], "value": 7, "children": [
        {"interval": [2, 2], "value": 3},
        {"interval": [3, 3], "value": 4}
      ]}
    ]
  }],
  "kwargs": {
    "original": [1, 2, 3, 4],
    "query_range": [1, 2]
  },
  "output": "output/segment-tree.png"
}
```

### Graph with tuple-key edge states

```json
{
  "renderer": "draw_graph",
  "args": [[
    ["A", "B", 4],
    ["A", "C", 0],
    ["C", "B", 1]
  ]],
  "kwargs": {
    "nodes": ["A", "B", "C", "D"],
    "edge_states": {
      "$map": [
        [{"$tuple": ["A", "C"]}, "path"]
      ]
    }
  },
  "output": "output/graph.png"
}
```

### Legacy skip list

```json
{
  "engine": "legacy",
  "renderer": "draw_skip_list",
  "args": [[[1, 2, 3, 4, 5], [1, 3, 5], [3]]],
  "kwargs": {"title": "Skip List"},
  "output": "output/skip-list.png"
}
```

The wrapper never evaluates Python expressions and never resolves a function outside its explicit modern or legacy renderer allowlist. It rejects duplicate JSON keys, `NaN`/`Infinity`, non-finite decoded numbers, inputs over 2 MiB, excessive nesting, excessive item counts, and unsupported output suffixes. Existing destination files are intentionally replaced, matching the renderer library's normal file-output behavior.
