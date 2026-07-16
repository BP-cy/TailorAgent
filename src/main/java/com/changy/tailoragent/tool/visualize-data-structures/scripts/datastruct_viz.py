"""
数据结构可视化工具 — 一键生成博客配图
=======================================
支持: 数组 | 栈 | 队列 | 链表 | 二叉树 | 图
输出: PNG / SVG, 统一扁平教学风格

用法:
    from datastruct_viz import *
    
    draw_array([5, 3, 8, 1, 9], highlight=[2], title="数组 arr")
    draw_stack([3, 7, 2], title="栈 Stack")
    draw_queue([1, 2, 3, 4], title="队列 Queue")
    draw_linked_list([10, 20, 30], title="链表")
    draw_binary_tree([1, 2, 3, 4, 5, 6, 7], title="二叉树")
    draw_graph([(1,2), (2,3), (3,1), (2,4)], title="有向图")
"""

import matplotlib
matplotlib.use("Agg")  # 无 GUI 后端
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
import matplotlib.font_manager as fm
from matplotlib.patches import FancyBboxPatch, FancyArrowPatch, Circle, Arc, Polygon
import numpy as np
import os
import math
from pathlib import Path

# ---------- 中文字体设置 (Windows / macOS / Linux) ----------
_CJK_FONT = None


def _detect_cjk_font():
    """检测系统可用的中文字体,返回字体名;若没有则返回 None"""
    global _CJK_FONT
    if _CJK_FONT is not None:
        return _CJK_FONT

    # 按优先级尝试
    candidates = [
        "Microsoft YaHei",
        "SimHei",
        "PingFang SC",
        "Heiti SC",
        "Noto Sans CJK SC",
        "WenQuanYi Micro Hei",
        "WenQuanYi Zen Hei",
        "Source Han Sans SC",
        "AR PL UMing CN",
        "Arial Unicode MS",
    ]
    available = {f.name for f in fm.fontManager.ttflist}

    for name in candidates:
        if name in available:
            _CJK_FONT = name
            return name
    return None


_CJK_FONT = _detect_cjk_font()
if _CJK_FONT:
    plt.rcParams["font.family"] = _CJK_FONT
    # 避免负号显示为方框
    plt.rcParams["axes.unicode_minus"] = False

# ============== 全局样式 ==============

# 配色方案 (扁平教学风)
PALETTE = {
    "primary":    "#4A90D9",  # 蓝 - 默认节点
    "secondary":  "#6C757D",  # 灰 - 辅助线/文字
    "highlight":  "#E85D75",  # 红 - 高亮/当前操作元素
    "accent":     "#F5A623",  # 橙 - 指针/标签
    "success":    "#7ED321",  # 绿 - 结果/成功
    "bg":         "#F8F9FA",  # 背景
    "edge":       "#34495E",  # 边/箭头
    "text":       "#2C3E50",  # 正文
    "null":       "#BDC3C7",  # NULL 占位
}

NODE_W = 0.9   # 默认格子宽
NODE_H = 0.7   # 默认格子高
FONT_SIZE = 11
FONT_SIZE_SM = 9
DPI = 150
OUTPUT_DIR = Path("./output")


def _setup_ax(ax, title="", xlim=None, ylim=None):
    """统一初始化坐标轴——去掉杂线,只留标题"""
    ax.set_aspect("equal")
    ax.axis("off")
    ax.set_xlim(xlim or (-0.5, 7))
    ax.set_ylim(ylim or (-0.5, 4))
    if title:
        ax.set_title(title, fontsize=14, fontweight="bold", color=PALETTE["text"], pad=12)


def _save(fig, filename):
    """保存图片到 output/ 目录"""
    OUTPUT_DIR.mkdir(exist_ok=True)
    path = OUTPUT_DIR / filename
    fig.savefig(str(path), dpi=DPI, bbox_inches="tight", facecolor="white", edgecolor="none")
    plt.close(fig)
    print(f"[OK] Saved: {path.resolve()}")
    return str(path.resolve())


