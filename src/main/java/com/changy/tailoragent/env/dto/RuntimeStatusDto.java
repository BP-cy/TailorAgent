package com.changy.tailoragent.env.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 本地运行时检测结果 —— {@code GET /api/env/runtimes} 的返回元素。
 * 供「环境配置」面板展示某运行时是否已安装及版本。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RuntimeStatusDto {

    /** 运行时 id："node" | "uv" */
    private String id;

    /** 展示名："Node.js" | "uv" */
    private String displayName;

    /** 是否已安装并可用 */
    private boolean installed;

    /** 版本号，未安装为 null */
    private String version;

    /** 实际检测所用命令，如 "node --version" */
    private String checkedCommand;
}
