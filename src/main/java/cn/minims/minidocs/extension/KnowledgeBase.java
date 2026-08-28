package cn.minims.minidocs.extension;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 知识库扩展（数据模型）。
 *
 * <p>对应 REST 路径：/apis/minidocs.halo.run/v1alpha1/knowledgebases
 *
 * @author Cosolar
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(
    group = "minidocs.halo.run",
    version = "v1alpha1",
    kind = "KnowledgeBase",
    plural = "knowledgebases",
    singular = "knowledgebase"
)
public class KnowledgeBase extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;

    @Schema(description = "状态信息（由 Reconciler/服务维护）")
    private Status status;

    @Data
    @Schema(name = "KnowledgeBaseSpec")
    public static class Spec {

        @Schema(description = "知识库名称", requiredMode = Schema.RequiredMode.REQUIRED,
            maxLength = 100)
        private String displayName;

        @Schema(description = "知识库链接别名（URL 友好标识，留空时系统自动生成），用于前台 /docs/view/{slug} 等路由")
        private String slug;

        @Schema(description = "知识库描述")
        private String description;

        @Schema(description = "是否公开可见", defaultValue = "false")
        private Boolean publicVisible = false;

        @Schema(description = "成员用户名列表（私有知识库可访问者）")
        private List<String> members;

        @Schema(description = "知识库标签")
        private List<String> tags;

        @Schema(description = "排序权重，越小越靠前")
        private Integer priority;

        @Schema(description = "创建人（创建人用户名）")
        private String creatorName;

        @Schema(description = "创建时间（创建时由系统自动写入）")
        private Instant creationTime;

        @Schema(description = "知识库图标地址")
        private String logo;

        @Schema(description = "封面图片地址")
        private String cover;

        @Schema(description = "最后一次更新时间")
        private Instant updateTime;

        @Schema(description = "访问量（每次打开阅读页 +1）", defaultValue = "0")
        private Long accessCount = 0L;

        @Schema(description = "点赞数")
        private Long likeCount = 0L;

        @Schema(description = "已点赞用户名列表（用于点赞去重与取消点赞）")
        private List<String> likedUsers;

        @Schema(description = "是否开启外链分享（null 表示未提交，更新时沿用旧值）")
        private Boolean shareEnabled;

        @Schema(description = "分享外链标识（随机 token，用于 /docs/share/{shareToken}）")
        private String shareToken;

        @Schema(description = "分享访问密码（为空表示无密码访问）")
        private String sharePassword;

        @Schema(description = "分享有效期截止时间（为空表示永久有效）")
        private Instant shareExpiresAt;
    }

    @Data
    @Schema(name = "KnowledgeBaseStatus")
    public static class Status {

        @Schema(description = "文档总数")
        private Integer docCount;

        @Schema(description = "最近发布时间")
        private Instant lastPublishTime;

        @Schema(description = "较上月新增知识库数")
        private Integer kbGrowth;

        @Schema(description = "较上月新增文档数")
        private Integer docGrowth;
    }
}
