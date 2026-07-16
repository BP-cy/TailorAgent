package com.changy.tailoragent.env.controller;

import com.changy.tailoragent.common.response.ApiResponse;
import com.changy.tailoragent.env.EnvInstallService;
import com.changy.tailoragent.env.RuntimeDetectionService;
import com.changy.tailoragent.env.dto.InstallRequest;
import com.changy.tailoragent.env.dto.RuntimeStatusDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 环境配置 API —— 本地运行时（Node.js / uv）的检测与一键安装。
 */
@RestController
@RequestMapping("/api/env")
public class EnvController {

    private final RuntimeDetectionService detection;
    private final EnvInstallService install;

    public EnvController(RuntimeDetectionService detection, EnvInstallService install) {
        this.detection = detection;
        this.install = install;
    }

    /** 检测本地运行时安装情况 */
    @GetMapping("/runtimes")
    public ApiResponse<List<RuntimeStatusDto>> runtimes() {
        return ApiResponse.success(detection.detectAll());
    }

    /** 通过 winget 一键安装指定运行时 */
    @PostMapping("/install")
    public ApiResponse<Void> install(@RequestBody InstallRequest req) {
        EnvInstallService.InstallOutcome out = install.install(req.getRuntimeId());
        return out.launched()
                ? ApiResponse.success(out.message())
                : ApiResponse.error(out.message());
    }
}
