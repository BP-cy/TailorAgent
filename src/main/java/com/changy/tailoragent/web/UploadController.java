package com.changy.tailoragent.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * 本地图片上传：把文件写入 {@link AppPaths#mediaDir()}，返回可直接用于 &lt;img src&gt; 的相对
 * URL（{@code /media/xxx}）。
 *
 * <p>之所以不在前端用 base64 内联：知识库要对正文做切片与向量化，base64 大字符串会污染
 * chunk / 产生垃圾向量。改为文件存储 + URL 引用，正文保持干净，图片可走独立检索管线。
 */
@RestController
@RequestMapping("/api")
public class UploadController {

    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("msg", "文件为空"));
        }
        String type = file.getContentType();
        if (type == null || !type.startsWith("image/")) {
            return ResponseEntity.badRequest().body(Map.of("msg", "仅支持图片文件"));
        }

        String name = UUID.randomUUID().toString().replace("-", "") + "." + extensionOf(file);
        Path dir = AppPaths.mediaDir();
        Files.createDirectories(dir);
        file.transferTo(dir.resolve(name).toAbsolutePath());

        return ResponseEntity.ok(Map.of("url", "/media/" + name));
    }

    /** 推断文件后缀：优先用 content-type，其次用原文件名，最后兜底 png。 */
    private static String extensionOf(MultipartFile file) {
        String type = file.getContentType();
        if (type != null && type.startsWith("image/")) {
            String sub = type.substring("image/".length());
            if (sub.equals("jpeg")) {
                return "jpg";
            }
            if (sub.matches("[a-zA-Z0-9]+")) {
                return sub;
            }
        }
        String original = file.getOriginalFilename();
        if (original != null && original.contains(".")) {
            return original.substring(original.lastIndexOf('.') + 1);
        }
        return "png";
    }
}
