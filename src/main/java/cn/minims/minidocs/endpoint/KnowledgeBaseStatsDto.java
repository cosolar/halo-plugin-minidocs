package cn.minims.minidocs.endpoint;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

/**
 * 知识库聚合统计视图。
 */
@Data
@Builder
public class KnowledgeBaseStatsDto {

    @Schema(description = "知识库总数")
    private Integer total;

    @Schema(description = "公开知识库数")
    private Integer publicCount;

    @Schema(description = "私有知识库数")
    private Integer privateCount;

    @Schema(description = "文档总数")
    private Integer docCount;

    @Schema(description = "较上月新增知识库数")
    private Integer kbGrowth;

    @Schema(description = "较上月新增文档数")
    private Integer docGrowth;

    @Schema(description = "公开知识库占比")
    private String publicRatio;
}
