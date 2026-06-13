<script setup lang="ts">
definePageMeta({ middleware: 'admin-auth', layout: 'admin' })

const { get, post, del } = useAdminApi()

const tags = ref<any[]>([])
const articleCount = ref<Record<number, number>>({})
const loading = ref(false)
const newName = ref('')
const showModal = ref(false)
const newTag = reactive({ name: '' })

const load = async () => {
  loading.value = true
  try {
    const res = await get<any>('/articles/tags')
    tags.value = res.data || []
    // 2026-06-12 修复：原逻辑还会再发一个 `/articles?size=1000` 公开端点（status=1 过滤掉草稿/归档）
    // 自己 reduce 统计 tagIds，结果是"只看已发布"——admin 后台想看的是该标签真实被多少篇引用，
    // 包含草稿和归档。后端 /articles/tags 已经基于 article_tag 表做了
    // `SELECT COUNT(*) FROM article_tag WHERE tag_id = ?`，准确无遗漏，直接复用即可。
    const map: Record<number, number> = {}
    for (const t of tags.value) map[t.id] = Number(t.articleCount) || 0
    articleCount.value = map
  } catch { /* ignore */ }
  loading.value = false
}

// [Bug fix 2026-06-13] handleCreateQuick：补 try/catch，后端 1002(slug 重复)/400 时 alert
const handleCreateQuick = async () => {
  if (!newName.value.trim()) return
  try {
    await post('/articles/tags', { name: newName.value.trim() })
    newName.value = ''
    load()
  } catch (e: any) {
    alert('创建失败：' + (e?.data?.message || e?.message || '未知错误'))
  }
}

const openModal = () => {
  Object.assign(newTag, { name: '' })
  showModal.value = true
}

const closeModal = () => { showModal.value = false }

// [Bug fix 2026-06-13]
// 1) 移除 `slug: newTag.name`：newTag.name 是用户原始输入（如 "Spring Boot"），含空格/大写，
//    传给后端后不走自动 slug 生成，直接以原始字符串落库（与 handleCreateQuick 行为不一致）。
//    去掉 slug 字段后，后端会自动执行 name.toLowerCase().replaceAll(...) 生成合规 slug。
// 2) 补 try/catch，后端报 1002(slug 重复)/400 时 alert 给用户。
const handleCreate = async () => {
  if (!newTag.name) { alert('请填写名称'); return }
  try {
    await post('/articles/tags', { ...newTag })
    closeModal()
    load()
  } catch (e: any) {
    alert('创建失败：' + (e?.data?.message || e?.message || '未知错误'))
  }
}

// [Bug fix 2026-06-13] handleDelete：补 try/catch
const handleDelete = async (t: any) => {
  if (!confirm(`确认删除标签「${t.name}」？`)) return
  try {
    await del(`/articles/tags/${t.id}`)
    load()
  } catch (e: any) {
    alert('删除失败：' + (e?.data?.message || e?.message || '未知错误'))
  }
}

// 字号按文章数对数缩放
const tagSize = (count: number) => {
  if (!count) return '13px'
  if (count < 2) return '14px'
  if (count < 4) return '16px'
  if (count < 7) return '19px'
  return '22px'
}

const tagWeight = (count: number) => count >= 4 ? '600' : '500'

onMounted(load)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>标签管理</h1>
        <p>用标签对文章做交叉分类，方便按主题浏览</p>
      </div>
      <button @click="openModal" class="btn-new">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M12 5v14M5 12h14"/></svg>
        新建标签
      </button>
    </div>

    <!-- 快速添加 -->
    <div class="card" style="padding: 12px 16px; margin-bottom: 14px; display: flex; gap: 8px; align-items: center;">
      <input v-model="newName" @keyup.enter="handleCreateQuick" class="form-control" placeholder="快速添加标签（回车提交）…" style="max-width: 280px; font-size: 13px;" />
      <button @click="handleCreateQuick" class="btn btn-ghost btn-sm">添加</button>
      <span style="margin-left: auto; color: var(--muted); font-size: 13px;">共 {{ tags.length }} 个</span>
    </div>

    <!-- 标签云 -->
    <div v-if="loading" class="card" style="padding: 40px; text-align: center; color: var(--muted);">加载中…</div>
    <div v-else-if="!tags.length" class="card" style="padding: 40px; text-align: center; color: var(--muted);">还没有标签</div>
    <div v-else class="card" style="padding: 32px 24px;">
      <div class="cloud-header">
        <div class="cloud-legend">
          <span class="cloud-legend-item"><span class="cloud-legend-dot"></span>1-2 篇</span>
          <span class="cloud-legend-item"><span class="cloud-legend-dot secondary"></span>3-6 篇</span>
          <span class="cloud-legend-item"><span class="cloud-legend-dot tertiary"></span>7+ 篇</span>
        </div>
      </div>
      <div class="cloud-area" style="border: none; padding: 24px 0 0;">
        <span v-for="t in tags" :key="t.id" class="tag-cloud-item"
          :style="{ fontSize: tagSize(articleCount[t.id] || 0), fontWeight: tagWeight(articleCount[t.id] || 0) }"
          @click="handleDelete(t)" title="点击删除">
          {{ t.name }} <span class="count">{{ articleCount[t.id] || 0 }}</span>
        </span>
      </div>
    </div>

    <!-- 标签列表（带删除） -->
    <div v-if="!loading && tags.length" class="table-wrap" style="margin-top: 16px;">
      <table class="table">
        <thead>
          <tr>
            <th>名称</th>
            <th style="width: 100px;">Slug</th>
            <th style="width: 100px;">文章数</th>
            <th style="width: 80px;">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="t in tags" :key="t.id">
            <td>
              <div class="cat-cell">
                <div class="cat-icon" style="background: var(--primary-soft);">#</div>
                <div>
                  <div class="cat-name">{{ t.name }}</div>
                </div>
              </div>
            </td>
            <td><code style="font-size: 11px; color: var(--muted); font-family: 'JetBrains Mono', monospace;">/{{ t.slug }}</code></td>
            <td><span class="cat-count">{{ articleCount[t.id] || 0 }}</span></td>
            <td>
              <div class="row-actions">
                <button @click="handleDelete(t)" class="row-action danger" title="删除">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 新建标签 modal -->
    <div v-if="showModal" class="modal-backdrop" @click.self="closeModal">
      <div class="modal">
        <div class="modal-header">
          <div class="modal-title">新建标签</div>
          <button @click="closeModal" class="modal-close">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18M6 6l12 12"/></svg>
          </button>
        </div>
        <div class="modal-body">
          <div class="form-group" style="margin: 0;">
            <label class="form-label">名称 *</label>
            <input v-model="newTag.name" class="form-control" placeholder="如：Spring Boot" />
          </div>
        </div>
        <div class="modal-footer">
          <button @click="closeModal" class="btn btn-ghost btn-sm">取消</button>
          <button @click="handleCreate" class="btn btn-primary btn-sm">创建</button>
        </div>
      </div>
    </div>
  </div>
</template>
