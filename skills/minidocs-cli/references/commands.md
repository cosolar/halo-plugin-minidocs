# 完整命令参考

> 所有命令均支持 `--profile <name>`（指定 profile，默认使用激活的 profile）与 `--json`（输出 JSON）。

## 目录

- [auth — 认证](#auth--认证)
- [kb / knowledgebase — 知识库](#kb--knowledgebase--知识库)
- [doc — 文档](#doc--文档)
- [completion — 补全](#completion--补全)
- [全局选项](#全局选项)

## auth — 认证

| 命令 | 说明 |
| --- | --- |
| `auth login` | 登录并保存 profile。参数：`--url`（必填）、`--auth-type bearer\|basic`、`--username`、`--password`、`--token`、`--profile <name>` |
| `auth current` | 显示当前激活的 profile |
| `auth profile list` | 列出所有已保存的 profile |
| `auth profile get <name>` | 显示指定 profile 详情 |
| `auth profile use <name>` | 切换激活的 profile |
| `auth profile delete <name>` | 删除 profile（连同钥匙串凭据），需确认或 `--force` |
| `auth profile doctor` | 校验所有已保存的 profile 与凭据是否可用 |

## kb / knowledgebase — 知识库

`kb` 是 `knowledgebase` 的别名，两者等价。

| 命令 | 说明 |
| --- | --- |
| `kb list` | 分页列出知识库。选项：`--keyword`、`--public-visible <true\|false>`、`--sort-by <updateTime\|name\|priority\|createTime\|docCount>`、`--page`、`--size` |
| `kb get <name>` | 显示知识库详情。`<name>` 支持 metadata.name 或 spec.slug |
| `kb create` | 创建知识库。`--display-name` 必填 |
| `kb update <name>` | 更新知识库。`<name>` 支持 name 或 slug |
| `kb delete <name>` | 删除知识库（级联删除其文档）。需确认或 `--force` |
| `kb stats` | 显示聚合统计（总数、公开/私有数、文档数、增长量、公开占比） |
| `kb export` | 导出知识库为 ZIP。`--names <name1,name2>` 必填（支持 name 或 slug），`--output <file>` 指定路径 |
| `kb import` | 从 ZIP 导入。`--file` 必填，`--strategy overwrite\|skip`（默认 overwrite，需确认或 `--force`） |
| `kb import-preview` | 预览 ZIP 中的可导入项（显示是否已存在），不写数据。`--file` 必填 |

创建/更新知识库可选字段：

| 选项 | 说明 |
| --- | --- |
| `--display-name` | 显示名称（create 必填） |
| `--slug` | URL 友好的唯一别名 |
| `--description` | 描述 |
| `--logo` / `--cover` | Logo / 封面图 URL |
| `--public <boolean>` | 是否公开可见 |
| `--members <users>` | 逗号分隔的成员用户名 |
| `--tags <tags>` | 逗号分隔的标签 |
| `--priority <number>` | 排序优先级（越小越靠前） |
| `--share-enabled <boolean>` | 是否启用外部分享链接 |
| `--share-password` | 分享访问密码 |
| `--share-expires-at <datetime>` | 分享过期时间（ISO-8601） |

## doc — 文档

| 命令 | 说明 |
| --- | --- |
| `doc list <kb>` | 分页列出文档。选项：`--keyword`、`--phase <draft\|published>`、`--page`、`--size` |
| `doc tree <kb>` | 以缩进树展示文档层级（`●` 已发布，`○` 草稿） |
| `doc get <kb> <doc>` | 显示文档详情。`<doc>` 为文档 metadata.name |
| `doc create <kb>` | 创建文档。`--title` 必填 |
| `doc update <kb> <doc>` | 更新文档 |
| `doc delete <kb> <doc>` | 删除文档（级联删除子树）。需确认或 `--force` |
| `doc publish <kb> <doc>` | 发布文档 |
| `doc unpublish <kb> <doc>` | 取消发布 |
| `doc move <kb> <doc>` | 移动/排序。`--parent <docName>`、`--priority <number>`、`--before <docName>`、`--after <docName>` |
| `doc export <kb> <doc>` | 导出单篇文档为 Markdown。`--output <file>` 指定路径 |
| `doc import <kb>` | 批量导入 Markdown 文件为文档。`--files a.md,b.md` 必填，可选 `--parent <docName>` |

创建/更新文档可选字段：

| 选项 | 说明 |
| --- | --- |
| `--title` | 标题（create 必填） |
| `--slug` | URL 友好的唯一别名 |
| `--raw <markdown>` | 内联 Markdown 内容 |
| `--file <file>` | 从 Markdown 文件读取内容（与 `--raw` 同时给出时优先） |
| `--summary` | 摘要 |
| `--cover <url>` | 封面图 URL |
| `--parent <docName>` | 父文档 metadata.name |
| `--priority <number>` | 排序优先级 |
| `--tags <tags>` | 逗号分隔的标签 |
| `--phase <phase>` | 发布状态：`draft`（默认）或 `published` |

## completion — 补全

```bash
eval "$(minidocs completion bash)"   # bash
eval "$(minidocs completion zsh)"    # zsh
```

## 全局选项

| 选项 | 说明 |
| --- | --- |
| `-h, --help` | 显示帮助 |
| `-v, --version` | 显示版本号 |
| `--profile <name>` | 指定使用的 profile（默认激活的 profile） |
| `--json` | 输出 JSON 格式结果 |
