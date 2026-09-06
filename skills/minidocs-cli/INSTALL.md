# minidocs-cli 技能安装指南

本技能遵循 [Agent Skills 开放标准](https://agentskills.io)（SKILL.md 格式），可被 Claude Code、OpenAI Codex、TRAE、OpenCode、OpenClaw、GitHub Copilot 等 20+ 支持 Agent Skills 的智能体工具直接安装使用。

## 快速安装

### 方式一：直接复制（通用，适用于所有工具）

将本技能目录复制到目标工具的 skills 目录：

```bash
# Linux / macOS
mkdir -p ~/.claude/skills
cp -r skills/minidocs-cli ~/.claude/skills/

# Windows PowerShell
New-Item -ItemType Directory -Path "$HOME\.claude\skills" -Force
Copy-Item -Path skills\minidocs-cli -Destination "$HOME\.claude\skills\" -Recurse
```

### 方式二：软链接（跟随仓库更新，推荐开发环境）

```bash
# Linux / macOS
ln -s "$(pwd)/skills/minidocs-cli" ~/.claude/skills/minidocs-cli
```

## 各智能体安装位置

| 智能体 | 用户级（全局） | 项目级 |
| --- | --- | --- |
| Claude Code | `~/.claude/skills/` | `.claude/skills/` |
| OpenAI Codex | `~/.agents/skills/` | `.agents/skills/` |
| GitHub Copilot | `~/.agents/skills/` | `.agents/skills/` |
| OpenCode | `~/.config/opencode/skills/` | `.opencode/skills/` |
| TRAE | TRAE 技能市场/插件目录 | `.trae/skills/` |
| OpenClaw | 按 OpenClaw 配置的 skills 目录 | 同左 |

选择任一位置复制 `minidocs-cli` 整个目录即可（注意保留目录名 `minidocs-cli` 与 SKILL.md 中的 `name` 字段一致）。

示例：

```bash
# Claude Code 项目级
cp -r skills/minidocs-cli .claude/skills/

# Codex 用户级
cp -r skills/minidocs-cli ~/.agents/skills/

# OpenCode 用户级
cp -r skills/minidocs-cli ~/.config/opencode/skills/

# TRAE 项目级（Windows）
Copy-Item -Path skills\minidocs-cli -Destination .trae\skills\ -Recurse
```

## 校验

安装前可先校验技能格式：

```bash
# 使用 skilllint（Agent Skills 官方校验器）
uvx skilllint@latest check skills/minidocs-cli
```

## 使用前置条件

技能生效后，智能体还需满足：

1. **安装 CLI**：`npm install -g minidocs-cli`（要求 Node.js >= 22）
2. **配置认证**：智能体需要向用户询问并执行 `minidocs auth login`（需要 Halo 站点地址和 Personal Access Token / Basic 凭据）

首次在智能体中询问"帮我管理 MiniDocs 知识库"时，智能体会自动加载本技能并按 `SKILL.md` 的流程执行。
