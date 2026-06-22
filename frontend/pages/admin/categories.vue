<script setup lang="ts">
definePageMeta({ middleware: 'admin-auth', layout: 'admin' })

const { get, post, put, del } = useAdminApi()
const $toast = useToast()
const $dialog = useDialog()

const categories = ref<any[]>([])
const articleCount = ref<Record<number, number>>({})
const loading = ref(false)

const editing = ref<any | null>(null)
const form = reactive({ name: '', slug: '', description: '', sort: 0, visible: 1 })
const showModal = ref(false)

const load = async () => {
  loading.value = true
  try {
    const res = await get<any>('/articles/categories/all')
    categories.value = res.data || []
  } catch { /* ignore */ }

  // 2026-06-13 修复（BUG-056）：原走 `/articles/admin/all?size=1000` 后端 size 上限 100 早改 400
  // → 整个 catch 吞掉 → categories 永远显示"0 篇"。改用新增的 /articles/categories/with-count
  // 端点：单次 SQL GROUP BY 拿全分类文章数，无 size 限制，O(1) 复杂度。
  try {
    const res = await get<any>('/articles/categories/with-count')
    articleCount.value = res.data || {}
  } catch { /* ignore */ }

  loading.value = false
}

const startCreate = () => {
  editing.value = { id: null }
  Object.assign(form, { name: '', slug: '', description: '', sort: 0, visible: 1 })
  showModal.value = true
}

const startEdit = (c: any) => {
  editing.value = c
  Object.assign(form, c)
  showModal.value = true
}

const closeModal = () => {
  showModal.value = false
  editing.value = null
}

// [Bug fix 2026-06-13] handleSave：补 try/catch，后端返回 1002(slug 重复)/400 时 alert 给用户
// 2026-06-16 改造：alert → $toast
const handleSave = async () => {
  if (!form.name) { $toast.warning('请填写名称'); return }
  try {
    if (editing.value?.id) {
      await put(`/articles/categories/${editing.value.id}`, form)
    } else {
      await post('/articles/categories', form)
    }
    $toast.success('已保存')
    closeModal()
    load()
  } catch (e: any) {
    $toast.error('保存失败：' + (e?.data?.message || e?.message || '未知错误'))
  }
}

// [Bug fix 2026-06-13]
// 1) 确认文案修正：后端 deleteCategory 在该分类下仍有文章时会返回 1004 错误并拒绝删除，
//    而非把文章标记为"未分类"——原文案误导用户以为删除是安全的。
// 2) 补 try/catch：后端返回 1004 时 del() 抛异常，原代码无 catch → 用户收不到任何反馈。
// 2026-06-16 改造：confirm → $dialog.confirm，alert → $toast
const handleDelete = async (c: any) => {
  const { confirmed } = await $dialog.confirm({
    title: '删除分类',
    message: `确认删除分类「${c.name}」？若该分类下仍有文章，后端会拒绝删除。`,
    confirmText: '删除',
    danger: true
  })
  if (!confirmed) return
  try {
    await del(`/articles/categories/${c.id}`)
    $toast.success('已删除')
    load()
  } catch (e: any) {
    $toast.error('删除失败：' + (e?.data?.message || e?.message || '未知错误'))
  }
}

const totalArticles = computed(() => Object.values(articleCount.value).reduce((s, n) => s + n, 0))
const visibleCount = computed(() => categories.value.filter(c => c.visible === 1).length)
const hiddenCount = computed(() => categories.value.filter(c => c.visible === 0).length)

onMounted(load)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>分类管理</h1>
        <p>组织你的内容主题，便于访客按兴趣浏览</p>
      </div>
      <button @click="startCreate" class="btn-new">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M12 5v14M5 12h14"/></svg>
        新建分类
      </button>
    </div>

    <!-- Stats -->
    <div class="stats-row">
      <div class="stat-card">
        <div class="stat-card-label">总分类</div>
        <div class="stat-card-value">{{ categories.length }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-card-label">已显示</div>
        <div class="stat-card-value">{{ visibleCount }}</div>
        <div class="stat-card-delta">前台可见</div>
      </div>
      <div class="stat-card">
        <div class="stat-card-label">已隐藏</div>
        <div class="stat-card-value">{{ hiddenCount }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-card-label">总文章</div>
        <div class="stat-card-value">{{ totalArticles }}</div>
        <div class="stat-card-delta">已分类</div>
      </div>
    </div>

    <div v-if="loading" class="card" style="padding: 40px; text-align: center; color: var(--muted);">加载中…</div>
    <div v-else-if="!categories.length" class="card" style="padding: 40px; text-align: center; color: var(--muted);">还没有分类</div>

    <div v-else class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>分类</th>
            <th style="width: 35%;">描述</th>
            <th style="width: 100px;">文章数</th>
            <th style="width: 80px;">排序</th>
            <th style="width: 80px;">状态</th>
            <th style="width: 100px;">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in categories" :key="c.id">
            <td>
              <div class="cat-cell">
                <div class="cat-icon">{{ c.name?.[0] || '?' }}</div>
                <div>
                  <div class="cat-name">{{ c.name }}</div>
                  <div class="cat-slug">/{{ c.slug }}</div>
                </div>
              </div>
            </td>
            <td><div class="cat-desc">{{ c.description || '—' }}</div></td>
            <td><span class="cat-count">{{ articleCount[c.id] || 0 }}</span></td>
            <td><span class="cat-sort">{{ c.sort }}</span></td>
            <td>
              <span class="badge" :class="c.visible === 1 ? 'badge-success' : 'badge-muted'">
                {{ c.visible === 1 ? '显示' : '隐藏' }}
              </span>
            </td>
            <td>
              <div class="row-actions">
                <button @click="startEdit(c)" class="row-action" title="编辑">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5L17 3z"/></svg>
                </button>
                <button @click="handleDelete(c)" class="row-action danger" title="删除">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Modal -->
    <div v-if="showModal" class="modal-backdrop" @click.self="closeModal">
      <div class="modal">
        <div class="modal-header">
          <div class="modal-title">{{ editing?.id ? '编辑分类' : '新建分类' }}</div>
          <button @click="closeModal" class="modal-close">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18M6 6l12 12"/></svg>
          </button>
        </div>
        <div class="modal-body">
          <div class="form-group">
            <label class="form-label">名称 *</label>
            <input v-model="form.name" class="form-control" placeholder="如：技术" />
          </div>
          <div class="form-group">
            <label class="form-label">Slug</label>
            <input v-model="form.slug" class="form-control" placeholder="如：tech（留空自动生成）" />
          </div>
          <div class="form-group">
            <label class="form-label">描述</label>
            <input v-model="form.description" class="form-control" placeholder="一句话描述" />
          </div>
          <div class="form-row-2">
            <div class="form-group" style="margin: 0;">
              <label class="form-label">排序</label>
              <input v-model.number="form.sort" type="number" class="form-control" />
            </div>
            <div class="form-group" style="margin: 0;">
              <label class="form-label">可见性</label>
              <select v-model="form.visible" class="form-control">
                <option :value="1">显示</option>
                <option :value="0">隐藏</option>
              </select>
            </div>
          </div>
        </div>
        <div class="modal-footer">
          <button @click="closeModal" class="btn btn-ghost btn-sm">取消</button>
          <button @click="handleSave" class="btn btn-primary btn-sm">保存</button>
        </div>
      </div>
    </div>
  </div>
</template>