def _draw_cell(ax, x, y, text, color=PALETTE["primary"], alpha=1.0,
               w=NODE_W, h=NODE_H, fontsize=FONT_SIZE, text_color="white", bold=False):
    """画一个圆角矩形格子,中间写文字"""
    rect = FancyBboxPatch(
        (x - w/2, y - h/2), w, h,
        boxstyle="round,pad=0.06",
        facecolor=color, edgecolor="white",
        linewidth=1.5, alpha=alpha
    )
    ax.add_patch(rect)
    weight = "bold" if bold else "normal"
    ax.text(x, y, str(text), ha="center", va="center",
            fontsize=fontsize, color=text_color, fontweight=weight)


def _draw_arrow(ax, x1, y1, x2, y2, color=PALETTE["edge"], lw=1.8,
                mutation_scale=12, style="simple", zorder=3):
    """画带箭头的连线"""
    arrow = FancyArrowPatch(
        (x1, y1), (x2, y2),
        arrowstyle=style,
        color=color, lw=lw, mutation_scale=mutation_scale, zorder=zorder
    )
    ax.add_patch(arrow)


def _draw_line(ax, x1, y1, x2, y2, color=PALETTE["edge"], lw=1.2, zorder=1):
    """画无箭头的直线"""
    ax.plot([x1, x2], [y1, y2], color=color, lw=lw, zorder=zorder)


def _draw_label(ax, x, y, text, color=PALETTE["accent"], fontsize=FONT_SIZE_SM, ha="center", bold=False):
    """画标注文字"""
    w = "bold" if bold else "normal"
    ax.text(x, y, text, ha=ha, va="center", fontsize=fontsize,
            color=color, fontweight=w)


# ======================================================================
#  1. 数组 Array
# ======================================================================

def draw_array(arr, highlight=None, highlight_color=None, title="Array",
               index_labels=None, filename="array.png"):
    """
    画一维数组: 一排格子 + 上面写值 + 下面写索引

    Parameters
    ----------
    arr : list
        数组元素
    highlight : list[int] | None
        要高亮的索引列表
    highlight_color : str | None
        高亮颜色,默认用调色板红色
    index_labels : list | None
        索引标签,默认 0,1,2,...
    """
    n = len(arr)
    highlight = highlight or []
    hl_color = highlight_color or PALETTE["highlight"]
    if index_labels is None:
        index_labels = [str(i) for i in range(n)]

    w_total = n * (NODE_W + 0.15)
    fig, ax = plt.subplots(figsize=(max(w_total, 4), 2.5))
    _setup_ax(ax, title, xlim=(-0.8, n + 0.2), ylim=(-1.2, 1.0))

    for i, val in enumerate(arr):
        color = hl_color if i in highlight else PALETTE["primary"]
        _draw_cell(ax, i, 0.1, val, color=color, bold=(i in highlight))
        # 索引标签
        _draw_label(ax, i, -0.55, index_labels[i], color=PALETTE["secondary"], fontsize=FONT_SIZE_SM)

    return _save(fig, filename)


# ======================================================================
#  2. 栈 Stack
# ======================================================================

def draw_stack(stack, top_label="top", title="Stack",
               highlight_top=True, filename="stack.png"):
    """
    画栈: 竖直堆叠,顶部有指针标注

    Parameters
    ----------
    stack : list
        栈元素,栈底在前(第一个元素是 bottom)
    """
    from algorithm_viz import draw_stack as _draw_stack_v2

    states = {len(stack) - 1: "current"} if highlight_top and stack else None
    return _draw_stack_v2(
        stack, states=states, top_label=top_label, title=title, filename=filename,
    )


# ======================================================================
#  3. 队列 Queue
# ======================================================================

