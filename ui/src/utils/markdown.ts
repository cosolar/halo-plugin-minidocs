import TurndownService from "turndown";
import { marked } from "marked";
import hljs from "highlight.js";
import { markedHighlight } from "marked-highlight";
import katex from "katex";

const turndownService = new TurndownService({
  headingStyle: "atx",
  codeBlockStyle: "fenced",
  bulletListMarker: "-",
  emDelimiter: "*",
});

export function htmlToMarkdown(html: string): string {
  return turndownService.turndown(html || "");
}

// ===== 代码高亮 =====
marked.use(
  markedHighlight({
    langPrefix: "hljs language-",
    highlight(code, lang) {
      if (lang && hljs.getLanguage(lang)) {
        return hljs.highlight(code, { language: lang }).value;
      }
      return hljs.highlightAuto(code).value;
    },
  })
);

// ===== Mermaid 图表 =====
const mermaidExtension = {
  name: "mermaid",
  level: "block" as const,
  start(src: string) {
    return src.indexOf("```mermaid");
  },
  tokenizer(src: string) {
    const match = src.match(/^```mermaid\s*\n([\s\S]*?)\n```/);
    if (match) {
      return {
        type: "mermaid",
        raw: match[0],
        text: match[1],
      };
    }
    return undefined;
  },
  renderer(token: { text: string }) {
    return `<div class="mermaid">${token.text}</div>`;
  },
};

marked.use({ extensions: [mermaidExtension] });

// ===== 公式（KaTeX）=====
const MATH_PLACEHOLDER = "\u0000MATH_";

function extractMath(markdown: string): { text: string; placeholders: string[] } {
  const placeholders: string[] = [];
  // 块级公式 $$...$$
  let text = markdown.replace(/\$\$([\s\S]+?)\$\$/g, (_, tex: string) => {
    const html = katex.renderToString(tex.trim(), {
      throwOnError: false,
      displayMode: true,
    });
    placeholders.push(html);
    return `${MATH_PLACEHOLDER}${placeholders.length - 1}\u0000`;
  });
  // 行内公式 $...$
  text = text.replace(/(?<!\$)\$([^$\n]+?)\$(?!\$)/g, (_, tex: string) => {
    const html = katex.renderToString(tex.trim(), {
      throwOnError: false,
      displayMode: false,
    });
    placeholders.push(html);
    return `${MATH_PLACEHOLDER}${placeholders.length - 1}\u0000`;
  });
  return { text, placeholders };
}

function restoreMath(html: string, placeholders: string[]): string {
  return html.replace(/\u0000MATH_(\d+)\u0000/g, (_, i: string) => placeholders[Number(i)]);
}

export function markdownToHtml(markdown: string): string {
  const { text, placeholders } = extractMath(markdown || "");
  const html = marked.parse(text, { async: false }) as string;
  return restoreMath(html, placeholders);
}
