package run.halo.plugin.minidocs.infra;

import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 服务端 Markdown 渲染：生成 contentHtml 供主题端直接输出。
 *
 * <p>基础语法（GFM 表格/删除线/任务列表/自动链接）由 flexmark 渲染；
 * mermaid 代码块转为 {@code <div class="mermaid">} 容器，由主题端前端 JS 渲染；
 * 行内/块级公式保留原始 {@code $...$} / {@code $$...$$} 标记，由主题端 KaTeX 处理。
 *
 * @author Cosolar
 */
@Component
public class MarkdownRenderer {

    private static final Pattern MERMAID_BLOCK = Pattern.compile(
        "<pre><code class=\"language-mermaid\">([\\s\\S]*?)</code></pre>");

    private final Parser parser;
    private final HtmlRenderer renderer;

    public MarkdownRenderer() {
        var options = new MutableDataSet()
            .set(Parser.EXTENSIONS, List.of(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                TaskListExtension.create(),
                AutolinkExtension.create()
            ));
        parser = Parser.builder(options).build();
        renderer = HtmlRenderer.builder(options).build();
    }

    public String render(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        String html = renderer.render(parser.parse(markdown));
        // 将 mermaid 代码块替换为容器，内容已由 flexmark 转义，主题端 JS 读取 textContent 渲染
        Matcher matcher = MERMAID_BLOCK.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String mermaidHtml = "<div class=\"mermaid\">" + matcher.group(1) + "</div>";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(mermaidHtml));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
