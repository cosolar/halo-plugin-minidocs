package cn.minims.minidocs.setting;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 插件基础设置（对应 settings.yaml 的 basic 分组）。
 *
 * @author Cosolar
 */
public record BasicSetting(
    @Schema(description = "允许未登录用户阅读公开知识库") Boolean allowAnonymousRead,
    @Schema(description = "允许导出文档（Markdown）") Boolean allowDocExport) {

    public boolean anonymousReadEnabled() {
        return allowAnonymousRead == null || allowAnonymousRead;
    }

    public boolean docExportEnabled() {
        return allowDocExport == null || allowDocExport;
    }
}
