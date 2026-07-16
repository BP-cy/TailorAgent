# Renderer API reference

## Contents

- [Shared contract](#shared-contract)
- [Linear, tabular, and interval views](#linear-tabular-and-interval-views)
- [Trees and indexed structures](#trees-and-indexed-structures)
- [Graphs and geometry](#graphs-and-geometry)
- [Important input shapes](#important-input-shapes)
- [Legacy-only capability](#legacy-only-capability)

## Shared contract

Import modern functions from `scripts/algorithm_viz.py`. Most single-panel renderers accept:

| Parameter | Meaning |
|---|---|
| `title` | Figure or panel title |
| `filename` | Destination used by `output="file"` |
| `ax` | Existing Matplotlib Axes for composition |
| `output` | `file`, `figure`, `bytes`, or `axes` |
| `theme` | `VizTheme` semantic colors and typography |
| `dpi` | Raster resolution |
| `transparent` | Transparent output background |

The monotonic and graph-representation composite functions use `axes` and return all supplied/created Axes for `output="axes"`. `draw_heap` is a fixed internal two-panel view and is an exception described below.

Output behavior:

- `file` saves and returns an absolute path; a bare filename is placed under `./output/` relative to the process working directory.
- `figure` returns an open Matplotlib Figure owned by the caller.
- `bytes` returns PNG bytes.
- `axes` returns the drawn Axes without saving or closing the Figure.
- Passing an existing Axes clears that Axes before drawing.

Many index/node renderers accept either item-to-state:

```python
{2: "current", 4: "success"}
```

or state-to-items:

```python
{"current": [2], "success": [4]}
```

The second form is easier in plain JSON because JSON object keys are strings. Cell-addressed functions (`draw_multi_array`, `draw_matrix`, `draw_grid`, `draw_dp_table`, and `draw_buckets`), Trie prefixes, graph edges, and annotations require selector-to-state mappings. Use the CLI's tagged `$map` form for tuple keys.

## Linear, tabular, and interval views

| Renderer | Required data | Useful options |
|---|---|---|
| `draw_array` | sequence | `states`, `pointers`, `ranges`, `index_labels`, `row_label` |
| `draw_string` | string | `pattern`, `pattern_offset`, `states`, `pointers`, `ranges` |
| `draw_multi_array` | row-name mapping or rows | cell `states`, `column_labels` |
| `draw_matrix` / `draw_table` | rows of cells | cell `states`, row/column labels, annotations |
| `draw_grid` | rectangular grid | `start`, `end`, `obstacles`, `path`, cell arrows |
| `draw_stack` | bottom-to-top sequence | `states`, `pointers`, `capacity`, `top_label` |
| `draw_queue` | front-to-rear sequence | `states`, `pointers`, endpoint labels |
| `draw_deque` | front-to-rear sequence | endpoint labels, operation arrows, states |
| `draw_circular_queue` | fixed slot sequence; `None` is empty | `front`, `rear`, `states` |
| `draw_linked_list` | node values | `doubly`, `circular`, `pointers`, `random_links` |
| `draw_dp_table` | DP matrix | cell states, dependencies, current/answer, formulas |
| `draw_intervals` / `draw_timeline` | intervals | scan position and axis label |
| `draw_mapping` | key/value mapping | separate key/value states |
| `draw_set` | iterable | element states |
| `draw_buckets` | bucket mapping or rows | bucket-entry states |
| `draw_histogram` | numeric heights | states, pointers, rectangle, water levels |
| `draw_hash_table_chaining` | bucket-to-pairs mapping | `capacity` |
| `draw_hash_table_open_addressing` | pair-or-`None` slots | `capacity`, probe sequences |
| `draw_bitmask` / `draw_bits` | integer or label-to-integer mapping | width, signed mode, highlighted bits, bit labels |

`draw_monotonic_stack(values, stack_indices, ...)` and `draw_monotonic_queue(values, deque_indices, ...)` create two-panel algorithm views. Their structure entries are original array indices, not local positions or values.

## Trees and indexed structures

| Renderer | Input | Important behavior |
|---|---|---|
| `draw_tree` | `TreeNode`, nested mapping, or scalar root | General N-ary tree; vertical/horizontal orientation |
| `draw_binary_tree` | level-order sequence, mapping, or `TreeNode` | Binary layout with semantic states |
| `draw_heap` / `draw_binary_heap` | heap array | Combined tree and array; `heap_type` is `min` or `max`, but the renderer does not validate the heap property |
| `draw_trie` | sequence of words | Terminal double rings, word/prefix path, optional prefixes |
| `draw_bplus_tree` | nested mapping or `None` | Internal indexes, leaf chain, optional `search_key` |
| `draw_union_find` | integer parent sequence | Parent array plus forest; child-to-parent arrows |
| `draw_segment_tree` | `SegmentTreeNode`, mapping, or `None` | Query coverage, update path, lazy tag, optional source array |
| `draw_fenwick_tree` | BIT value sequence | 1-based ranges and paths; optional source array/binary indices |

Use `previous_parent` with `draw_union_find` to show path compression. The old edge is dashed and a changed parent cell displays `old→new`. The previous and current arrays must give every node the same final representative root, not merely an equivalent partition with renamed roots.

Use `query_index` or `query_path` with `draw_fenwick_tree`, never both. Use `update_index` or `update_path`, never both. Set `includes_sentinel=True` only when the supplied sequence includes a zero-index sentinel.

Aliases:

- `draw_binary_indexed_tree` → `draw_fenwick_tree`
- `draw_binary_heap` → `draw_heap`
- `draw_doubly_linked_list` → `draw_linked_list(doubly=True)`

## Graphs and geometry

| Renderer | Purpose | Key options |
|---|---|---|
| `draw_graph` | Directed/undirected weighted graph | nodes, circular/hierarchical layout, manual positions, path, node/edge states |
| `draw_dag` | Strict directed acyclic graph | rejects cycles/self-loops; layered layout and skip-edge routing |
| `draw_adjacency_list` | Adjacency-list panel | explicit node order, directed flag |
| `draw_adjacency_matrix` | Matrix panel | explicit node order, directed flag, absent marker |
| `draw_edge_list` | Edge table | source, target, optional weight |
| `draw_graph_representations` | Multi-panel comparison | select graph/list/matrix/edge-list panels |
| `draw_coordinate_plane` | Geometry state | points, segments, vectors, polygons, circles, scan lines |

Graph edges may be `(source, target)` or `(source, target, weight)`. A weight of zero is a real edge. Supply `nodes` to retain isolated nodes and control matrix order. Adjacency matrices reject parallel edges rather than silently dropping one.

Coordinate vectors use `origin + delta`, not two endpoints. Polygon vertices are connected in caller-provided order; the renderer does not compute a convex hull. Circle radius must be positive, and all coordinates must be finite.

## Important input shapes

Nested general tree:

```json
{"value":"root","children":[{"value":"left"},{"value":"right"}]}
```

Segment-tree node:

```json
{
  "interval":[0,1],
  "value":3,
  "lazy":null,
  "children":[
    {"interval":[0,0],"value":1},
    {"interval":[1,1],"value":2}
  ]
}
```

B+ tree internal and leaf nodes:

```json
{
  "keys":[20],
  "children":[
    {"keys":[5,10],"leaf":true},
    {"keys":[20,30],"leaf":true}
  ]
}
```

For B+ trees, every internal node must have `len(keys) + 1` children, keys must already be sorted, and all leaves must have the same depth. `order` must be at least 3 but does not currently enforce node occupancy or maximum key counts.

Geometry objects can use mappings such as:

```json
{
  "origin":[0,0],
  "delta":[3,2],
  "label":"v",
  "state":"current"
}
```

## Legacy-only capability

Import `draw_skip_list` from `scripts/datastruct_viz.py`, or use the CLI with `"engine":"legacy"`. Keep all other new work on the modern API unless an old signature is explicitly required.

`draw_heap` has no external `ax`/`axes` parameter. Its current `output="axes"` returns only its tree panel, so use `output="figure"` when both heap panels must be retained or further edited.