def draw_queue(queue, front_label="front", rear_label="rear", title="Queue",
               filename="queue.png"):
    """
    画队列: 水平排列,FIFO 管道风格

    Parameters
    ----------
    queue : list
        队首在前,队尾在后
    """
    n = len(queue)
    w_total = max(n * (NODE_W + 0.15) + 4, 5.5)
    fig, ax = plt.subplots(figsize=(w_total, 2.5))
    _setup_ax(ax, title, xlim=(-1.8, n + 2.2), ylim=(-1.2, 1.2))

    for i, val in enumerate(queue):
        is_front = (i == 0)
        is_rear = (i == n - 1)
        color = PALETTE["primary"]
        if is_front:
            color = PALETTE["highlight"] if n > 0 else PALETTE["primary"]
        _draw_cell(ax, i, 0, val, color=color, bold=is_front)

    # 入队方向箭头 (从右侧进入队尾)
    _draw_arrow(ax, n + 0.8, 0.55, n - 0.45, 0.55, color=PALETTE["accent"], lw=1.5)
    _draw_label(ax, n + 1.1, 0.55, "enqueue", color=PALETTE["accent"], ha="left", fontsize=FONT_SIZE_SM)

    # 出队方向箭头 (队首向左离开)
    _draw_arrow(ax, -0.45, -0.55, -1.15, -0.55, color=PALETTE["success"], lw=1.5)
    _draw_label(ax, -1.1, -0.55, "dequeue", color=PALETTE["success"], ha="right", fontsize=FONT_SIZE_SM)

    # front / rear 指针
    if n > 0:
        _draw_label(ax, 0, -0.85, front_label, color=PALETTE["highlight"], bold=True, fontsize=FONT_SIZE_SM)
        _draw_label(ax, n - 1, 0.85, rear_label, color=PALETTE["accent"], bold=True, fontsize=FONT_SIZE_SM)
    else:
        _draw_label(ax, 0, 0, "(empty)", color=PALETTE["null"], fontsize=FONT_SIZE, bold=True)

    return _save(fig, filename)


# ======================================================================
#  4. 链表 Linked List
# ======================================================================

def draw_linked_list(values, title="Linked List", null_text="NULL",
                     filename="linked_list.png"):
    """
    画单链表: 节点分 data / next 两格,箭头连接,末尾 NULL

    Parameters
    ----------
    values : list
        链表各节点的 data 值
    """
    n = len(values)
    w_total = max(n * 1.65 + 1.5, 5)
    fig, ax = plt.subplots(figsize=(w_total, 2.2))
    _setup_ax(ax, title, xlim=(-0.5, n * 1.65 + 1.0), ylim=(-1.0, 1.2))

    for i, val in enumerate(values):
        cx = i * 1.65
        # data 格子
        _draw_cell(ax, cx, 0, val, color=PALETTE["primary"], w=0.7, h=0.6, fontsize=FONT_SIZE - 1)
        # next 格子 (小)
        _draw_cell(ax, cx + 0.6, 0, "next", color=PALETTE["secondary"], w=0.55, h=0.45,
                   fontsize=FONT_SIZE_SM - 1, alpha=0.7)

        # 分割线
        ax.plot([cx + 0.35, cx + 0.35], [-0.3, 0.3], color="white", lw=1.5, zorder=5)

        # 箭头到下一个节点
        if i < n - 1:
            _draw_arrow(ax, cx + 0.88, 0, cx + 1.65 - 0.35, 0,
                        color=PALETTE["edge"], lw=1.5)

    # head 标签
    if n > 0:
        _draw_label(ax, 0, 0.65, "head", color=PALETTE["highlight"], bold=True, fontsize=FONT_SIZE_SM)

    # NULL
    if n > 0:
        null_x = (n - 1) * 1.65 + 0.88
        _draw_arrow(ax, null_x, 0, null_x + 0.45, 0, color=PALETTE["edge"], lw=1.5)
        _draw_label(ax, null_x + 0.65, 0, null_text, color=PALETTE["null"], ha="left", bold=True, fontsize=FONT_SIZE_SM)
    else:
        _draw_label(ax, 1, 0, "(empty)", color=PALETTE["null"], fontsize=FONT_SIZE, bold=True)

    return _save(fig, filename)


# ======================================================================
#  5. 二叉树 Binary Tree
# ======================================================================

def _tree_layout(values):
    """
    将层序数组转为 (x, y) 坐标列表。
    values[i] 不为 None 的位置画节点,否则跳过(空子节点)。
    返回 [(x, y, value, index), ...]
    """
    if not values:
        return []
    positions = []
    for i, val in enumerate(values):
        if val is None:
            continue
        level = int(math.floor(math.log2(i + 1)))
        pos_in_level = i - (2**level - 1)
        nodes_in_level = 2**level
        # x 均匀分布在该层
        x = (pos_in_level - (nodes_in_level - 1) / 2) * (1.6 / max(nodes_in_level / 2, 1))
        # 根据树深度缩放
        max_level = int(math.floor(math.log2(len(values)))) if len(values) > 1 else 0
        scale = 1.0 / max(1, max_level) * 2.5
        x *= (1 + max_level * 0.6)
        y = -level * 1.2
        positions.append((x, y, val, i))
    return positions


