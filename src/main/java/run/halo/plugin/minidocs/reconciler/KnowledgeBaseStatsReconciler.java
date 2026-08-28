package run.halo.plugin.minidocs.reconciler;

import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;
import run.halo.plugin.minidocs.extension.KnowledgeBase;
import run.halo.plugin.minidocs.extension.KnowledgeBaseDoc;

import static run.halo.app.extension.index.query.Queries.and;
import static run.halo.app.extension.index.query.Queries.equal;

/**
 * 知识库统计 Reconciler。
 *
 * <p>监听 {@link KnowledgeBaseDoc} 变更，刷新所属知识库的
 * {@code status.docCount} 与 {@code status.lastPublishTime}。
 * <p>注意：文档删除事件发生时资源已不可见，无法得知父级，因此删除路径由
 * {@code KnowledgeBaseService.refreshStats} 在删除时同步刷新。
 *
 * @author Cosolar
 */
@Component
@RequiredArgsConstructor
public class KnowledgeBaseStatsReconciler implements Reconciler<Reconciler.Request> {

    private final ExtensionClient client;

    @Override
    public Result reconcile(Request request) {
        client.fetch(KnowledgeBaseDoc.class, request.name())
            .ifPresent(doc -> refreshStats(doc.getSpec().getKnowledgeBaseName()));
        return Result.doNotRetry();
    }

    private void refreshStats(String kbName) {
        var allDocs = ListOptions.builder()
            .fieldQuery(equal("spec.knowledgeBaseName", kbName))
            .build();
        var count = client.countBy(KnowledgeBaseDoc.class, allDocs);

        var publishedDocs = ListOptions.builder()
            .fieldQuery(and(
                equal("spec.knowledgeBaseName", kbName),
                equal("spec.phase", "published")))
            .build();
        var published = client.listAll(KnowledgeBaseDoc.class, publishedDocs,
            Sort.by(Sort.Order.desc("spec.publishTime")));
        var lastPublishTime = published.isEmpty() ? null
            : published.getFirst().getSpec().getPublishTime();

        client.fetch(KnowledgeBase.class, kbName).ifPresent(kb -> {
            var newCount = Math.toIntExact(count);
            var status = kb.getStatus() == null ? new KnowledgeBase.Status() : kb.getStatus();
            if (!Objects.equals(status.getDocCount(), newCount)
                || !Objects.equals(status.getLastPublishTime(), lastPublishTime)) {
                status.setDocCount(newCount);
                status.setLastPublishTime(lastPublishTime);
                kb.setStatus(status);
                client.update(kb);
            }
        });
    }

    @Override
    public Controller setupWith(ControllerBuilder builder) {
        return builder
            .extension(new KnowledgeBaseDoc())
            .syncAllOnStart(true)
            .build();
    }
}
