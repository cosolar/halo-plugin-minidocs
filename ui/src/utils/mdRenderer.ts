/**
 * Headless Markdown → HTML 渲染器。
 *
 * 复用与编辑器 MarkdownEditor.vue 完全一致的 cherry-markdown 渲染管线（mermaid /
 * KaTeX / prism 代码高亮均相同配置），把一段 Markdown 渲染成与“编辑保存”相同的 HTML，
 * 用于导入/一键发布时在没有编辑器界面的情况下补齐文档的 spec.content。
 *
 * 用法：renderMarkdownToHtml(markdown) -> Promise<string>
 *
 * 说明：所有重量级依赖（cherry / katex / mermaid / prism）都在首次真正渲染时才动态加载，
 * 避免把渲染引擎带进知识库列表页的首屏包。
 */

// ============ Prism 代码高亮（与 MarkdownEditor.vue 保持一致） ============
let loadedPrism: any = null;
const PRISM_LANG_WAVES: Array<Array<() => Promise<any>>> = [
  [
    () => import("prismjs/components/prism-clike.js"),
    () => import("prismjs/components/prism-markup.js"),
    () => import("prismjs/components/prism-markup-templating.js"),
    () => import("prismjs/components/prism-css.js"),
    () => import("prismjs/components/prism-python.js"),
    () => import("prismjs/components/prism-bash.js"),
    () => import("prismjs/components/prism-shell-session.js"),
    () => import("prismjs/components/prism-sql.js"),
    () => import("prismjs/components/prism-json.js"),
    () => import("prismjs/components/prism-yaml.js"),
    () => import("prismjs/components/prism-go.js"),
    () => import("prismjs/components/prism-rust.js"),
    () => import("prismjs/components/prism-csharp.js"),
    () => import("prismjs/components/prism-docker.js"),
    () => import("prismjs/components/prism-powershell.js"),
    () => import("prismjs/components/prism-diff.js"),
    () => import("prismjs/components/prism-ini.js"),
    () => import("prismjs/components/prism-toml.js"),
    () => import("prismjs/components/prism-http.js"),
  ],
  [
    () => import("prismjs/components/prism-javascript.js"),
    () => import("prismjs/components/prism-java.js"),
    () => import("prismjs/components/prism-c.js"),
    () => import("prismjs/components/prism-ruby.js"),
    () => import("prismjs/components/prism-swift.js"),
    () => import("prismjs/components/prism-kotlin.js"),
    () => import("prismjs/components/prism-groovy.js"),
    () => import("prismjs/components/prism-markdown.js"),
    () => import("prismjs/components/prism-php.js"),
  ],
  [
    () => import("prismjs/components/prism-typescript.js"),
    () => import("prismjs/components/prism-cpp.js"),
    () => import("prismjs/components/prism-scala.js"),
  ],
];

async function ensurePrismLoaded(): Promise<any> {
  if (loadedPrism) {
    return loadedPrism;
  }
  const mod = await import("prismjs");
  const prism = mod.default ?? mod;
  (window as unknown as Record<string, any>).Prism = prism;
  loadedPrism = prism;
  for (const wave of PRISM_LANG_WAVES) {
    await Promise.all(
      wave.map((loader) =>
        loader().catch((e) => {
          console.warn("prism 语言组件加载失败，该语言将按纯文本渲染", e);
        })
      )
    );
  }
  return prism;
}

