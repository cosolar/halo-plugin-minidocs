import TurndownService from "turndown";

const turndownService = new TurndownService({
  headingStyle: "atx",
  codeBlockStyle: "fenced",
  bulletListMarker: "-",
  emDelimiter: "*",
});

/**
 * 将 HTML 转回 Markdown，用于兼容历史以 HTML 保存的旧数据。
 * 当前文档的渲染/解析统一由 cherry-markdown 承担，前端不再维护自有的渲染管线。
 */
export function htmlToMarkdown(html: string): string {
  return turndownService.turndown(html || "");
}