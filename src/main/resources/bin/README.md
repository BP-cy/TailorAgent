# 内置二进制目录

本目录的文件会随 fat jar 一起打包,运行时由各 Provisioner 解压到
`%LOCALAPPDATA%\TailorAgent\bin\`(可写目录,套路同 JCEF natives)。

## rg.exe（ripgrep，Grep 工具依赖）

请把 Windows 版 ripgrep 可执行文件放在这里并命名为 **`rg.exe`**:

```
src/main/resources/bin/rg.exe
```

- 下载:https://github.com/BurntSushi/ripgrep/releases （取 `ripgrep-*-x86_64-pc-windows-msvc.zip` 里的 `rg.exe`）
- 许可证:MIT / The Unlicense，可自由随应用分发
- 由 `RipgrepProvisioner` 在启动时解压;**缺失时 Grep 自动回退到纯 Java 实现**,不会报错,只是大仓库下较慢。

> 该 `rg.exe`（约 5MB）按团队约定直接提交进仓库。
