package com.changy.tailoragent.tool.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 数据结构 renderer 到 AI 工具的静态契约。
 *
 * <p>这里刻意逐项描述 {@code algorithm_viz.py} 的公开 renderer，而不是让模型通过一个通用
 * {@code renderer/args/kwargs} 入口猜函数签名。每个条目最终生成一个独立的 JSON Schema，字段名与
 * Python renderer 参数保持一致；输出位置、渲染引擎和文件格式仍由 Java 工具统一控制。</p>
 */
final class KnowledgeVisualizationToolCatalog {

    static final String MODERN_ENGINE = "modern";
    static final String LEGACY_ENGINE = "legacy";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<ParameterSpec> MODERN_COMMON_PARAMETERS = List.of(
            optional("title", "图片标题；应描述当前算法状态，而不只是结构名称。", string()),
            optional("dpi", "PNG 分辨率，整数范围 36..600；通常省略。", integer(36, 600)),
            optional("transparent", "是否使用透明背景；默认 false。", bool()),
            optional("file_name", "可选输出文件名，不含目录；扩展名可省略，工具始终生成 PNG。", string())
    );

    private static final List<ParameterSpec> LEGACY_COMMON_PARAMETERS = List.of(
            optional("title", "图片标题；应描述当前算法状态，而不只是结构名称。", string()),
            optional("file_name", "可选输出文件名，不含目录；扩展名可省略，工具始终生成 PNG。", string())
    );