def draw_binary_tree(values, title="Binary Tree", null_marker="∅",
                     filename="binary_tree.png"):
    """
    画二叉树: 层序数组表示,画节点+父子边

    Parameters
    ----------
    values : list
        层序遍历的二叉树值,None 表示空节点。如 [1, 2, 3, None, 5, 6, 7]
    """
    n = len(values)
    if n == 0:
        fig, ax = plt.subplots(figsize=(4, 2))
        _setup_ax(ax, title, xlim=(-3, 3), ylim=(-3, 1))
        _draw_label(ax, 0, -1, "(empty tree)", color=PALETTE["null"], fontsize=FONT_SIZE, bold=True)
        return _save(fig, filename)

    positions = _tree_layout(values)
    if not positions:
        fig, ax = plt.subplots(figsize=(4, 2))
        _setup_ax(ax, title, xlim=(-3, 3), ylim=(-3, 1))
        _draw_label(ax, 0, -1, "(empty tree)", color=PALETTE["null"], fontsize=FONT_SIZE, bold=True)
        return _save(fig, filename)

    # 按 index 建立映射
    idx_map = {p[3]: p for p in positions}

    # 计算画布
    all_x = [p[0] for p in positions]
    all_y = [p[1] for p in positions]
    x_range = max(abs(min(all_x)), abs(max(all_x))) + 1.5
    y_range = abs(min(all_y)) + 1.5

    fig, ax = plt.subplots(figsize=(max(x_range * 0.7, 4), max((y_range) * 0.7, 3)))
    _setup_ax(ax, title, xlim=(-x_range, x_range), ylim=(min(all_y) - 1, max(all_y) + 1.2))

    # 先画边
    for i in range(n):
        if values[i] is None:
            continue
        left_idx = 2 * i + 1
        right_idx = 2 * i + 2
        parent = idx_map.get(i)
        if parent is None:
            continue
        px, py = parent[0], parent[1]
        for child_idx in [left_idx, right_idx]:
            child = idx_map.get(child_idx)
            if child is None:
                continue
            cx, cy = child[0], child[1]
            _draw_line(ax, px, py - 0.28, cx, cy + 0.28,
                       color=PALETTE["edge"], lw=1.5, zorder=1)

    # 再画节点
    for x, y, val, idx in positions:
        is_root = (idx == 0)
        r = 0.32
        circle = Circle((x, y), r, facecolor=PALETTE["primary"], edgecolor="white", linewidth=1.5, zorder=3)
        ax.add_patch(circle)
        ax.text(x, y, str(val), ha="center", va="center", fontsize=FONT_SIZE - 1,
                color="white", fontweight="bold", zorder=4)

    # root 标签
    if idx_map.get(0):
        rx, ry = idx_map[0][0], idx_map[0][1]
        _draw_label(ax, rx, ry + 0.6, "root", color=PALETTE["highlight"], bold=True, fontsize=FONT_SIZE_SM)

    return _save(fig, filename)


# ======================================================================
#  6. 图 Graph
# ======================================================================

def _circular_layout(nodes):
    """圆形布局: 将节点均匀分布在圆上"""
    n = len(nodes)
    positions = {}
    for i, node in enumerate(nodes):
        angle = 2 * math.pi * i / n - math.pi / 2
        r = max(n / 5, 1.5)
        x = r * math.cos(angle)
        y = r * math.sin(angle)
        positions[node] = (x, y)
    return positions


def draw_graph(edges, nodes=None, directed=True, title="Graph",
               layout="circular", filename="graph.png"):
    """
    画图: 节点 + 边

    Parameters
    ----------
    edges : list[tuple]
        边列表,如 [(1,2), (2,3), (3,1)]
    nodes : list | None
        节点列表,默认从 edges 自动提取
    directed : bool
        True 为有向图, False 为无向图
    layout : str
        "circular" 圆形布局 (目前只支持这个)
    """
    from algorithm_viz import draw_graph as _draw_graph_v2

    return _draw_graph_v2(
        edges, nodes=nodes, directed=directed, layout=layout,
        title=title, filename=filename,
    )


