/* MiniDocs 知识库阅读页 / 分享页共享脚本（doc.html / doc_share.html）。
   页面配置由模板内联注入 window.MD_CONF：
   - apiBase: 文档/统计接口前缀（阅读页 /knowledgebases/{slug}，分享页 /share/{token}）
   - isCatalog: 是否目录模式
   - kbName: 知识库名称
   - share: 是否分享页
   注意：页面 HTML 体积受线上链路 ~120KB 响应限制，脚本必须外置，勿内联回模板。

   文档切换策略（核心）：点击目录树节点 -> fetch 拉文档内容 -> 更新 #md-prose / 底部信息 /
   右侧大纲 -> history.pushState 更新 URL，全程不重新加载页面，因此左侧目录树的展开状态、
   滚动位置、搜索词都原样保留。JSON 接口异常时降级抓取本页 HTML 片段；彻底失败时只提示
   错误、保持当前页，绝不触发 location 导航。

   代码块高亮注意事项：halo-plugin-shiki 是前端 shiki-code Web Component，只在整页加载与
   pjax:complete 事件时扫描代码块。无刷新替换正文后必须派发该事件，否则新插入的代码块会
   一直停留在模糊的未渲染占位态（参考 halo-theme-cosolar/src/js/doc.ts 的 SPA 官方钩子）。 */