    private static final List<RendererSpec> RENDERERS = List.of(
            modern("draw_array", "渲染数组及索引、指针、区间和语义状态",
                    required("values", "按索引排列的数组值。", sequence()),
                    optional("states", "索引状态映射；优先使用 state-to-indices。", mapping()),
                    optional("pointers", "指针标签到数组索引的映射。", mapping()),
                    optional("ranges", "需要标注的区间序列。", sequence()),
                    optional("index_labels", "覆盖默认索引的标签序列，长度应与 values 一致。", sequence()),
                    optional("row_label", "数组行左侧的短标签。", string())),

            modern("draw_string", "渲染字符串、匹配模式、指针、区间和字符状态",
                    required("text", "要展示的原字符串。", string()),
                    optional("pattern", "可选匹配模式字符串。", string()),
                    optional("pattern_offset", "pattern 相对 text 的起始偏移，默认 0。", integer()),
                    optional("states", "字符索引状态映射。", mapping()),
                    optional("pointers", "指针标签到字符索引的映射。", mapping()),
                    optional("ranges", "需要标注的字符区间序列。", sequence())),

            modern("draw_multi_array", "渲染对齐的多行数组",
                    required("rows", "行名到数组的映射，或二维行数组。", arrayOrObject()),
                    optional("states", "单元格 (row,column) 到状态的映射；tuple 键使用 $map/$tuple。", mapping()),
                    optional("column_labels", "各列的标签序列。", sequence())),

            modern("draw_matrix", "渲染矩阵及行列标签、单元格状态和注释",
                    required("matrix", "矩形二维单元格数组。", matrix()),
                    optional("states", "单元格 (row,column) 到状态的映射；tuple 键使用 $map/$tuple。", mapping()),
                    optional("row_labels", "行标签序列。", sequence()),
                    optional("col_labels", "列标签序列。", sequence()),
                    optional("cell_annotations", "单元格坐标到短注释的映射；tuple 键使用 $map/$tuple。", mapping())),

            modern("draw_grid", "渲染网格搜索、障碍、路径和单元格方向",
                    required("grid", "矩形二维网格。", matrix()),
                    optional("states", "单元格坐标到语义状态的映射；tuple 键使用 $map/$tuple。", mapping()),
                    optional("start", "起点坐标 [row,column]。", integerPair()),
                    optional("end", "终点坐标 [row,column]。", integerPair()),
                    optional("obstacles", "障碍坐标列表。", integerPairSequence()),
                    optional("path", "按顺序排列的路径坐标列表。", integerPairSequence()),
                    optional("arrows", "单元格箭头列表，每项为 [fromCoordinate,toCoordinate]。", sequence()),
                    optional("show_coordinates", "是否显示网格坐标，默认 true。", bool())),

            modern("draw_stack", "渲染栈、top、容量、指针和元素状态",
                    required("values", "bottom-to-top 排列的栈元素。", sequence()),
                    optional("states", "栈元素索引状态映射。", mapping()),
                    optional("pointers", "标签到栈元素索引的映射。", mapping()),
                    optional("capacity", "可选固定容量，不得小于当前元素数量。", integer(0, null)),
                    optional("top_label", "栈顶标签，默认 top。", string())),

            modern("draw_queue", "渲染队列、front/rear、指针和元素状态",
                    required("values", "front-to-rear 排列的队列元素。", sequence()),
                    optional("states", "队列元素索引状态映射。", mapping()),
                    optional("pointers", "标签到队列元素索引的映射。", mapping()),
                    optional("front_label", "队首标签，默认 front。", string()),
                    optional("rear_label", "队尾标签，默认 rear。", string())),

            modern("draw_deque", "渲染双端队列及两端操作方向",
                    required("values", "front-to-rear 排列的双端队列元素。", sequence()),
                    optional("states", "元素索引状态映射。", mapping()),
                    optional("pointers", "标签到元素索引的映射。", mapping()),
                    optional("front_label", "队首标签，默认 front。", string()),
                    optional("rear_label", "队尾标签，默认 rear。", string()),
                    optional("show_operations", "是否显示两端操作箭头，默认 true。", bool())),

            modern("draw_circular_queue", "渲染固定槽位的循环队列",
                    required("slots", "固定槽位序列；null 表示空槽。", sequence()),
                    optional("front", "front 的槽位索引。", integer()),
                    optional("rear", "rear 的槽位索引。", integer()),
                    optional("states", "槽位索引状态映射。", mapping())),

            modern("draw_linked_list", "渲染单向、双向、循环或带 random 指针的链表",
                    required("values", "按 next 顺序排列的节点值。", sequence()),
                    optional("states", "节点索引状态映射。", mapping()),
                    optional("pointers", "指针标签到节点索引或 null 的映射。", mapping()),
                    optional("doubly", "是否显示 prev 反向链接，默认 false。", bool()),
                    optional("circular", "尾节点是否链接回头节点，默认 false。", bool()),
                    optional("random_links", "random 链接列表，每项为 [sourceIndex,targetIndexOrNull]。", sequence())),

            modern("draw_doubly_linked_list", "渲染带 prev/next 的双向链表",
                    required("values", "按 next 顺序排列的节点值。", sequence()),
                    optional("states", "节点索引状态映射。", mapping()),
                    optional("pointers", "指针标签到节点索引或 null 的映射。", mapping()),
                    optional("circular", "尾节点是否链接回头节点，默认 false。", bool()),
                    optional("random_links", "random 链接列表，每项为 [sourceIndex,targetIndexOrNull]。", sequence())),

            modern("draw_tree", "渲染一般 N 叉树",
                    required("tree", "嵌套树对象、标量根；对象节点使用 value/label、children。", treeValue()),
                    optional("states", "节点 ID/标签到状态的映射。", mapping()),
                    optional("edge_labels", "(parent,child) 边到短标签的映射；tuple 键使用 $map/$tuple。", mapping()),
                    optionalEnum("orientation", "树布局方向。", "vertical", "horizontal")),

            modern("draw_binary_tree", "渲染二叉树及节点语义状态",
                    required("tree", "层序数组或嵌套树对象；数组中的 null 表示空节点。", arrayOrObject()),
                    optional("states", "节点索引/ID/标签到状态的映射。", mapping())),

            modern("draw_heap", "以树和数组双面板渲染二叉堆",
                    required("values", "按堆数组顺序排列的值。", sequence()),
                    optionalEnum("heap_type", "堆类型；renderer 不会替你验证堆性质。", "min", "max"),
                    optional("states", "堆数组索引状态映射。", mapping())),

            modern("draw_binary_heap", "以树和数组双面板渲染二叉堆（draw_heap 别名）",
                    required("values", "按堆数组顺序排列的值。", sequence()),
                    optionalEnum("heap_type", "堆类型；renderer 不会替你验证堆性质。", "min", "max"),
                    optional("states", "堆数组索引状态映射。", mapping())),

            modern("draw_graph", "渲染有向或无向加权图",
                    required("edges", "边列表；每项为 [source,target] 或 [source,target,weight]。", edges()),
                    optional("nodes", "显式节点顺序；保留孤立节点或固定矩阵顺序时必须提供。", sequence()),
                    optional("directed", "是否为有向图，默认 true。", bool()),
                    optionalEnum("layout", "布局方式。", "circular", "hierarchical"),
                    optional("positions", "节点到 [x,y] 的手工位置映射；非字符串键使用 $map。", mapping()),
                    optional("node_states", "节点状态映射。", mapping()),
                    optional("edge_states", "(source,target) 边状态映射；tuple 键使用 $map/$tuple。", mapping()),
                    optional("path", "按顺序排列的路径节点。", sequence()),
                    optional("node_annotations", "节点到短注释的映射。", mapping())),

            modern("draw_table", "渲染通用二维表格",
                    required("table", "矩形二维单元格数组。", matrix()),
                    optional("states", "单元格 (row,column) 到状态的映射；tuple 键使用 $map/$tuple。", mapping()),
                    optional("row_labels", "行标签序列。", sequence()),
                    optional("col_labels", "列标签序列。", sequence()),
                    optional("cell_annotations", "单元格坐标到短注释的映射；tuple 键使用 $map/$tuple。", mapping())),

            modern("draw_dp_table", "渲染动态规划表、依赖、当前状态和答案",
                    required("table", "DP 二维数组。", matrix()),
                    optional("states", "单元格坐标到状态的映射；tuple 键使用 $map/$tuple。", mapping()),
                    optional("row_labels", "行标签序列。", sequence()),
                    optional("col_labels", "列标签序列。", sequence()),
                    optional("dependencies", "依赖箭头列表，每项为 [sourceCoordinate,targetCoordinate]。", sequence()),
                    optional("current", "当前计算单元格 [row,column]。", integerPair()),
                    optional("answer", "答案单元格 [row,column]。", integerPair()),
                    optional("formulas", "单元格坐标到公式文本的映射；tuple 键使用 $map/$tuple。", mapping())),

            modern("draw_intervals", "渲染区间集合和扫描位置",
                    required("intervals", "区间序列；每项按 Skill 的区间输入形状提供。", sequence()),
                    optional("scan_position", "扫描线所在的数轴位置。", number()),
                    optional("axis_label", "数轴标签。", string())),

            modern("draw_timeline", "以时间轴语义渲染区间集合",
                    required("intervals", "时间区间序列。", sequence()),
                    optional("scan_position", "当前时间或扫描位置。", number()),
                    optional("axis_label", "时间轴标签，默认 time。", string())),

            modern("draw_mapping", "渲染逻辑 key/value 映射",
                    required("mapping", "key 到 value 的映射；非字符串键使用 $map。", mapping()),
                    optional("key_states", "key 的语义状态映射。", mapping()),
                    optional("value_states", "value 的语义状态映射。", mapping())),

            modern("draw_set", "渲染集合及元素状态",
                    required("values", "集合元素数组，或使用 $set 的 tagged 对象。", arrayOrObject()),
                    optional("states", "元素状态映射。", mapping())),

            modern("draw_buckets", "渲染 bucket 分组结构",
                    required("buckets", "bucket 到元素序列的映射，或二维 bucket 数组。", arrayOrObject()),
                    optional("states", "(bucket,entryIndex) 到状态的映射；tuple 键使用 $map/$tuple。", mapping())),

            modern("draw_histogram", "渲染直方图、指针、矩形区域和水位",
                    required("heights", "各柱的数值高度。", numberSequence()),
                    optional("states", "柱索引状态映射。", mapping()),
                    optional("pointers", "指针标签到柱索引的映射。", mapping()),
                    optional("rectangle", "强调矩形 [leftIndex,rightIndex,height]。", numericTriple()),
                    optional("water_levels", "每根柱对应的水位高度。", numberSequence())),

            modern("draw_hash_table_chaining", "渲染拉链法哈希表",
                    required("table", "bucket 到 [key,value] 对序列的映射；整数 bucket 键可使用 $map。", mapping()),
                    optional("capacity", "哈希表容量，默认 8。", integer(1, null))),

            modern("draw_hash_table_open_addressing", "渲染开放寻址哈希表和探测序列",
                    required("table", "槽位序列；每项为 [key,value] 或 null。", sequence()),
                    optional("capacity", "可选容量；省略时由槽位数决定。", integer(1, null)),
                    optional("probe_sequences", "key 到探测槽位索引序列的映射。", mapping())),

            modern("draw_trie", "渲染 Trie、终止节点和单词/前缀路径",
                    required("words", "用于构建 Trie 的单词字符串数组。", stringSequence()),
                    optional("states", "Trie 前缀/节点状态映射。", mapping()),
                    optional("highlight_word", "要突出显示的完整单词。", string()),
                    optional("highlight_prefix", "要突出显示的前缀。", string()),
                    optional("show_prefixes", "是否显示前缀文本，默认 false。", bool())),

            modern("draw_bplus_tree", "渲染 B+ 树内部索引、叶节点链和查找键",
                    requiredNullable("tree", "B+ 树嵌套对象；空树传 null。内部节点 children 数必须为 keys 数 + 1。", nullableObject()),
                    optional("order", "B+ 树阶数，至少为 3。", integer(3, null)),
                    optional("search_key", "可选查找键。", anyValue()),
                    optional("show_leaf_links", "是否显示叶节点链，默认 true。", bool())),

            modern("draw_union_find", "渲染并查集 parent 数组、森林和路径压缩",
                    required("parent", "每个节点的父索引整数数组。", integerSequence()),
                    optional("previous_parent", "路径压缩前的 parent 数组；最终代表根必须与 parent 一致。", integerSequence()),
                    optional("labels", "节点显示标签，长度应与 parent 一致。", sequence()),
                    optional("ranks", "rank 数组或索引到 rank 的映射。", arrayOrObject()),
                    optional("sizes", "size 数组或索引到 size 的映射。", arrayOrObject()),
                    optional("states", "节点索引状态映射。", mapping()),
                    optional("highlight_path", "需要突出显示的节点索引路径。", integerSequence())),

            modern("draw_segment_tree", "渲染线段树、查询覆盖、更新路径和 lazy tag",
                    requiredNullable("tree", "线段树嵌套对象；节点使用 interval/value/children/lazy，空树传 null。", nullableObject()),
                    optional("original", "可选原数组。", sequence()),
                    optional("query_range", "查询闭区间 [left,right]。", integerPair()),
                    optional("update_index", "单点更新的原数组索引。", integer()),
                    optional("states", "节点/区间状态映射。", mapping())),

            modern("draw_fenwick_tree", "渲染 Fenwick Tree 的 1-based 区间、查询路径和更新路径",
                    required("tree_values", "BIT 树值序列。", sequence()),
                    optional("original", "可选原数组。", sequence()),
                    optional("query_path", "预先计算好的 1-based 查询路径；与 query_index 二选一。", integerSequence()),
                    optional("update_path", "预先计算好的 1-based 更新路径；与 update_index 二选一。", integerSequence()),
                    optional("query_index", "由 renderer 推导路径的 1-based 查询索引；与 query_path 二选一。", integer()),
                    optional("update_index", "由 renderer 推导路径的 1-based 更新索引；与 update_path 二选一。", integer()),
                    optional("includes_sentinel", "tree_values 是否含下标 0 哨兵，默认 false。", bool()),
                    optional("show_ranges", "是否显示 lowbit 覆盖区间，默认 true。", bool()),
                    optional("show_binary", "是否显示二进制索引，默认 false。", bool()),
                    optional("states", "逻辑 1-based 索引状态映射。", mapping())),

            modern("draw_binary_indexed_tree", "渲染 Binary Indexed Tree（draw_fenwick_tree 别名）",
                    required("values", "BIT 树值序列。", sequence()),
                    optional("original", "可选原数组。", sequence()),
                    optional("query_path", "预先计算好的 1-based 查询路径；与 query_index 二选一。", integerSequence()),
                    optional("update_path", "预先计算好的 1-based 更新路径；与 update_index 二选一。", integerSequence()),
                    optional("query_index", "由 renderer 推导路径的 1-based 查询索引。", integer()),
                    optional("update_index", "由 renderer 推导路径的 1-based 更新索引。", integer()),
                    optional("includes_sentinel", "values 是否含下标 0 哨兵，默认 false。", bool()),
                    optional("show_ranges", "是否显示 lowbit 覆盖区间，默认 true。", bool()),
                    optional("show_binary", "是否显示二进制索引，默认 false。", bool()),
                    optional("states", "逻辑 1-based 索引状态映射。", mapping())),

            modern("draw_monotonic_stack", "以原数组和栈双面板渲染单调栈快照",
                    required("values", "原数组值。", sequence()),
                    required("stack_indices", "当前栈中元素的原数组索引，按栈顺序排列。", integerSequence()),
                    optional("current_index", "当前处理的原数组索引。", integer()),
                    optional("active_range", "当前活动闭区间 [left,right]。", integerPair()),
                    optional("popped_indices", "本步骤弹出的原数组索引。", integerSequence()),
                    optionalEnum("direction", "单调方向。", "increasing", "decreasing")),

            modern("draw_monotonic_queue", "以原数组和队列双面板渲染单调队列快照",
                    required("values", "原数组值。", sequence()),
                    required("deque_indices", "当前双端队列中的原数组索引，按 front-to-rear 排列。", integerSequence()),
                    optional("window", "当前窗口闭区间 [left,right]。", integerPair()),
                    optional("current_index", "当前处理的原数组索引。", integer()),
                    optional("expired_indices", "本步骤过期移除的原数组索引。", integerSequence()),
                    optionalEnum("direction", "单调方向。", "increasing", "decreasing")),

            modern("draw_adjacency_list", "渲染图的邻接表表示",
                    required("edges", "边列表；每项为 [source,target] 或 [source,target,weight]。", edges()),
                    optional("nodes", "显式节点顺序；用于保留孤立节点。", sequence()),
                    optional("directed", "是否为有向图，默认 true。", bool())),

            modern("draw_adjacency_matrix", "渲染图的邻接矩阵表示",
                    required("edges", "边列表；每项为 [source,target] 或 [source,target,weight]。", edges()),
                    optional("nodes", "矩阵节点顺序；顺序重要或有孤立节点时必须提供。", sequence()),
                    optional("directed", "是否为有向图，默认 true。", bool()),
                    optional("absent", "无边单元格的显示标记。", anyValue())),

            modern("draw_edge_list", "渲染图的边列表表格",
                    required("edges", "边列表；每项为 [source,target] 或 [source,target,weight]。", edges())),

            modern("draw_graph_representations", "多面板比较图、邻接表、邻接矩阵和边列表",
                    required("edges", "边列表；每项为 [source,target] 或 [source,target,weight]。", edges()),
                    optional("nodes", "显式节点顺序；用于保留孤立节点和固定各面板顺序。", sequence()),
                    optional("directed", "是否为有向图，默认 true。", bool()),
                    optionalEnumArray("show", "要展示的面板，值不可重复。",
                            "graph", "adjacency_list", "adjacency_matrix", "edge_list"),
                    optionalEnum("graph_layout", "图面板布局。", "circular", "hierarchical")),

            modern("draw_dag", "渲染严格有向无环图，并拒绝环和自环",
                    required("edges", "有向边列表；每项为 [source,target] 或 [source,target,weight]。", edges()),
                    optional("nodes", "显式节点顺序；用于保留孤立节点。", sequence()),
                    optional("node_states", "节点状态映射。", mapping()),
                    optional("edge_states", "(source,target) 边状态映射；tuple 键使用 $map/$tuple。", mapping()),
                    optional("path", "按顺序排列的路径节点。", sequence()),
                    optional("node_annotations", "节点到短注释的映射。", mapping())),

            modern("draw_bitmask", "渲染一个或多个位掩码及高亮位",
                    required("masks", "单个整数，或标签到整数掩码的映射。", integerOrObject()),
                    optional("width", "显示位宽；省略时自动推导。", integer(1, null)),
                    optional("signed", "是否按有符号模式解释，默认 false。", bool()),
                    optional("states", "掩码行或位的状态映射。", mapping()),
                    optional("highlight_bits", "高亮位编号数组，或标签到位编号数组的映射；LSB=0。", arrayOrObject()),
                    optional("bit_labels", "位编号到短标签的映射。", mapping()),
                    optional("msb_left", "最高位是否显示在左侧，默认 true。", bool())),

            modern("draw_bits", "渲染一个或多个位序列（draw_bitmask 别名）",
                    required("value", "单个整数，或标签到整数值的映射。", integerOrObject()),
                    optional("width", "显示位宽；省略时自动推导。", integer(1, null)),
                    optional("signed", "是否按有符号模式解释，默认 false。", bool()),
                    optional("states", "值行或位的状态映射。", mapping()),
                    optional("highlight_bits", "高亮位编号数组，或标签到位编号数组的映射；LSB=0。", arrayOrObject()),
                    optional("bit_labels", "位编号到短标签的映射。", mapping()),
                    optional("msb_left", "最高位是否显示在左侧，默认 true。", bool())),

            modern("draw_coordinate_plane", "渲染点、线段、向量、多边形、圆和扫描线",
                    optional("points", "点映射或点对象数组；坐标均为 [x,y]。", arrayOrObject()),
                    optional("segments", "线段数组；每条线段提供两个端点。", sequence()),
                    optional("vectors", "向量数组；使用 origin + delta，而不是两个端点。", sequence()),
                    optional("polygons", "多边形数组；顶点按给定顺序连接，不自动求凸包。", sequence()),
                    optional("circles", "圆数组；圆心坐标有限且 radius 必须为正。", sequence()),
                    optional("scan_lines", "扫描线数组。", sequence()),
                    optional("states", "几何对象状态映射。", mapping()),
                    optional("annotations", "几何对象到短注释的映射。", mapping())),

            legacy("draw_skip_list", "使用内置 legacy renderer 渲染跳表",
                    required("layers", "跳表各层数组，通常从底层到高层提供。", matrix()))
    );