# ======================================================================
#  一键生成全部示例
# ======================================================================

def demo_all():
    """生成所有数据结构示例图,放在 output/ 目录"""
    print("\n>>> 开始生成示例图...\n")

    # ---- 基础数据结构 ----
    draw_array([5, 3, 8, 1, 9, 2],
               highlight=[2, 4],
               title="Array — 访问 arr[2] 和 arr[4]",
               index_labels=["0", "1", "2", "3", "4", "5"],
               filename="demo_array.png")

    draw_stack(["A", "B", "C", "D"],
               title="Stack — push/pop",
               filename="demo_stack.png")

    draw_queue(["cat", "dog", "rabbit", "fox"],
               title="Queue — FIFO (first in, first out)",
               filename="demo_queue.png")

    draw_linked_list([10, 20, 30, 40],
                      title="Linked List — 单链表",
                      filename="demo_linked_list.png")

    draw_binary_tree([50, 30, 70, 20, 40, 60, 80],
                      title="Binary Search Tree (BST)",
                      filename="demo_binary_tree.png")

    draw_graph(
        edges=[(1, 2), (1, 3), (2, 4), (3, 4), (4, 5), (2, 5)],
        directed=False,
        title="Undirected Graph",
        filename="demo_graph.png"
    )

    draw_graph(
        edges=[("A", "B"), ("A", "C"), ("B", "D"), ("C", "D"), ("D", "E")],
        directed=True,
        layout="hierarchical",
        title="Directed Graph (DAG)",
        filename="demo_dag.png"
    )

    # ---- 高级数据结构 ----
    draw_binary_heap([2, 5, 3, 8, 12, 7, 10],
                     heap_type="min",
                     title="Min Heap (Binary Heap)",
                     filename="demo_min_heap.png")

    draw_binary_heap([50, 40, 30, 20, 10, 5, 3],
                     heap_type="max",
                     title="Max Heap (Binary Heap)",
                     filename="demo_max_heap.png")

    draw_hash_table_chaining(
        {0: [("apple", 1), ("banana", 2)],
         2: [("cherry", 3)],
         4: [("date", 4), ("elderberry", 5), ("fig", 6)]},
        capacity=8,
        title="Hash Table — Chaining",
        filename="demo_hash_chaining.png"
    )

    draw_hash_table_open_addr(
        [("k1","v1"), None, ("k2","v2"), ("k3","v3"), None,
         ("k4","v4"), None, None],
        probe_seqs={5: [0, 5], 2: [2], 3: [3]},
        title="Hash Table — Open Addressing",
        filename="demo_hash_open_addr.png"
    )

    draw_trie(["cat", "car", "dog", "dot", "team", "ten"],
              title="Trie (Prefix Tree)",
              filename="demo_trie.png")

    draw_skip_list([[1,2,3,4,5,6,7,8],
                    [1,3,5,7],
                    [3,7]],
                   title="Skip List",
                   filename="demo_skip_list.png")

    draw_bplus_tree(
        {"keys": [30],
         "children": [
             {"keys": [10, 20],
              "children": [
                  {"keys": [5, 8], "leaf": True},
                  {"keys": [10, 15], "leaf": True},
                  {"keys": [22, 25, 28], "leaf": True},
              ]},
             {"keys": [45, 55],
              "children": [
                  {"keys": [32, 35, 38], "leaf": True},
                  {"keys": [48, 50], "leaf": True},
                  {"keys": [58, 62], "leaf": True},
              ]}
         ]},
        order=3,
        title="B+ Tree (order=3)",
        filename="demo_bplus_tree.png"
    )

    print("\n>>> 全部示例已生成完毕!\n")


# ======================================================================
#  7. 二叉堆 Binary Heap
# ======================================================================

