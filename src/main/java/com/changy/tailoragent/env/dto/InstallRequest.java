package com.changy.tailoragent.env.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 运行时一键安装请求 —— {@code POST /api/env/install} 的请求体。
 */
@Data
@NoArgsConstructor
public class InstallRequest {

    /** 要安装的运行时 id："node" | "uv" */
    private String runtimeId;
}