    private KnowledgeVisualizationToolCatalog() {
    }

    static List<RendererSpec> renderers() {
        return RENDERERS;
    }

    static String inputSchema(RendererSpec renderer) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode properties = root.putObject("properties");
        ArrayNode required = root.putArray("required");
        for (ParameterSpec parameter : renderer.parameters()) {
            ObjectNode property = parameter.schema().deepCopy();
            property.put("description", parameter.description());
            properties.set(parameter.name(), property);
            if (parameter.required()) {
                required.add(parameter.name());
            }
        }
        root.put("additionalProperties", false);
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("生成可视化工具 JSON Schema 失败", e);
        }
    }

    private static RendererSpec modern(String renderer, String description, ParameterSpec... parameters) {
        return renderer(MODERN_ENGINE, renderer, description, MODERN_COMMON_PARAMETERS, parameters);
    }

    private static RendererSpec legacy(String renderer, String description, ParameterSpec... parameters) {
        return renderer(LEGACY_ENGINE, renderer, description, LEGACY_COMMON_PARAMETERS, parameters);
    }

    private static RendererSpec renderer(String engine,
                                         String renderer,
                                         String description,
                                         List<ParameterSpec> common,
                                         ParameterSpec... parameters) {
        List<ParameterSpec> all = new ArrayList<>(Arrays.asList(parameters));
        all.addAll(common);
        Set<String> duplicateNames = all.stream()
                .collect(Collectors.groupingBy(ParameterSpec::name, Collectors.counting()))
                .entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(java.util.Map.Entry::getKey)
                .collect(Collectors.toSet());
        if (!duplicateNames.isEmpty()) {
            throw new IllegalStateException(renderer + " 存在重复参数: " + duplicateNames);
        }
        return new RendererSpec(renderer, engine, description, List.copyOf(all));
    }

    private static ParameterSpec required(String name, String description, ObjectNode schema) {
        return new ParameterSpec(name, true, false, description, schema);
    }

    private static ParameterSpec requiredNullable(String name, String description, ObjectNode schema) {
        return new ParameterSpec(name, true, true, description, schema);
    }

    private static ParameterSpec optional(String name, String description, ObjectNode schema) {
        return new ParameterSpec(name, false, false, description, schema);
    }

    private static ParameterSpec optionalEnum(String name, String description, String... values) {
        ObjectNode schema = string();
        ArrayNode enumValues = schema.putArray("enum");
        Arrays.stream(values).forEach(enumValues::add);
        return optional(name, description, schema);
    }

    private static ParameterSpec optionalEnumArray(String name, String description, String... values) {
        ObjectNode item = string();
        ArrayNode enumValues = item.putArray("enum");
        Arrays.stream(values).forEach(enumValues::add);
        ObjectNode schema = array(item);
        schema.put("uniqueItems", true);
        return optional(name, description, schema);
    }

    private static ObjectNode string() {
        return typed("string");
    }

    private static ObjectNode bool() {
        return typed("boolean");
    }

    private static ObjectNode integer() {
        return integer(null, null);
    }

    private static ObjectNode integer(Integer minimum, Integer maximum) {
        ObjectNode schema = typed("integer");
        if (minimum != null) {
            schema.put("minimum", minimum);
        }
        if (maximum != null) {
            schema.put("maximum", maximum);
        }
        return schema;
    }

    private static ObjectNode number() {
        return typed("number");
    }

    private static ObjectNode mapping() {
        return typed("object");
    }

    private static ObjectNode nullableObject() {
        return oneOf(typed("object"), typed("null"));
    }

    private static ObjectNode sequence() {
        return array(anyValue());
    }

    private static ObjectNode stringSequence() {
        return array(string());
    }

    private static ObjectNode integerSequence() {
        return array(integer());
    }

    private static ObjectNode numberSequence() {
        return array(number());
    }

    private static ObjectNode matrix() {
        return array(sequence());
    }

    private static ObjectNode edges() {
        ObjectNode edge = array(anyValue());
        edge.put("minItems", 2);
        edge.put("maxItems", 3);
        return array(edge);
    }

    private static ObjectNode integerPair() {
        ObjectNode pair = array(integer());
        pair.put("minItems", 2);
        pair.put("maxItems", 2);
        return pair;
    }

    private static ObjectNode integerPairSequence() {
        return array(integerPair());
    }

    private static ObjectNode numericTriple() {
        ObjectNode triple = array(number());
        triple.put("minItems", 3);
        triple.put("maxItems", 3);
        return triple;
    }

    private static ObjectNode arrayOrObject() {
        return oneOf(sequence(), mapping());
    }

    private static ObjectNode integerOrObject() {
        return oneOf(integer(), mapping());
    }

    private static ObjectNode treeValue() {
        return oneOf(mapping(), sequence(), string(), number(), bool());
    }

    private static ObjectNode anyValue() {
        return MAPPER.createObjectNode();
    }

    private static ObjectNode array(ObjectNode itemSchema) {
        ObjectNode schema = typed("array");
        schema.set("items", itemSchema);
        return schema;
    }

    private static ObjectNode oneOf(ObjectNode... schemas) {
        ObjectNode schema = MAPPER.createObjectNode();
        ArrayNode oneOf = schema.putArray("oneOf");
        Arrays.stream(schemas).forEach(oneOf::add);
        return schema;
    }

    private static ObjectNode typed(String type) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", type);
        return schema;
    }

    record RendererSpec(String renderer,
                        String engine,
                        String description,
                        List<ParameterSpec> parameters) {

        String toolName() {
            return "kb_" + renderer;
        }

        Set<String> parameterNames() {
            return parameters.stream().map(ParameterSpec::name).collect(Collectors.toUnmodifiableSet());
        }
    }

    record ParameterSpec(String name,
                         boolean required,
                         boolean nullable,
                         String description,
                         ObjectNode schema) {
    }
}