def draw_binary_heap(heap, heap_type="min", title="Binary Heap",
                     filename="binary_heap.png"):
    """
    画二叉堆: 完全二叉树的层序数组表示

    Parameters
    ----------
    heap : list
        层序排列的堆数组,如 [2, 5, 3, 8, 12, 7, 10]
    heap_type : str
        "min" 或 "max"
    """
    n = len(heap)
    if n == 0:
        fig, ax = plt.subplots(figsize=(4, 2))
        _setup_ax(ax, title, xlim=(-3, 3), ylim=(-3, 1))
        _draw_label(ax, 0, -1, "(empty heap)", color=PALETTE["null"], fontsize=FONT_SIZE)
        return _save(fig, filename)

    positions = _tree_layout(heap)
    idx_map = {p[3]: p for p in positions}

    all_x = [p[0] for p in positions]
    all_y = [p[1] for p in positions]
    x_range = max(abs(min(all_x)), abs(max(all_x))) + 1.8
    y_range = abs(min(all_y)) + 2.0

    fig, ax = plt.subplots(figsize=(max(x_range * 0.7, 4.5), max(y_range * 0.7, 3.5)))
    _setup_ax(ax, title, xlim=(-x_range, x_range), ylim=(min(all_y) - 1.0, max(all_y) + 1.5))

    # 边
    for i in range(n):
        left_idx = 2 * i + 1
        right_idx = 2 * i + 2
        parent = idx_map.get(i)
        if parent is None:
            continue
        px, py = parent[0], parent[1]
        for child_idx in [left_idx, right_idx]:
            child = idx_map.get(child_idx)
            if child is None:
                continue
            cx, cy = child[0], child[1]
            _draw_line(ax, px, py - 0.3, cx, cy + 0.3,
                       color=PALETTE["edge"], lw=1.5, zorder=1)

    # 节点
    for x, y, val, idx in positions:
        r = 0.34
        color = PALETTE["highlight"] if idx == 0 else PALETTE["primary"]
        circle = Circle((x, y), r, facecolor=color, edgecolor="white",
                        linewidth=1.8, zorder=3)
        ax.add_patch(circle)
        ax.text(x, y, str(val), ha="center", va="center", fontsize=FONT_SIZE - 1,
                color="white", fontweight="bold", zorder=4)
        # 索引放在节点右下侧，避开父子边的扇出区域。
        _draw_label(ax, x + r + 0.08, y - 0.12, f"[{idx}]", color=PALETTE["secondary"],
                    fontsize=FONT_SIZE_SM - 1, ha="left")

    # 根标签
    if idx_map.get(0):
        rx, ry = idx_map[0][0], idx_map[0][1]
        label = "min" if heap_type == "min" else "max"
        _draw_label(ax, rx, ry + 0.65, label, color=PALETTE["highlight"],
                    bold=True, fontsize=FONT_SIZE_SM + 1)

    return _save(fig, filename)


# ======================================================================
#  8. 哈希表 Hash Table
# ======================================================================

def draw_hash_table_chaining(table, capacity=8, title="Hash Table (Chaining)",
                             filename="hash_chaining.png"):
    """
    画哈希表 — 链地址法

    Parameters
    ----------
    table : dict[int, list[tuple]]
        槽位索引 -> (key, value) 链表,如
        {0: [("a",1), ("b",2)], 2: [("c",3)]}
    capacity : int
        总槽位数
    """
    from algorithm_viz import draw_hash_table_chaining as _draw_hash_chaining_v2

    return _draw_hash_chaining_v2(
        table, capacity=capacity, title=title, filename=filename,
    )


def draw_hash_table_open_addr(table, capacity=None, probe_seqs=None,
                              title="Hash Table (Open Addressing)",
                              filename="hash_open_addr.png"):
    """
    画哈希表 — 开放寻址法

    Parameters
    ----------
    table : list
        长度 = capacity, 每个位置是 (key, value) 或 None
    probe_seqs : dict | None
        最终位置 -> [探测过的索引列表], 用于画冲突箭头
        如 {5: [0, 5]} 表示最终在位置 5 的元素,最初哈希到 0,探测后落在 5
    """
    from algorithm_viz import draw_hash_table_open_addressing as _draw_hash_open_v2

    return _draw_hash_open_v2(
        table, capacity=capacity, probe_sequences=probe_seqs,
        title=title, filename=filename,
    )


# ======================================================================
#  9. 前缀树 Trie
# ======================================================================

