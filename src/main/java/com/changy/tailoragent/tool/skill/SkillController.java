package com.changy.tailoragent.tool.skill;

import com.changy.tailoragent.common.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import javax.swing.*;
import java.awt.EventQueue;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Skill 管理 API —— 设置面板「我的 SKILL」用。
 * <p>
 * 列表读 {@link SkillRegistry};导入有两条路径:
 * <ol>
 *   <li><b>拖拽</b>:前端读取文件夹内文件(base64)上传 → {@code POST /import};</li>
 *   <li><b>选择文件夹</b>:后端弹原生 {@link JFileChooser} 取本地路径整树复制 → {@code POST /import-dir}。</li>
 * </ol>
 * 导入/删除后 {@link SkillRegistry} 已即时重扫,下一轮对话的「可用 Skills」清单自动反映变化,无需重启。
 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private static final Logger log = LoggerFactory.getLogger(SkillController.class);

    private final SkillRegistry registry;

    public SkillController(SkillRegistry registry) {
        this.registry = registry;
    }

    /** 已安装 Skill 列表 */
    @GetMapping
    public ApiResponse<List<SkillInfo>> list() {
        return ApiResponse.success(registry.list());
    }

    /** 拖拽导入:前端上传文件夹内全部文件(相对路径 + base64),写入并返回最新列表 */
    @PostMapping("/import")
    public ApiResponse<List<SkillInfo>> importFiles(@RequestBody ImportRequest req) {
        if (req == null || req.files() == null || req.files().isEmpty()) {
            return ApiResponse.error("未读取到任何文件");
        }
        try {
            List<SkillRegistry.ImportFile> files = new ArrayList<>();
            for (ImportFile f : req.files()) {
                byte[] content = (f.contentBase64() == null || f.contentBase64().isEmpty())
                        ? new byte[0]
                        : Base64.getDecoder().decode(f.contentBase64());
                files.add(new SkillRegistry.ImportFile(f.path(), content));
            }
            registry.importFromFiles(req.folderName(), files);
            log.info("已导入 Skill 文件夹: {}({} 个文件)", req.folderName(), files.size());
            return ApiResponse.success(registry.list());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            log.error("导入 Skill 失败", e);
            return ApiResponse.error("导入失败: " + e.getMessage());
        }
    }

    /** 原生文件夹选择导入(桌面环境):弹 JFileChooser → 整树复制 → 返回最新列表 */
    @PostMapping("/import-dir")
    public ApiResponse<List<SkillInfo>> importDir() {
        try {
            String[] result = new String[1];
            if (EventQueue.isDispatchThread()) {
                result[0] = openDirChooser();
            } else {
                SwingUtilities.invokeAndWait(() -> result[0] = openDirChooser());
            }
            String dir = result[0];
            if (dir == null || dir.isBlank()) {
                return ApiResponse.success(registry.list()); // 用户取消
            }
            registry.importFromDir(Path.of(dir));
            log.info("已从文件夹导入 Skill: {}", dir);
            return ApiResponse.success(registry.list());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ApiResponse.success(registry.list());
        } catch (InvocationTargetException e) {
            log.error("打开文件夹选择对话框失败", e.getCause());
            return ApiResponse.error("打开文件夹选择失败: " + e.getCause().getMessage());
        } catch (Exception e) {
            log.error("导入 Skill 失败", e);
            return ApiResponse.error("导入失败: " + e.getMessage());
        }
    }

    /** 删除某 Skill,返回最新列表 */
    @DeleteMapping("/{name}")
    public ApiResponse<List<SkillInfo>> delete(@PathVariable String name) {
        try {
            registry.delete(name);
            log.info("已删除 Skill: {}", name);
            return ApiResponse.success(registry.list());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /** 原生文件夹选择对话框(套路同 ConfigController#openDirChooser) */
    private String openDirChooser() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            log.debug("设置系统 Look-and-Feel 失败,回退默认外观: {}", e.getMessage());
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("选择 Skill 文件夹(须含 SKILL.md)");
        int result = chooser.showOpenDialog(null);
        if (result == JFileChooser.APPROVE_OPTION) {
            java.io.File selected = chooser.getSelectedFile();
            return selected != null ? selected.getAbsolutePath() : null;
        }
        return null;
    }

    /** 拖拽导入请求体:文件夹名 + 文件列表 */
    public record ImportRequest(String folderName, List<ImportFile> files) {
    }

    /** 单个文件:相对路径 + base64 内容 */
    public record ImportFile(String path, String contentBase64) {
    }
}
