package run.halo.plugin.minidocs;

import java.time.Instant;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.index.IndexSpecs;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;
import run.halo.plugin.minidocs.extension.KnowledgeBase;
import run.halo.plugin.minidocs.extension.KnowledgeBaseDoc;

/**
 * 知识库插件入口。
 *
 * <p>负责在插件启动/停止时注册或注销自定义扩展（数据模型）及其索引。
 *
 * @author Cosolar
 */
@Slf4j
@Component
public class KnowledgeBasePlugin extends BasePlugin {

    private final SchemeManager schemeManager;

    public KnowledgeBasePlugin(PluginContext pluginContext, SchemeManager schemeManager) {
        super(pluginContext);
        this.schemeManager = schemeManager;
    }

    @Override
    public void start() {
        // 注册自定义扩展（CRD 数据模型），注册后 Halo 会自动生成 CRUD REST API
        schemeManager.register(KnowledgeBase.class, indexSpecs -> {
            // 供列表排序与关键字搜索使用
            indexSpecs.add(IndexSpecs.<KnowledgeBase, Integer>single("spec.priority", Integer.class)
                .indexFunc(kb -> kb.getSpec() == null ? null : kb.getSpec().getPriority()));
            indexSpecs.add(IndexSpecs.<KnowledgeBase, String>single("spec.displayName", String.class)
                .indexFunc(kb -> kb.getSpec() == null ? null : kb.getSpec().getDisplayName()));
            indexSpecs.add(IndexSpecs.<KnowledgeBase, Boolean>single("spec.publicVisible",
                    Boolean.class)
                .indexFunc(kb -> kb.getSpec() == null ? null : kb.getSpec().getPublicVisible()));
        });
        schemeManager.register(KnowledgeBaseDoc.class, indexSpecs -> {
            // 文档查询索引：按知识库、父文档、状态、发布时间、标签、别名、标题过滤/排序
            indexSpecs.add(IndexSpecs.<KnowledgeBaseDoc, String>single("spec.knowledgeBaseName",
                    String.class)
                .nullable(false)
                .indexFunc(doc -> doc.getSpec() == null ? null
                    : doc.getSpec().getKnowledgeBaseName()));
            indexSpecs.add(IndexSpecs.<KnowledgeBaseDoc, String>single("spec.parentName",
                    String.class)
                .indexFunc(doc -> doc.getSpec() == null ? null : doc.getSpec().getParentName()));
            indexSpecs.add(IndexSpecs.<KnowledgeBaseDoc, String>single("spec.phase", String.class)
                .indexFunc(doc -> doc.getSpec() == null ? null : doc.getSpec().getPhase()));
            indexSpecs.add(IndexSpecs.<KnowledgeBaseDoc, Instant>single("spec.publishTime",
                    Instant.class)
                .indexFunc(doc -> doc.getSpec() == null ? null : doc.getSpec().getPublishTime()));
            indexSpecs.add(IndexSpecs.<KnowledgeBaseDoc, String>multi("spec.tags", String.class)
                .indexFunc(doc -> {
                    if (doc.getSpec() == null || doc.getSpec().getTags() == null) {
                        return Set.of();
                    }
                    return Set.copyOf(doc.getSpec().getTags());
                }));
            indexSpecs.add(IndexSpecs.<KnowledgeBaseDoc, String>single("spec.slug", String.class)
                .indexFunc(doc -> doc.getSpec() == null ? null : doc.getSpec().getSlug()));
            indexSpecs.add(IndexSpecs.<KnowledgeBaseDoc, String>single("spec.title", String.class)
                .indexFunc(doc -> doc.getSpec() == null ? null : doc.getSpec().getTitle()));
            indexSpecs.add(IndexSpecs.<KnowledgeBaseDoc, Integer>single("spec.priority",
                    Integer.class)
                .indexFunc(doc -> doc.getSpec() == null ? null : doc.getSpec().getPriority()));
        });
        log.info("Knowledge base plugin started");
    }

    @Override
    public void stop() {
        schemeManager.unregister(Scheme.buildFromType(KnowledgeBase.class));
        schemeManager.unregister(Scheme.buildFromType(KnowledgeBaseDoc.class));
        log.info("Knowledge base plugin stopped");
    }
}
