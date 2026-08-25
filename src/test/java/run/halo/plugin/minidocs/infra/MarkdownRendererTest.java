package run.halo.plugin.minidocs.infra;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MarkdownRendererTest {

    private final MarkdownRenderer renderer = new MarkdownRenderer();

    @Test
    void renderBasicMarkdown() {
        String html = renderer.render("# 标题\n\n**粗体** 和 *斜体*\n\n- 列表项");
        assertThat(html).contains("<h1>标题</h1>");
        assertThat(html).contains("<strong>粗体</strong>");
        assertThat(html).contains("<em>斜体</em>");
        assertThat(html).contains("<li>列表项</li>");
    }

    @Test
    void renderTable() {
        String html = renderer.render("| 参数 | 说明 |\n| --- | --- |\n| a | b |");
        assertThat(html).contains("<table>");
        assertThat(html).contains("<th>参数</th>");
        assertThat(html).contains("<td>b</td>");
    }

    @Test
    void renderMermaidBlock() {
        String html = renderer.render("```mermaid\ngraph TD\n  A --> B\n```");
        assertThat(html).contains("<div class=\"mermaid\">");
        assertThat(html).contains("graph TD");
        assertThat(html).contains("A --&gt; B");
    }

    @Test
    void renderCodeBlockWithLanguage() {
        String html = renderer.render("```java\nint a = 1;\n```");
        assertThat(html).contains("language-java");
        assertThat(html).contains("int a = 1;");
    }

    @Test
    void renderEmpty() {
        assertThat(renderer.render(null)).isEmpty();
        assertThat(renderer.render("")).isEmpty();
        assertThat(renderer.render("   ")).isEmpty();
    }
}
