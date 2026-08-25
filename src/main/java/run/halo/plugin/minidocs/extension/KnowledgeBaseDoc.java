package run.halo.plugin.minidocs.extension;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 知识库文档扩展（数据模型）。
 *
 * <p>对应 REST 路径：/apis/minidocs.halo.run/v1alpha1/knowledgebasedocs
 * 字段以骨架为准，后续迭代按需求文档扩展。
 *
 * @author YourName
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(
    group = "minidocs.halo.run",
    version = "v1alpha1",
    kind = "KnowledgeBaseDoc",
    plural = "knowledgebasedocs",
    singular = "knowledgebasedoc"
)
public class KnowledgeBaseDoc extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;

    @Data
    @Schema(name = "KnowledgeBaseDocSpec")
    public static class Spec {

        @Schema(description = "所属知识库名称", requiredMode = Schema.RequiredMode.REQUIRED)
        private String knowledgeBaseName;

        @Schema(description = "文档标题", requiredMode = Schema.RequiredMode.REQUIRED,
            maxLength = 200)
        private String title;

        @Schema(description = "文档别名，用于生成可读 URL", maxLength = 200)
        private String slug;

        @Schema(description = "文档内容（Markdown）")
        private String content;

        @Schema(description = "文档内容（服务端渲染后的 HTML，供主题端直接输出）")
        private String contentHtml;

        @Schema(description = "父文档名称，用于构建文档树，空表示顶级文档")
        private String parentName;

        @Schema(description = "排序权重，越小越靠前")
        private Integer priority;

        @Schema(description = "标签列表")
        private List<String> tags;

        @Schema(description = "文档状态：draft / published / archived",
            defaultValue = "draft")
        private String phase = "draft";

        @Schema(description = "最后发布时间")
        private Instant publishTime;
    }
}