def _build_trie(words):
    """构建 Trie 嵌套字典, '#' 表示单词结束"""
    trie = {}
    for w in words:
        node = trie
        for ch in w:
            if ch not in node:
                node[ch] = {}
            node = node[ch]
        node['#'] = True
    return trie


def _trie_layout_dfs(node, depth, start_x, positions, edges):
    """
    递归布局 Trie:
    - positions: list[(x, y, char, is_end)]
    - edges: list[(px, py, cx, cy, char)]
    - 返回: 该子树占用的 x 范围 (min_x, max_x)
    """
    if not node:
        return (start_x, start_x)

    children = [(ch, child) for ch, child in node.items() if ch != '#']
    is_end = '#' in node

    if not children:
        x = start_x
        y = -depth * 1.0
        if is_end:
            positions.append((x, y, "*", True))
        return (start_x, start_x + 0.8)

    child_ranges = []
    cur_x = start_x
    for ch, child in children:
        child_min, child_max = _trie_layout_dfs(child, depth + 1, cur_x, positions, edges)
        child_ranges.append((ch, child_min, child_max, (child_min + child_max) / 2.0))
        cur_x = child_max + 0.3

    my_x = (child_ranges[0][1] + child_ranges[-1][2]) / 2.0
    my_y = -depth * 1.0
    my_min = child_ranges[0][1]
    my_max = child_ranges[-1][2]

    positions.append((my_x, my_y, "", is_end))

    for ch, cmin, cmax, cx in child_ranges:
        edges.append((my_x, my_y, cx, -(depth + 1) * 1.0, ch))

    return (my_min, my_max)


def draw_trie(words, title="Trie", filename="trie.png"):
    """
    画前缀树 (Trie)

    Parameters
    ----------
    words : list[str]
        单词列表,如 ["cat", "car", "dog", "dot", "team", "ten"]
    """
    # 新版使用真实的父子关系和叶宽布局；保留旧函数签名以兼容调用方。
    from algorithm_viz import draw_trie as _draw_trie_v2

    return _draw_trie_v2(words, title=title, filename=filename)


# ======================================================================
#  10. 跳表 Skip List
# ======================================================================

def draw_skip_list(layers, title="Skip List", filename="skip_list.png"):
    """
    画跳表: 多层水平链表 + 层间竖线

    Parameters
    ----------
    layers : list[list]
        从底层到高层的值列表:
        [[1,2,3,4,5,6,7,8],  # L0 (底层, 所有元素)
         [1,3,5,7],            # L1
         [3,7]]                # L2 (顶层)
    """
    if not layers:
        fig, ax = plt.subplots(figsize=(4, 2))
        _setup_ax(ax, title, xlim=(-3, 3), ylim=(-3, 1))
        _draw_label(ax, 0, -1, "(empty)", color=PALETTE["null"], fontsize=FONT_SIZE)
        return _save(fig, filename)

    n_layers = len(layers)
    all_vals = sorted(set(v for layer in layers for v in layer))
    val_to_x = {v: i * 1.2 for i, v in enumerate(all_vals)}

    total_w = max(len(all_vals) * 1.2 + 2, 6)
    total_h = n_layers * 1.0 + 2.5

    fig, ax = plt.subplots(figsize=(total_w, total_h * 0.7))
    _setup_ax(ax, title,
              xlim=(-2.4, len(all_vals) * 1.2 + 1.5),
              ylim=(-1.0, n_layers * 0.85 + 0.5))

    common_head_x = -1.25
    for li, layer in enumerate(layers):
        # 输入顺序是底层到高层，因此 L0 应位于最下方。
        y = li * 0.85
        _draw_label(ax, common_head_x - 0.55, y, f"L{li}", color=PALETTE["secondary"],
                    fontsize=FONT_SIZE_SM, bold=True, ha="right")

        if not layer:
            continue

        head_x = common_head_x
        _draw_cell(ax, head_x, y, "HEAD", color=PALETTE["text"],
                   w=0.7, h=0.42, fontsize=FONT_SIZE_SM - 2)

        prev_x = head_x
        for val in layer:
            cx = val_to_x[val]
            _draw_arrow(ax, prev_x + 0.35, y, cx - 0.3, y,
                        color=PALETTE["edge"], lw=1.3)
            _draw_cell(ax, cx, y, str(val), color=PALETTE["primary"],
                       w=0.55, h=0.42, fontsize=FONT_SIZE_SM - 1)
            prev_x = cx

        null_x = prev_x + 0.7
        _draw_arrow(ax, prev_x + 0.28, y, null_x - 0.1, y,
                    color=PALETTE["edge"], lw=1.3)
        _draw_label(ax, null_x, y, "NULL", color=PALETTE["null"],
                    fontsize=FONT_SIZE_SM, ha="left")

    # 层间竖线
    for li in range(n_layers - 1):
        y_bot = li * 0.85
        y_top = (li + 1) * 0.85
        for val in layers[li]:
            if val in layers[li + 1]:
                cx = val_to_x[val]
                ax.plot([cx, cx], [y_top - 0.21, y_bot + 0.21],
                        color=PALETTE["secondary"], lw=1.0,
                        linestyle=(0, (3, 3)), zorder=0, alpha=0.6)

    return _save(fig, filename)


