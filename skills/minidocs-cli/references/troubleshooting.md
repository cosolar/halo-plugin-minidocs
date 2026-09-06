# 常见错误排查

## 目录

- [认证相关](#认证相关)
- [资源定位相关](#资源定位相关)
- [导入导出相关](#导入导出相关)
- [安装与环境相关](#安装与环境相关)
- [通用建议](#通用建议)

## 认证相关

### `Received an HTML page instead of JSON from ...`

**原因**：请求未通过认证，被 Halo 重定向到登录页（HTML），CLI 期望 JSON 响应。

**解决**：
```bash
# 用 Bearer PAT 重新登录（Halo 控制台 API 需要个人访问令牌）
minidocs auth login \
  --profile <name> \
  --url https://your-site \
  --auth-type bearer \
  --token <personal-access-token>
minidocs auth profile use <name>
minidocs auth profile doctor   # 校验凭据可用性
```

**注意**：Basic 认证仅适用于本地/受信任站点；远程站点必须使用 Bearer PAT。PAT 在 Halo 控制台「个人资料 → 个人访问令牌」中创建。

### `MiniDocs profile "xxx" does not exist.`

**原因**：指定的 profile 未保存。先 `minidocs auth profile list` 查看已保存的 profile，或用 `auth login` 新建。

### 切换站点后仍访问旧站点

**原因**：激活的 profile 未切换。

**解决**：`minidocs auth profile use <name>` 切换后重试。

## 资源定位相关

### `Knowledge base "xxx" not found. It must be either a metadata name or an exact slug.`

**原因**：`kb get/update/delete/export` 中的参数既不是 metadata.name，也不是完全匹配的 slug。

**解决**：`minidocs kb list --json` 查看可用的 name 与 slug 后重试。

### 文档操作报 404

**原因**：`doc` 命令的 `<doc>` 参数必须是文档的 **metadata.name**（UUID 形式），不支持 slug 定位。

**解决**：用 `minidocs doc list <kb> --json` 或 `minidocs doc tree <kb>` 获取文档 name 后重试。

## 导入导出相关

### `Importing with the overwrite strategy requires confirmation in interactive mode or use --force.`

**原因**：`kb import` 默认 overwrite 策略，非交互环境要求显式确认。

**解决**：
```bash
# 跳过重名知识库（安全）
minidocs kb import --file kbs.zip --strategy skip
# 或确认覆盖
minidocs kb import --file kbs.zip --force
```

### 导入报「版本冲突」（Failed to update versioned entity...）

**原因**：目标知识库在导入过程中被并发修改（metadata.version 变化），覆盖操作被拒绝并回滚，原数据保留。

**解决**：这是保护机制，属预期行为。先 `minidocs kb get <name>` 确认现状，如确需覆盖再重试 `--force`。

### 导出/写入文件报 ENOENT

**原因**：`--output` 指向的目录不存在。

**解决**：先创建目标目录再执行。

## 安装与环境相关

### `minidocs: command not found`

**原因**：CLI 未安装或 PATH 未包含 npm 全局 bin 目录。

**解决**：
```bash
npm install -g minidocs-cli
# 验证
minidocs --version
```

### 安装后提示 `Unknown command "--version"`

**原因**：安装了过旧版本（2026.9.8 之前的 bug，已在 2026.9.9 修复）。

**解决**：升级到最新版：`npm install -g minidocs-cli@latest`。

### 阿里云镜像导致版本找不到（ETARGET）

**原因**：npm registry 配置为镜像源时，镜像尚未同步最新版本。

**解决**：
```bash
npm install -g minidocs-cli@latest --registry=https://registry.npmjs.org/ --prefer-online
```

### `Node.js >= 22` 要求

**原因**：CLI 使用现代 JS 特性，旧版 Node 不支持。

**解决**：升级 Node 到 22+（`node --version` 检查）。

## 通用建议

1. **先看帮助**：任何命令加 `--help` 查看完整参数。
2. **多用 `--json`**：脚本化解析或排查数据问题时，`--json` 输出最可靠。
3. **危险操作确认**：删除、覆盖导入默认需确认；非交互环境必须显式 `--force`，注意数据不可恢复（知识库删除会级联删除其全部文档）。
4. **凭据安全**：token/密码保存在系统钥匙串，不要将 profile 配置或 `--token` 参数写入版本库。
