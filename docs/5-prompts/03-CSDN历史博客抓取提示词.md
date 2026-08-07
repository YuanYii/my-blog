# CSDN 历史博客抓取提示词 v2

> 用途：配合 AutoGLM Browser Agent，从 CSDN 博客列表页逐篇抓取文章内容、图片和元数据，生成本项目 ZIP 导入包。
> 目标系统：my-blog DEV-002（ZIP 导入功能）
> 前提：浏览器已登录 CSDN（Agent 共享你的浏览器 Cookie，不需要在 prompt 里登录）

---

## 零、开始前检查

### 0.1 确认登录态

先用浏览器打开 `https://blog.csdn.net/{你的CSDN用户名}`，确认：

- 页面能正常加载（不是登录页 / 验证码页）
- 文章列表可见

如果触发了验证码，先手动过掉。Agent 抓到一半触发验证码会导致当前那篇文章丢失，需要从头来。

### 0.2 CSDN 用户名

将本文档中所有 `{你的CSDN用户名}` 替换为实际用户名，否则 Agent 无法确定目标。

---

## 一、收集文章列表

1. 打开 `https://blog.csdn.net/{你的CSDN用户名}`
2. 点击「文章」tab 切换到文章列表
3. **逐页翻页**，每页收集以下信息（仅列表页可见的）：
    - 文章标题（用于后续识别）
    - 详情页链接
    - **注意：列表页的日期可能是"3天前""1个月前"这种相对时间，不可直接使用。正确发布日期一律从详情页提取。**
4. 翻到最后一页或到达目标数量上限后停止
5. **每页操作间隔 ≥ 5 秒**，触发验证码则停止、人工过掉后再继续

> ⚠️ 如果文章总数 > 50 篇，建议分批执行（第一批先抓 20~30 篇），每批次间让浏览器闲置 5~10 分钟，降低被反爬概率。

---

## 二、逐篇抓取

按发布时间**从旧到新**依次处理每篇文章。处理一篇的完整步骤：

### 2.1 打开详情页 & 等待渲染

1. 打开文章详情页
2. **等待 3~5 秒**（CSDN 是 SPA，正文由 JS 动态渲染，不等待会拿到空壳）
3. 向下滚动到正文区域，等正文完全加载后再开始抓取

### 2.2 抓取字段

| 字段     | 来源                   | 说明                                                       |
| -------- | ---------------------- | ---------------------------------------------------------- |
| 标题     | `<h1>` 或 `<title>`    | 去掉尾部 `_xxx（作者名）-CSDN博客` 等网站后缀              |
| 发布日期 | 文章 meta 区（详情页） | 格式 `YYYY-MM-DD`，**禁止使用列表页的日期**                |
| 阅读数   | 文章 meta 区           | 纯数字，去掉"阅读""阅读量""万""k"等单位；`1.2万` → `12000` |
| 点赞数   | 文章底部操作区         | 可选，纯数字                                               |
| 评论数   | 文章底部操作区         | 可选，纯数字                                               |
| 分类     | 顶部面包屑/标签区      | 可多个，用 YAML 数组                                       |
| 标签     | 文章底部标签           | 可多个，用 YAML 数组                                       |

### 2.3 正文提取

提取文章正文（CSDN 正文容器通常为 `<article>` 或 `#content_views`），转换为 Markdown：

- **代码块**：`<pre><code>` → ` ```语言\n代码\n ``` `
    - 优先从 `<code>` 或 `<pre>` 的 `class` 属性提取语言（如 `language-java` → `java`）
    - 无语言标注时，通过代码内容启发式推断（`import java.` → java，`def ` → python，`function` / `const` → javascript，`SELECT` / `CREATE TABLE` → sql，`#!/bin/bash` → bash）
    - 完全推断不出 → 不写语言标记（` ```\n代码\n``` `）
- **图片**：提取 `<img>` 的 `src`，暂保留原 URL，后续步骤统一处理（见第三节）
- **表格**：`<table>` → Markdown 表格
- **标题**：`h1`→`#`，`h2`→`##`，依此类推（但跳过文章主标题，正文从 `##` 起）
- **链接**：保留原格式
- **LaTeX 公式**：保持 `$$...$$` 或 `$...$` 格式
- **清理**：去掉以下内容：
    - 文章开头的自动目录（TOC，通常是一个 `<div class="toc">` 或包含"目录""文章目录"等文字的模块）
    - 文末版权声明（"版权声明：本文为博主原创…"）
    - "阅读更多""关注博主"等推广模块
    - "打赏""点赞"等交互组件的文字描述

---

## 三、图片处理

正文中提取到的所有图片 URL（CSDN 通常为 OSS 签名 URL，会过期）：