# ======================================================================
#  11. B+ 树 B+ Tree
# ======================================================================

def _bplus_collect_leaves(node):
    """递归收集 B+ 树所有叶节点 (左到右)"""
    if node.get("leaf"):
        return [node]
    leaves = []
    for child in node.get("children", []):
        leaves.extend(_bplus_collect_leaves(child))
    return leaves


def _bplus_layout(node, leaf_x_map, depth, positions, edges):
    """
    递归布局 B+ 树:
    - positions: list[(x, y, node)]
    - edges: list[(px, py, cx, cy)]
    - 返回该子树 x 范围 (min_x, max_x)
    """
    if node.get("leaf"):
        x = leaf_x_map[id(node)]
        y = 0
        positions.append((x, y, node))
        return (x, x)

    children = node.get("children", [])
    child_ranges = []
    for child in children:
        child_ranges.append(_bplus_layout(child, leaf_x_map, depth + 1, positions, edges))

    my_min = child_ranges[0][0]
    my_max = child_ranges[-1][1]
    my_x = (my_min + my_max) / 2.0
    my_y = -depth * 1.5

    positions.append((my_x, my_y, node))

    for child, (cmin, cmax) in zip(children, child_ranges):
        cx = (cmin + cmax) / 2.0
        cy = -(depth + 1) * 1.5
        edges.append((my_x, my_y, cx, cy))

    return (my_min, my_max)


def _draw_bplus_node_cell(ax, x, y, keys, color=PALETTE["primary"],
                          w_per_key=0.55, h=0.45, fontsize=FONT_SIZE_SM - 1):
    """画 B+ 树节点: 多键矩形带分隔线"""
    n = len(keys)
    if n == 0:
        return
    total_w = n * w_per_key
    rect = FancyBboxPatch(
        (x - total_w / 2, y - h / 2), total_w, h,
        boxstyle="round,pad=0.04",
        facecolor=color, edgecolor="white", linewidth=1.5
    )
    ax.add_patch(rect)

    for i, k in enumerate(keys):
        kx = x - total_w / 2 + (i + 0.5) * w_per_key
        ax.text(kx, y, str(k), ha="center", va="center",
                fontsize=fontsize, color="white", fontweight="bold", zorder=5)
        if i < n - 1:
            sep_x = x - total_w / 2 + (i + 1) * w_per_key
            ax.plot([sep_x, sep_x], [y - h / 2 + 0.06, y + h / 2 - 0.06],
                    color="white", lw=1, zorder=4, alpha=0.6)


def draw_bplus_tree(tree, order=3, title="B+ Tree", filename="bplus_tree.png"):
    """
    画 B+ 树

    Parameters
    ----------
    tree : dict
        嵌套树结构:
        - 内部节点: {"keys": [k1,k2,...], "children": [child1,child2,...]}
        - 叶节点:   {"keys": [k1,k2,...], "leaf": True}
    order : int
        B+ 树的阶 (仅用于标题信息)
    """
    # 新版按真实节点宽度进行自底向上的布局，并使用实际叶节点坐标绘制叶链。
    from algorithm_viz import draw_bplus_tree as _draw_bplus_tree_v2

    return _draw_bplus_tree_v2(tree, order=order, title=title, filename=filename)


if __name__ == "__main__":
    demo_all()
