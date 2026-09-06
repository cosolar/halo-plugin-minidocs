---
name: minidocs-cli
description: Manage MiniDocs knowledge bases and documents on Halo using the minidocs-cli command-line tool. Use when the user asks to create, list, update, delete, publish, import or export knowledge bases and documents, or work with Halo MiniDocs content.
license: MIT
compatibility: Requires Node.js >= 22 and minidocs-cli (install with `npm install -g minidocs-cli`)
metadata:
  author: Cosolar
  version: "1.0.0"
---

# MiniDocs CLI 知识库管理

`minidocs-cli` 是基于 [Halo](https://halo.run) Console REST API（`console.api.minidocs.halo.run/v1alpha1`）的官方命令行工具，用于管理 **MiniDocs 插件**的知识库（KnowledgeBase）与文档（KnowledgeBaseDoc）。

**何时使用本技能**：当用户要求管理 MiniDocs 知识库、编写/发布/整理文档、导入导出知识内容，或任何与 Halo MiniDocs 内容相关的操作时。

---

## 前置条件

```bash
# 要求 Node.js >= 22
npm install -g minidocs-cli
minidocs --version   # 验证安装，输出 minidocs/2026.x.x
```

## 首次使用：认证（必做）

CLI 通过 Halo **个人访问令牌（PAT）** 或 Basic 认证访问站点。必须先登录并保存 profile：

```bash
# Bearer PAT 方式（推荐，用于远程站点）
minidocs auth login \
  --profile my-site \
  --url https://example.halo.run \
  --auth-type bearer \
  --token <personal-access-token>

# 本地开发站点 Basic 方式
minidocs auth login \
  --profile local \
  --url http://127.0.0.1:8090 \
  --auth-type basic \
  --username admin \
  --password <password>
```

非交互模式（脚本/CI）必须显式传全部参数；交互模式直接运行 `minidocs auth login` 会逐项询问。

常用认证命令：

```bash
minidocs auth current                 # 查看当前激活的 profile
minidocs auth profile list            # 列出所有 profile
minidocs auth profile use my-site     # 切换激活 profile
minidocs auth profile doctor          # 校验 profile 与凭据是否可用
minidocs auth profile delete local    # 删除 profile（需确认）
```

## 核心工作流

### 1. 知识库操作（命令别名：`kb` = `knowledgebase`）

```bash
minidocs kb list                                   # 列出知识库
minidocs kb create --display-name "我的知识库" --slug my-kb --public true
minidocs kb get my-kb                              # 详情（name 或 slug 均可定位）
minidocs kb update my-kb --description "新描述" --tags "AI,文档"
minidocs kb stats                                  # 聚合统计
minidocs kb delete my-kb --force                   # 删除（级联删文档，默认需确认）
```

> `kb get/update/delete/export` 的资源定位同时支持 **metadata.name** 与 **spec.slug** 两种形式。

### 2. 文档操作

```bash
minidocs doc list my-kb                            # 分页列出文档
minidocs doc tree my-kb                            # 文档层级树（● 已发布 / ○ 草稿）
minidocs doc create my-kb --title "标题" --file page.md    # 从 Markdown 文件创建
minidocs doc create my-kb --title "标题" --raw "# 内联内容" # 或内联 Markdown
minidocs doc update my-kb <docName> --title "新标题"
minidocs doc publish my-kb <docName>               # 发布
minidocs doc unpublish my-kb <docName>             # 取消发布
minidocs doc move my-kb <docName> --parent <parentDocName>  # 移动到子节点
minidocs doc move my-kb <docName> --priority 10    # 或调整排序
minidocs doc delete my-kb <docName> --force        # 删除（级联删子树，默认需确认）
```

### 3. 导入导出

```bash
minidocs kb export --names my-kb,other-kb --output kbs.zip   # 导出为 ZIP
minidocs kb import-preview --file kbs.zip                    # 预览，不写数据
minidocs kb import --file kbs.zip --strategy skip            # 导入（跳过重名）
minidocs kb import --file kbs.zip --force                    # 导入（覆盖，需 --force）

minidocs doc export my-kb <docName> --output page.md         # 单篇导出 Markdown
minidocs doc import my-kb --files a.md,b.md                  # 批量导入（可选 --parent）
```

### 4. 结构化输出与脚本化

```bash
# 所有命令支持 --json，便于脚本解析
minidocs kb list --json
minidocs doc list my-kb --json
minidocs doc get my-kb <docName> --json
```

---

## 关键行为与注意事项

- **确认机制**：`kb delete`、`doc delete`、`kb import`（覆盖策略）默认要求二次确认；非交互环境必须加 `--force` 或使用 `--strategy skip`。
- **`--file` 优先于 `--raw`**：两者同时给出时以 `--file` 内容为准。
- **`doc import` 标题规则**：以文件名（去掉 `.md` 扩展名）作为文档标题，自动剥离 UTF-8 BOM。
- **文档定位**：`doc` 命令中 `<doc>` 参数必须是文档的 `metadata.name`（可用 `doc list --json` 获取），不支持 slug。
- **多站点**：凭据存储在系统钥匙串，配置文件不保存明文 token/密码；切换站点用 `auth profile use <name>`。
- **Shell 补全**：`eval "$(minidocs completion bash)"`（bash）或 `eval "$(minidocs completion zsh)"`（zsh）。

## 错误排查

常见错误及解决办法见 [references/troubleshooting.md](references/troubleshooting.md)；完整命令与选项参考见 [references/commands.md](references/commands.md)。