- **必须在当前浏览器 session 内完成下载**，不要脱离浏览器用 `curl` 下载
- 图片保存到 `images/` 目录
- 命名：`{slug}-{序号}.png`（如 `spring-boot-ru-men-01.png`）
- Markdown 中替换为相对路径：`![图片描述](images/spring-boot-ru-men-01.png)`
- 下载失败时保留原 URL 并标注：`<!-- 图片下载失败：原URL -->`
- 如果文章无图片，`images/` 目录无需为此文章创建文件

---

## 四、输出格式

### 4.1 文件命名

```
{YYYY-MM-DD}-{slug}.md
```

- `YYYY-MM-DD`：详情页提取的发布日期
- `slug`：标题转写规则——**只保留字母、数字和连字符**，中文转拼音（全小写、空格→连字符）
    - `Spring Boot 入门教程` → `spring-boot-ru-men-jiao-cheng`
    - `Java 多线程详解` → `java-duo-xian-cheng-xiang-jie`
    - 如果无法可靠转拼音，退而求其次：去掉中文，保留英文部分（`Spring Boot 入门` → `spring-boot`）

### 4.2 YAML Front Matter

```yaml
---
date: 2024-06-15
view_count: 1280
categories:
  - Java
  - 后端开发
tags:
  - Spring Boot
  - MyBatis
status: published
summary: ""
---
```

- `categories`：YAML 数组，一篇文章可属多个分类
- `tags`：YAML 数组
- `summary`：留空字符串即可，由 my-blog 后端从正文自动截取
- `status`：统一 `published`

### 4.3 Markdown 正文

紧跟 front matter 之后。

---

## 五、断点续抓

为防止中途中断导致已抓内容丢失：

1. **每处理完一篇文章立即落盘**，保存为 `{date}-{slug}.md`
2. 维护一个进度文件（如 `/tmp/csdn-import/progress.txt`），每完成一篇追加一行文件名
3. 如果中断，根据 `progress.txt` 跳过已完成的文章，从下一篇继续

---

## 六、打包（手动执行）

抓取全部完成后，在 `/tmp/csdn-import/` 下手动运行以下脚本：

```bash
#!/bin/bash
# cwd: /tmp/csdn-import/

# 1. 按发布日期排序所有 md 文件
FILES=$(ls *.md | sort)

# 2. 以 2 篇一组拆分，创建 batch 目录并复制文件
BATCH=1; COUNT=0; DIR="batch-1"
mkdir -p "$DIR/images"

for f in $FILES; do
  if [ $COUNT -ge 2 ]; then
    BATCH=$((BATCH + 1))
    COUNT=0
    DIR="batch-$BATCH"
    mkdir -p "$DIR/images"
  fi
  cp "$f" "$DIR/"
  COUNT=$((COUNT + 1))
done

# 3. 为每个 batch 复制对应文章的图片
for d in batch-*/; do
  for md in "$d"*.md; do
    # 从 md 文件名提取 slug
    slug=$(basename "$md" .md | sed 's/^[0-9-]*//; s/^-//')
    # 复制该 slug 对应的所有图片
    cp images/"$slug"-*.png "$d/images/" 2>/dev/null
  done
done

# 4. 逐批打包
for d in batch-*/; do
  num=${d#batch-}; num=${num%/}
  (cd "$d" && zip -r "../csdn-import-$(printf '%02d' "$num").zip" *.md images/)
done

# 5. 验证
echo "=== ZIP 列表 ==="
ls -lh csdn-import-*.zip
echo ""
echo "=== 每个 ZIP 内容 ==="
for z in csdn-import-*.zip; do
  echo "--- $z ($(du -h "$z" | cut -f1)) ---"
  unzip -l "$z"
done
```

---

## 七、ZIP 导入前检查清单

每个 ZIP 包确认：

- [ ] 仅含 `.md` 和 `.png` 文件
- [ ] 每个 `.md` 有 `---` 包裹的 YAML front matter
- [ ] `date` 格式为 `YYYY-MM-DD`
- [ ] `view_count` 为纯数字
- [ ] 图片引用为 `images/xxx.png` 相对路径
- [ ] 每个 ZIP ≤ 2 篇 `.md` + 其引用图片
- [ ] 每个 ZIP 解压后 ≤ 5MB

---

## 八、常见问题 & 应对

| 问题                                | 应对                                                         |
| ----------------------------------- | ------------------------------------------------------------ |
| 抓取中触发验证码                    | Agent 返回 `INTERACT_REQUIRED`，手动过验证码后继续           |
| 某篇文章详情页打不开                | 记录到错误日志，跳过，继续下一篇                             |
| 图片下载后打不开                    | 可能 OSS 签名过期，标注 `<!-- 图片下载失败 -->`，保留原 URL  |
| CSDN 页面改版导致元素定位失败       | 手动打开一篇确认当前 DOM 结构，调整 prompt 中的 CSS selector |
| 文章太多，Agent 单次 session 跑不完 | 利用断点续抓机制分批跑                                       |