(function () {
  'use strict';

  var CONF = window.MD_CONF || {};
  var IS_CATALOG = !!CONF.isCatalog;
  var API_BASE = CONF.apiBase || '';
  var KB_NAME = CONF.kbName || '';

  // ===== DOM 引用（脚本 defer，DOM 已解析完成） =====
  var mainEl = document.getElementById('md-main');
  var prose = document.getElementById('md-prose');
  var emptyEl = document.getElementById('md-empty');
  var tocList = document.getElementById('md-toc-list');
  var tocRoot = document.getElementById('md-toc');
  var treeRoot = document.querySelector('.md-tree');
  var search = document.getElementById('md-doc-search');
  var likeBtn = document.getElementById('md-like');
  var likeCountEl = document.getElementById('md-like-count');
  var viewCountEl = document.getElementById('md-view-count');

  // 供诊断：确认本脚本已接管点击（若页面仍在整页刷新，先查此值）
  window.__MD_SPA_READY = true;

  // ===== 小工具 =====
  function toast(msg) {
    var t = document.getElementById('md-toast');
    if (!t) return;
    t.textContent = msg;
    t.classList.add('show');
    clearTimeout(t._timer);
    t._timer = setTimeout(function () { t.classList.remove('show'); }, 1800);
  }
  function cssEscape(s) {
    return String(s).replace(/([^a-zA-Z0-9_-])/g, '\\$1');
  }
  function findLi(slug) {
    return document.querySelector('.md-tree li[data-slug="' + cssEscape(slug) + '"]');
  }
  function tsOf(id) {
    var el = document.getElementById(id);
    return el ? (el.getAttribute('data-ts') || '') : '';
  }
  function elText(id) {
    var el = document.getElementById(id);
    return el ? (el.textContent || '') : '';
  }
  function urlFor(slug) {
    // 保留当前路径与其它查询参数（分享链路可能带额外参数），只替换 docSlug，并清掉旧锚点
    var u = new URL(location.href);
    u.searchParams.set('docSlug', slug);
    u.hash = '';
    return u.pathname + u.search;
  }
  function defaultSlug() {
    var a = document.querySelector('.md-tree a.md-tree-link');
    return a ? (a.dataset.slug || a.dataset.name || '') : '';
  }
  function formatDateTime(iso) {
    if (!iso) return '';
    var d = new Date(iso);
    if (isNaN(d.getTime())) return iso;
    function p(n) { return (n < 10 ? '0' : '') + n; }
    return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate())
      + ' ' + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
  }
  function formatTimes() {
    var els = document.querySelectorAll('.md-ts[data-ts]');
    Array.prototype.forEach.call(els, function (el) {
      el.textContent = formatDateTime(el.getAttribute('data-ts'));
    });
  }

  // 密码验证成功进入正文后，从地址栏清除密码参数（避免留存在历史记录）
  (function () {
    try {
      var u = new URL(location.href);
      if (u.searchParams.has('password')) {
        u.searchParams.delete('password');
        history.replaceState(history.state, '',
          u.pathname + u.search + u.hash);
      }
    } catch (e) {}
  })();

  // ===== 目录树折叠状态 =====
  // 无刷新切换本身就能保留展开状态；额外持久化到 sessionStorage，
  // 让用户手动 F5 刷新后也能恢复，且按知识库/分享链接隔离。
  var FOLD_KEY = 'minidocs:fold:' + (API_BASE || location.pathname);

  function refreshTree() {
    var lis = document.querySelectorAll('.md-tree li[data-ancestors]');
    var bySlug = {};
    Array.prototype.forEach.call(lis, function (li) {
      if (li.dataset.slug) bySlug[li.dataset.slug] = li;
    });
    Array.prototype.forEach.call(lis, function (li) {
      var parts = (li.dataset.ancestors || '').split(' ').filter(Boolean);
      var hidden = false;
      // data-ancestors 末位是自身，自身折叠只隐藏子行，不隐藏自己
      for (var i = 0; i < parts.length - 1; i++) {
        var p = bySlug[parts[i]];
        if (p && p.classList.contains('md-fold')) { hidden = true; break; }
      }
      li.classList.toggle('md-hide', hidden);
    });
  }

  function saveFold() {
    try {
      var folded = [];
      Array.prototype.forEach.call(
        document.querySelectorAll('.md-tree li.md-has-child.md-fold'),
        function (li) { folded.push(li.dataset.slug); });
      sessionStorage.setItem(FOLD_KEY, JSON.stringify(folded));
    } catch (e) {}
  }
  // 返回是否存在用户保存过的状态
  function restoreFold() {
    try {
      var raw = sessionStorage.getItem(FOLD_KEY);
      if (!raw) return false;
      var set = JSON.parse(raw) || [];
      Array.prototype.forEach.call(
        document.querySelectorAll('.md-tree li.md-has-child'),
        function (li) { li.classList.toggle('md-fold', set.indexOf(li.dataset.slug) !== -1); });
      return true;
    } catch (e) { return false; }
  }
  function setFold(li, folded) {
    if (!li) return;
    li.classList.toggle('md-fold', !!folded);
    refreshTree();
    saveFold();
  }
  // 展开目标行的所有祖先目录，保证切换后该行可见；不折叠任何其它目录
  function expandAncestorsOf(li, silent) {
    if (!li) return;
    var parts = (li.dataset.ancestors || '').split(' ').filter(Boolean);
    parts.pop();
    if (!parts.length) return;
    Array.prototype.forEach.call(document.querySelectorAll('.md-tree li[data-slug]'),
      function (n) {
        if (parts.indexOf(n.dataset.slug) !== -1) n.classList.remove('md-fold');
      });
    if (!silent) saveFold();
  }

  // ===== 知识库访问量 / 点赞（匿名可赞，localStorage 去重，一次性点赞） =====
  function likeApi(path) { return API_BASE + path; }
  function likeKey() { return 'minidocs:like:' + likeApi(''); }
  function locallyLiked() {
    try { return localStorage.getItem(likeKey()) === '1'; } catch (e) { return false; }
  }
  function setLocallyLiked() {
    try { localStorage.setItem(likeKey(), '1'); } catch (e) {}
  }
  function markLiked() {
    if (!likeBtn) return;
    likeBtn.classList.add('is-liked');
    likeBtn.classList.remove('popping');
    void likeBtn.offsetWidth;
    likeBtn.classList.add('popping');
  }
  function refreshLikeUi(data) {
    if (!data) return;
    if (typeof data.likeCount === 'number' && likeCountEl) {
      likeCountEl.textContent = data.likeCount;
    }
    var liked = !!data.liked || locallyLiked();
    if (likeBtn) likeBtn.classList.toggle('is-liked', liked);
  }
  function initLike() {
    if (!API_BASE) return;
    fetch(likeApi('/stats'), { credentials: 'include' })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (data) {
        if (!data) return;
        refreshLikeUi(data);
        if (viewCountEl && typeof data.accessCount === 'number') {
          viewCountEl.textContent = data.accessCount;
        }
      })
      .catch(function (e) { console.warn('[minidocs] like stats failed', e); });

    if (!likeBtn) return;
    likeBtn.addEventListener('click', function () {
      if (locallyLiked()) { toast('该知识库已点赞过了'); markLiked(); return; }
      fetch(likeApi('/like'), { method: 'POST', credentials: 'include' })
        .then(function (r) {
          if (!r.ok) throw new Error('like http ' + r.status);
          return r.json();
        })
        .then(function (data) {
          setLocallyLiked();
          if (likeCountEl && data && typeof data.likeCount === 'number') {
            likeCountEl.textContent = data.likeCount;
          }
          markLiked();
          toast('点赞成功');
        })
        .catch(function (e) {
          console.error('[minidocs] like failed', e);
          toast('点赞失败，请稍后重试');
        });
    });
  }

  // 回到顶部（滚动中间正文容器）
  window.mdScrollTop = function () {
    if (mainEl) mainEl.scrollTo({ top: 0, behavior: 'smooth' });
  };

  // ===== 搜索文档（按标题过滤树节点） =====
  if (search) {
    search.addEventListener('input', function () {
      var q = search.value.trim().toLowerCase();
      var links = document.querySelectorAll('.md-tree-link');
      var visible = 0;
      Array.prototype.forEach.call(links, function (a) {
        var hay = (a.dataset.title || '').toLowerCase();
        var show = hay.indexOf(q) !== -1;
        a.parentElement.style.display = show ? '' : 'none';
        if (show) visible++;
      });
      var empty = document.querySelector('.md-tree-empty');
      if (empty) empty.style.display = q && !visible ? '' : 'none';
    });
  }

  // ===== 文档无刷新切换 =====
  var currentSlug = (function () {
    var a = document.querySelector('.md-tree-link.active');
    var s = a ? (a.dataset.slug || a.dataset.name || '') : '';
    if (!s && mainEl) s = mainEl.getAttribute('data-doc-slug') || '';
    return s;
  })();
  var docCache = Object.create(null);
  var reqSeq = 0;
  var abortCtrl = null;

  // 首屏内容直接入缓存，避免点回当前文档时重复请求
  (function cacheFirstScreen() {
    if (!currentSlug || !prose || docCache[currentSlug]) return;
    // 首屏没有正文时不要缓存空内容，否则点回来会一直空白而不重新请求
    if (emptyEl && emptyEl.style.display !== 'none') return;
    var a = document.querySelector('.md-tree-link.active');
    docCache[currentSlug] = {
      title: a ? (a.getAttribute('data-title') || '') : '',
      content: prose.innerHTML,
      author: elText('md-author'),
      creationTime: tsOf('md-ts-create'),
      updateTime: tsOf('md-ts-update')
    };
  })();

  function normalizeDoc(data) {
    var spec = (data && data.spec) || {};
    var meta = (data && data.metadata) || {};
    return {
      title: (data && data.title) || spec.title || '',
      content: data && data.content != null ? data.content
        : (spec.content != null ? spec.content : ''),
      author: (data && data.author) || spec.author || '',
      creationTime: (data && data.creationTime) || meta.creationTimestamp || '',
      updateTime: (data && data.updateTime) || spec.updateTime || ''
    };
  }

  // 主数据源：公开 JSON 接口（阅读页返回完整 doc 对象，分享页返回精简结构）。
  // 内容替换后会派发 pjax:complete，由 shiki 的 Web Component 重新高亮代码块。
  function fetchDocJson(slug, signal) {
    if (!API_BASE) return Promise.reject(new Error('no api base'));
    return fetch(API_BASE + '/docs/' + encodeURIComponent(slug), {
      credentials: 'include',
      headers: { 'Accept': 'application/json' },
      signal: signal
    }).then(function (r) {
      var ct = r.headers.get('Content-Type') || '';
      if (!r.ok || ct.indexOf('json') === -1) {
        throw new Error('doc api http ' + r.status + ' (' + ct + ')');
      }
      return r.json();
    }).then(function (data) {
      var hasContent = !!data && (
        Object.prototype.hasOwnProperty.call(data, 'content')
        || (data.spec && Object.prototype.hasOwnProperty.call(data.spec, 'content')));
      if (!hasContent) throw new Error('unexpected doc payload');
      return normalizeDoc(data);
    });
  }

  // 降级数据源：抓取本页 HTML，用 DOMParser 取出正文片段。
  // 仍是 XHR（浏览器不刷新、目录树展开状态不丢），可覆盖 JSON 接口鉴权 / 路径异常等场景。
  function fetchDocHtml(slug, signal) {
    return fetch(urlFor(slug), {
      credentials: 'include',
      headers: { 'Accept': 'text/html,application/xhtml+xml' },
      signal: signal
    }).then(function (r) {
      if (!r.ok) throw new Error('doc page http ' + r.status);
      return r.text();
    }).then(function (html) {
      var dom = new DOMParser().parseFromString(html, 'text/html');
      var p = dom.getElementById('md-prose');
      if (!p) throw new Error('doc page has no prose (gate or error page)');
      var a = dom.querySelector('.md-tree-link.active');
      var cTs = dom.getElementById('md-ts-create');
      var uTs = dom.getElementById('md-ts-update');
      return {
        title: a ? (a.getAttribute('data-title') || '') : '',
        content: p.innerHTML,
        author: dom.getElementById('md-author')
          ? dom.getElementById('md-author').textContent : '',
        creationTime: cTs ? (cTs.getAttribute('data-ts') || '') : '',
        updateTime: uTs ? (uTs.getAttribute('data-ts') || '') : ''
      };
    });
  }

  function setLoading(on) {
    if (prose) prose.classList.toggle('md-loading', !!on);
  }
  function syncUrl(slug, opts) {
    var same = false;
    try {
      same = new URL(location.href).searchParams.get('docSlug') === slug;
    } catch (e) {}
    var url = urlFor(slug);
    if (opts && opts.replace) {
      history.replaceState({ mdSlug: slug }, '', url);
    } else if (!(opts && opts.push === false) && !same) {
      history.pushState({ mdSlug: slug }, '', url);
    }
  }

  // 通知 halo-plugin-shiki（shiki-code Web Component）重新扫描并高亮新插入的代码块。
  // 它是前端异步组件，只在整页加载与 pjax:complete 时扫描；无刷新替换 #md-prose 后
  // 若不派发，代码块会一直停留在「深色底 + 模糊文字」的未渲染占位态。
  // 这是该插件的 SPA 官方钩子，参考 halo-theme-cosolar/src/js/doc.ts。
  function notifyContentRendered() {
    try {
      window.dispatchEvent(new Event('pjax:complete'));
    } catch (e) {
      console.warn('[minidocs] pjax:complete dispatch failed', e);
    }
  }

  function switchDoc(slug, opts) {
    opts = opts || {};
    if (!slug) return;
    if (slug === currentSlug && !opts.force) {
      syncUrl(slug, opts);
      return;
    }

    var seq = ++reqSeq;
    if (abortCtrl) { try { abortCtrl.abort(); } catch (e) {} }
    abortCtrl = (typeof AbortController !== 'undefined') ? new AbortController() : null;
    var signal = abortCtrl ? abortCtrl.signal : undefined;

    setLoading(true);
    // 优先 JSON 接口（轻量），失败时降级抓取本页 HTML 片段
    var task = docCache[slug]
      ? Promise.resolve(docCache[slug])
      : fetchDocJson(slug, signal).catch(function (err) {
          // 快速连点导致旧请求被取消时，直接丢弃，不要再发降级请求
          if (err && err.name === 'AbortError') throw err;
          console.warn('[minidocs] 文档 JSON 接口不可用，降级抓取本页 HTML：', err);
          return fetchDocHtml(slug, signal);
        });

    return task.then(function (data) {
      if (seq !== reqSeq) return; // 已被后续点击取代，丢弃过期响应
      docCache[slug] = data;
      currentSlug = slug;
      applyDoc(data, slug);
      syncUrl(slug, opts);
      // 与主题一致：URL 更新后再通知 shiki 重新高亮新插入的代码块
      notifyContentRendered();
    }).catch(function (err) {
      if (seq !== reqSeq || (err && err.name === 'AbortError')) return;
      console.error('[minidocs] 文档加载失败', err);
      // 关键：绝不 location.search / location.replace，页面与目录展开状态原样保留
      toast('文档加载失败，请点击重试');
    }).then(function () {
      if (seq === reqSeq) setLoading(false);
    });
  }

  // 将文档数据应用到页面（只改正文/选中态/底栏/大纲，不重置目录树折叠状态）
  function applyDoc(d, slug) {
    if (prose) {
      prose.innerHTML = d.content || '';
      if (emptyEl) emptyEl.style.display = d.content ? 'none' : '';
    }

    var prev = document.querySelector('.md-tree-link.active');
    if (prev) prev.classList.remove('active');
    var li = findLi(slug);
    if (li) {
      var a = li.querySelector('a.md-tree-link');
      if (a) a.classList.add('active');
      expandAncestorsOf(li);
    }
    refreshTree();

    try { enhanceProse(); } catch (e) {
      console.warn('[minidocs] enhanceProse failed', e);
    }
    if (mainEl) mainEl.scrollTo({ top: 0, behavior: 'auto' });

    updateMeta(d);
    renderRoute();
    if (d.title) {
      document.title = d.title + ' - ' + (KB_NAME || '知识库');
    }
  }

  function setTime(wrapId, elId, iso) {
    var wrap = document.getElementById(wrapId);
    var el = document.getElementById(elId);
    if (!wrap || !el) return;
    if (!iso) {
      wrap.style.display = 'none';
      el.removeAttribute('data-ts');
      el.textContent = '';
      return;
    }
    el.setAttribute('data-ts', iso);
    el.textContent = formatDateTime(iso);
    wrap.style.display = '';
  }
  function updateMeta(d) {
    fillBottomMeta();
    var aw = document.getElementById('md-author-wrap');
    var ae = document.getElementById('md-author');
    if (aw && ae) {
      ae.textContent = d.author || '';
      aw.style.display = d.author ? '' : 'none';
    }
    setTime('md-mi-create', 'md-ts-create', d.creationTime);
    setTime('md-mi-update', 'md-ts-update', d.updateTime);
  }

  // 浏览器前进 / 后退：按 URL 中的 docSlug 切换（不新增历史记录）
  window.addEventListener('popstate', function () {
    var slug = '';
    try { slug = new URL(location.href).searchParams.get('docSlug') || ''; } catch (e) {}
    if (!slug) slug = defaultSlug();
    if (slug) switchDoc(slug, { push: false });
  });

  // ===== 目录树点击：事件委托（捕获阶段） =====
  if (treeRoot) {
    // 规范化链接地址：保证中键 / Ctrl+点击 / JS 未加载时仍能打开对应文档
    Array.prototype.forEach.call(treeRoot.querySelectorAll('a.md-tree-link'),
      function (a) {
        var slug = a.dataset.slug || a.dataset.name;
        if (slug) a.setAttribute('href', urlFor(slug));
      });

    treeRoot.addEventListener('click', function (e) {
      if (e.defaultPrevented || e.button !== 0
          || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
      var target = e.target;
      var caret = target && target.closest ? target.closest('.md-tree-caret') : null;
      if (caret) {
        // 箭头只负责折叠 / 展开，不切换文档
        e.preventDefault();
        e.stopPropagation();
        var caretLi = caret.closest('li');
        if (caretLi) setFold(caretLi, !caretLi.classList.contains('md-fold'));
        return;
      }
      var link = target && target.closest ? target.closest('a.md-tree-link') : null;
      if (!link) return;
      e.preventDefault();
      var li = link.closest('li');
      var slug = link.dataset.slug || link.dataset.name || '';
      if (!slug) return;
      // 点击目录行：展开（而不是折叠 / 隐藏）其下子项，同时加载该节点的正文
      if (li && li.classList.contains('md-has-child')
          && li.classList.contains('md-fold')) {
        setFold(li, false);
      }
      switchDoc(slug, { push: true });
    }, true);
  }

  // ===== 初始化目录树状态 =====
  var hadFoldState = restoreFold();
  var activeA0 = document.querySelector('.md-tree-link.active');
  if (!hadFoldState && activeA0) {
    // 首次进入：展开当前文档的所有祖先，避免它藏在默认折叠的目录里
    expandAncestorsOf(activeA0.closest('li'), true);
  }
  refreshTree();

  // 目录模式且没有选中文档时：直接用 AJAX 打开第一篇，不整页跳转
  if (IS_CATALOG && !activeA0) {
    var firstLeaf = document.querySelector('.md-tree li.md-leaf a.md-tree-link')
      || document.querySelector('.md-tree a.md-tree-link');
    if (firstLeaf) {
      var firstSlug = firstLeaf.dataset.slug || firstLeaf.dataset.name || '';
      if (firstSlug) switchDoc(firstSlug, { replace: true, force: true });
    }
  }

  // 「展开 / 收起全部」
  var foldAllBtn = document.getElementById('md-foldall');
  if (foldAllBtn) {
    foldAllBtn.addEventListener('click', function () {
      var dirs = document.querySelectorAll('.md-tree li.md-has-child');
      if (!dirs.length) return;
      var anyOpen = Array.prototype.some.call(dirs, function (li) {
        return !li.classList.contains('md-fold');
      });
      Array.prototype.forEach.call(dirs, function (li) {
        li.classList.toggle('md-fold', anyOpen);
      });
      refreshTree();
      saveFold();
    });
  }

  // ===== 正文增强：代码块复制、标题锚点、右侧大纲、scroll spy =====
  var spyHeads = [];
  var spyTocItems = [];
  var spyBound = false;

  function bindSpy() {
    if (spyBound || !mainEl) return;
    spyBound = true;
    mainEl.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('scroll', onScroll, { passive: true });
  }
  function onScroll() {
    if (!spyHeads.length) return;
    var cur = null;
    var topBias = mainEl ? mainEl.getBoundingClientRect().top + 12 : 80;
    Array.prototype.forEach.call(spyHeads, function (h) {
      if (h.getBoundingClientRect().top <= topBias) cur = h;
    });
    var activeId = '#' + (cur ? cur.id : '');
    Array.prototype.forEach.call(spyTocItems, function (item) {
      var a = item.querySelector('a');
      item.classList.toggle('active', !!a && a.getAttribute('href') === activeId);
    });
    if (!tocList) return;
    var activeItem = tocList.querySelector('.md-toc-item.active');
    if (activeItem) {
      var liEl = activeItem.parentElement;
      while (liEl) {
        var pLi = liEl.parentElement ? liEl.parentElement.closest('li') : null;
        if (!pLi) break;
        pLi.classList.remove('toc-fold');
        liEl = pLi;
      }
    }
  }

  function fillBottomMeta() {
    var wEl = document.getElementById('md-words');
    var lEl = document.getElementById('md-lines');
    if (!wEl && !lEl) return;
    var text = prose ? (prose.innerText || '') : '';
    if (wEl) wEl.textContent = text.replace(/\s+/g, '').length;
    if (lEl) lEl.textContent = text ? text.split(/\n/).length : 0;
  }

  function enhanceProse() {
    if (!prose) return;

    // 代码块：语言标签 + 复制按钮
    Array.prototype.forEach.call(prose.querySelectorAll('pre'), function (pre) {
      if (pre.closest('.cherry-mermaid-source-toolbar-panel')) return;
      pre.style.position = 'relative';
      var code = pre.querySelector('code');
      if (code) {
        var langEl = code.className.match(/language-([\w-]+)/);
        if (langEl) {
          var s = document.createElement('span');
          s.className = 'md-lang';
          s.textContent = langEl[1];
          pre.appendChild(s);
        }
      }
      var btn = document.createElement('button');
      btn.className = 'md-copy';
      btn.type = 'button';
      btn.textContent = '复制';
      btn.addEventListener('click', function (e) {
        e.preventDefault();
        copyText(pre.innerText.replace(/\n{3,}/g, '\n\n'));
        toast('已复制');
      });
      pre.appendChild(btn);
    });

    // 大纲：切换文档后先清空旧条目，避免多篇文章的标题累积
    if (!tocList) return;
    tocList.innerHTML = '';
    if (tocRoot) tocRoot.style.display = '';

    var cache = {};
    function slideId(text) {
      var s = String(text || '').trim().toLowerCase()
        .replace(/\s+/g, '-').replace(/[^\w\u4e00-\u9fa5-]/g, '');
      if (cache[s] != null) { cache[s] += 1; return s + '-' + cache[s]; }
      cache[s] = 0; return s;
    }
    var heads = prose.querySelectorAll('h1,h2,h3,h4,h5,h6');
    var tree = [];
    var stack = [];
    Array.prototype.forEach.call(heads, function (h) {
      h.id = h.id || slideId(h.textContent);
      var lvl = parseInt(h.tagName.charAt(1), 10);
      var item = { lvl: lvl, id: h.id, text: h.textContent };
      while (stack.length && stack[stack.length - 1].lvl >= lvl) stack.pop();
      if (stack.length) {
        (stack[stack.length - 1].children = stack[stack.length - 1].children || [])
          .push(item);
      } else { tree.push(item); }
      stack.push(item);
    });

    (function build(items, parentList) {
      items.forEach(function (it) {
        var li = document.createElement('li');
        var hasKids = it.children && it.children.length;
        var item = document.createElement('div');
        item.className = 'md-toc-item';
        if (hasKids) {
          var caret = document.createElement('span');
          caret.className = 'md-toc-caret';
          caret.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" '
            + 'stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">'
            + '<polyline points="6 9 12 15 18 9"></polyline></svg>';
          item.appendChild(caret);
        } else {
          var spacer = document.createElement('span');
          spacer.className = 'md-toc-spacer';
          item.appendChild(spacer);
        }
        var link = document.createElement('a');
        link.href = '#' + it.id;
        link.textContent = it.text;
        link.addEventListener('click', function (e) {
          e.preventDefault();
          var t = document.getElementById(it.id);
          if (t) t.scrollIntoView({ behavior: 'smooth', block: 'start' });
          history.replaceState(history.state, '',
            location.pathname + location.search + '#' + it.id);
        });
        item.appendChild(link);
        item.addEventListener('click', function (e) {
          if (e.target.closest && e.target.closest('a')) return;
          if (li.querySelector(':scope > ul')) li.classList.toggle('toc-fold');
        });
        li.appendChild(item);
        parentList.appendChild(li);
        if (hasKids) {
          if (it.lvl >= 2) li.classList.add('toc-fold');
          var childUl = document.createElement('ul');
          li.appendChild(childUl);
          build(it.children, childUl);
        }
      });
    })(tree, tocList);

    if (tocList.children.length === 0 && tocRoot) tocRoot.style.display = 'none';
    spyHeads = Array.prototype.slice.call(heads);
    spyTocItems = Array.prototype.slice.call(tocList.querySelectorAll('.md-toc-item'));
    onScroll();
  }

  // 底部文档路由路径：知识库名 / 祖先链 / 文档名（扁平树没有嵌套 li，用 data-ancestors）
  function renderRoute() {
    var el = document.getElementById('md-route');
    if (!el) return;
    var activeA = document.querySelector('.md-tree-link.active');
    var parts = [];
    if (activeA) {
      var li = activeA.closest('li');
      var chain = li ? (li.dataset.ancestors || '').split(' ').filter(Boolean) : [];
      Array.prototype.forEach.call(chain, function (s) {
        var n = document.querySelector(
          '.md-tree li[data-slug="' + cssEscape(s) + '"] > a.md-tree-link');
        if (n) parts.push(n.getAttribute('data-title') || '');
      });
    }
    var root = el.getAttribute('data-root') || '';
    var all = [root].concat(parts).filter(function (s) { return s; });
    el.textContent = '';
    all.forEach(function (s, i) {
      if (i > 0) {
        var sep = document.createElement('span');
        sep.className = 'sep';
        sep.textContent = '/';
        el.appendChild(sep);
      }
      var span = document.createElement('span');
      span.textContent = s;
      el.appendChild(span);
    });
    el.setAttribute('title', all.join(' / '));
  }

  // cherry-markdown 的 mermaid 源码/图 tab（事件委托绑在 #md-prose 上，innerHTML 替换后仍有效）
  function enableCherryMermaidTabs() {
    if (!prose) return;
    prose.addEventListener('click', function (e) {
      var tab = e.target && e.target.closest
        ? e.target.closest('.cherry-mermaid-source-toolbar-tab[data-mode]') : null;
      if (!tab) return;
      var fig = tab.closest('figure');
      if (!fig) return;
      var mode = tab.getAttribute('data-mode');
      fig.querySelectorAll('.cherry-mermaid-source-toolbar-tab').forEach(function (t) {
        t.classList.toggle('active', t === tab);
      });
      fig.querySelectorAll('.cherry-mermaid-source-toolbar-panel').forEach(function (p) {
        var on = p.getAttribute('data-mode') === mode;
        p.classList.toggle('active', on);
        p.style.display = on ? 'block' : 'none';
      });
    });
  }

  // ===== 左右侧边栏拖拽拉宽 =====
  function initResizer(handle, target, dir, min, max) {
    handle.addEventListener('mousedown', function (e) {
      e.preventDefault();
      handle.classList.add('dragging');
      document.body.style.cursor = 'col-resize';
      document.body.style.userSelect = 'none';
      var startX = e.clientX;
      var startW = target.offsetWidth;
      function move(ev) {
        var delta = dir === 'left' ? (ev.clientX - startX) : (startX - ev.clientX);
        var w = startW + delta;
        if (w < min) w = min;
        if (w > max) w = max;
        target.style.width = w + 'px';
      }
      function up() {
        document.removeEventListener('mousemove', move);
        document.removeEventListener('mouseup', up);
        handle.classList.remove('dragging');
        document.body.style.cursor = '';
        document.body.style.userSelect = '';
      }
      document.addEventListener('mousemove', move);
      document.addEventListener('mouseup', up);
    });
  }
  var sbHandle = document.querySelector('.resize-handle.sb-resize');
  var sbEl = document.getElementById('md-sb');
  if (sbHandle && sbEl) initResizer(sbHandle, sbEl, 'left', 200, 520);
  var tocHandle = document.querySelector('.resize-handle.toc-resize');
  if (tocHandle && tocRoot) initResizer(tocHandle, tocRoot, 'right', 170, 480);

  // ===== 复制 / 书签 =====
  function copyText(text) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      return navigator.clipboard.writeText(text).catch(function () { fallbackCopy(text); });
    }
    fallbackCopy(text);
  }
  function fallbackCopy(text) {
    var ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    try { document.execCommand('copy'); } catch (e) {}
    document.body.removeChild(ta);
  }
  window.mdShare = function () {
    copyText(location.href);
    toast('链接已复制');
  };
  window.mdAddBookmark = function () {
    var url = location.href;
    var title = document.title || '知识库';
    if (window.external && typeof window.external.AddFavorite === 'function') {
      try { window.external.AddFavorite(url, title); toast('已添加到浏览器书签'); return; } catch (e) {}
    }
    if (window.sidebar && window.sidebar.addPanel) {
      window.sidebar.addPanel(title, url, '');
      return;
    }
    var mac = navigator.platform && navigator.platform.toUpperCase().indexOf('MAC') >= 0;
    toast('当前浏览器不支持自动添加，请按 ' + (mac ? '⌘D' : 'Ctrl+D') + ' 将本页加入浏览器书签');
  };

  // ===== 首屏初始化 =====
  if (prose) {
    try { enhanceProse(); } catch (e) {
      console.warn('[minidocs] enhanceProse failed', e);
    }
    enableCherryMermaidTabs();
    fillBottomMeta();
    bindSpy();
  }
  renderRoute();
  formatTimes();
  initLike();
})();