function escapeHtml(str: string): string {
  return str
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

const LANG_ALIAS: Record<string, string> = {
  shell: "bash",
  sh: "bash",
  js: "javascript",
  py: "python",
  "c++": "cpp",
  cxx: "cpp",
  cs: "csharp",
  md: "markdown",
  yml: "yaml",
  html: "markup",
  htm: "markup",
  xml: "markup",
  svg: "markup",
  vue: "markup",
  ts: "typescript",
  docker: "docker",
  dockerfile: "docker",
  ps: "powershell",
  ps1: "powershell",
  powershell: "powershell",
};

function resolveGrammar(prism: any, lang: string) {
  const name = LANG_ALIAS[lang] || lang;
  return prism.languages[name] || prism.languages[lang];
}

function codeHighlight(code: string, lang: string): string {
  try {
    const prism = loadedPrism;
    if (!prism) {
      return escapeHtml(code);
    }
    const grammar = resolveGrammar(prism, lang);
    if (!grammar) {
      return escapeHtml(code);
    }
    const name = LANG_ALIAS[lang] || lang;
    return prism.highlight(code, grammar, name);
  } catch (e) {
    console.warn(`代码高亮失败（${lang}），按纯文本渲染`, e);
    return escapeHtml(code);
  }
}

// ============ Mermaid 插件注册（全局只注册一次，与编辑器共用守卫） ============
const PLUGIN_REGISTERED_KEY = "__minidocsCherryMermaidRegistered__";

async function ensureMermaidPluginRegistered(CherryCtor: any) {
  const g = window as unknown as Record<string, unknown>;
  if (g[PLUGIN_REGISTERED_KEY]) {
    return;
  }
  const mermaidMod = await import("cherry-markdown/dist/cherry-markdown.core.esm.js");
  const mermaidPlugin = (mermaidMod as unknown as { MermaidPlugin: unknown }).MermaidPlugin;
  const mermaidInstance = (await import("mermaid")).default;
  CherryCtor.usePlugin(mermaidPlugin as never, { mermaidAPI: mermaidInstance });
  g[PLUGIN_REGISTERED_KEY] = true;
}

// ============ 复用的隐藏 Cherry 实例 ============
let cherry: any = null;
let host: HTMLElement | null = null;
let initPromise: Promise<void> | null = null;

function nextFrame(): Promise<void> {
  return new Promise<void>((r) => requestAnimationFrame(() => r()));
}

function sleep(ms: number): Promise<void> {
  return new Promise<void>((r) => setTimeout(r, ms));
}

async function loadCherry(value: string) {
  await import("cherry-markdown/dist/cherry-markdown.css");
  const mod = await import("cherry-markdown/dist/cherry-markdown.core.esm.js");
  const Cherry = mod.default ?? mod;
  // 需在创建任何 Cherry 实例前注册 mermaid 插件
  await ensureMermaidPluginRegistered(Cherry);
  const katex = (await import("katex")).default ?? (await import("katex"));
  host = document.createElement("div");
  host.id = `minidocs-hc-${Math.random().toString(36).slice(2, 10)}`;
  host.style.cssText =
    "position:fixed;left:-9999px;top:0;width:860px;min-height:200px;" +
    "overflow:hidden;pointer-events:none;opacity:0;z-index:-1;";
  document.body.appendChild(host);
  return new Cherry({
    id: host.id,
    value,
    editor: { defaultModel: "previewOnly" },
    externals: { katex },
    engine: {
      global: { classicBr: true },
      syntax: {
        codeBlock: {
          mermaid: { showSourceToolbar: true },
          changeLang: false,
          editCode: false,
          expandCode: false,
          lineNumber: false,
          highlighter: codeHighlight,
        } as any,
        mathBlock: { engine: "katex", selfClosing: false },
        inlineMath: { engine: "katex" },
      },
    },
    toolbars: {
      toolbar: [],
      toolbarRight: [],
      toc: { defaultModel: "full", position: "absolute", updateLocationHash: false },
    },
  });
}

async function ensureCherry(md: string) {
  if (cherry) {
    return;
  }
  if (!initPromise) {
    initPromise = (async () => {
      await ensurePrismLoaded();
      // 需在创建任何 Cherry 实例前注册 mermaid 插件，且二者使用同一个 Cherry 构造器
      cherry = await loadCherry(md);
      await ensureMermaidPluginRegistered(cherry);
    })();
  }
  await initPromise;
}

/**
 * 等待 mermaid（异步 SVG 渲染）与基础渲染完成，确保 getHtml 时 SVG 已落板。
 */
async function waitRendered(md: string) {
  await nextFrame();
  await sleep(80);
  const hasMermaid = /```\s*mermaid/i.test(md);
  if (!hasMermaid || !host) {
    return;
  }
  const deadline = Date.now() + 3500;
  while (Date.now() < deadline) {
    const figs = host.querySelectorAll<HTMLElement>(
      'figure[data-type="mermaid"]'
    );
    let done = true;
    for (const f of Array.from(figs)) {
      const preview = f.querySelector(
        '.cherry-mermaid-source-toolbar-panel[data-mode="preview"]'
      );
      if (
        preview &&
        !preview.querySelector("svg") &&
        !preview.querySelector(".mermaid-error")
      ) {
        done = false;
        break;
      }
    }
    if (figs.length === 0 || done) {
      await nextFrame();
      return;
    }
    await sleep(50);
  }
}

/**
 * 将 Markdown 渲染为与编辑保存一致的 HTML。
 */
export async function renderMarkdownToHtml(markdown: string): Promise<string> {
  const md = markdown || "";
  await ensureCherry(md);
  if (!cherry) {
    return "";
  }
  cherry.setMarkdown(md);
  await waitRendered(md);
  const html = cherry.getHtml();
  return html || "";
}