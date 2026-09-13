<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search, Plus, Delete, Edit, Key, WarningFilled } from '@element-plus/icons-vue'
import {
  addTableColumn,
  createTable,
  createTableRow,
  deleteTableRow,
  getTableMetadata,
  listDatabaseCatalogs,
  listDatabaseTables,
  listTableRows,
  updateTableRow
} from '@/api/ops/database'

const pageSize = 50
const catalogs = ref([])
const databasesLoading = ref(false)
const tablesLoading = ref(false)
const metadataLoading = ref(false)
const rowsLoading = ref(false)
const saving = ref(false)
const errorMessage = ref('')
const selectedDatabase = ref('')
const selectedTable = ref('')
const tables = ref([])
const metadata = ref({ columns: [], indexes: [], primaryKey: [], primaryKeyMode: 'NONE', writable: false })
const rows = ref([])
const total = ref(null)
const hasNext = ref(false)
const currentPage = ref(1)
const sortColumn = ref('')
const sortOrder = ref('asc')
const tableQuery = ref('')
const rowQuery = ref('')
const statusQuery = ref('')
const view = ref('data')
const rowDrawerVisible = ref(false)
const rowMode = ref('create')
const editingRow = ref(null)
const rowForm = ref({})
const newTableVisible = ref(false)
const fieldVisible = ref(false)
const newTableForm = ref({ name: '', comment: '' })
const fieldForm = ref({ name: '', type: 'VARCHAR(255)', nullable: true, defaultValue: '' })

const primaryKeyMode = computed(() => String(metadata.value.primaryKeyMode || '').toUpperCase())
const primaryKey = computed(() => {
  const value = metadata.value.primaryKey
  if (Array.isArray(value)) return value[0] || ''
  if (typeof value === 'object' && value) return value.name || ''
  return value || metadata.value.columns?.find((column) => column.primaryKey)?.name || ''
})
const writable = computed(() => metadata.value.writable === true && primaryKeyMode.value === 'SINGLE' && Boolean(primaryKey.value))
const readOnlyReason = computed(() => {
  if (primaryKeyMode.value === 'COMPOSITE') return '复合主键表只读，暂不支持行级写入'
  if (primaryKeyMode.value === 'NONE' || !primaryKey.value) return '无主键表只读，避免误删或无法定位记录'
  return '当前表只读，请联系管理员检查表权限'
})
const currentTableInfo = computed(() => tables.value.find((table) => table.name === selectedTable.value) || {})
const visibleTables = computed(() => {
  const query = tableQuery.value.trim().toLowerCase()
  return query ? tables.value.filter((table) => String(table.name).toLowerCase().includes(query)) : tables.value
})
const rowColumns = computed(() => metadata.value.columns || [])
const hasStatusColumn = computed(() => rowColumns.value.some((column) => column.name === 'status'))
const rowFilter = computed(() => {
  const filters = []
  if (statusQuery.value && hasStatusColumn.value) filters.push({ column: 'status', operator: 'eq', value: statusQuery.value })
  return filters
})
const visibleRows = computed(() => {
  const query = rowQuery.value.trim().toLowerCase()
  if (!query) return rows.value
  return rows.value.filter((row) => rowColumns.value.some((column) => displayValue(rowValue(row, column)).toLowerCase().includes(query)))
})
const pageCount = computed(() => {
  if (total.value === null || total.value === undefined) return hasNext.value ? currentPage.value + 1 : currentPage.value
  return Math.max(1, Math.ceil(Number(total.value) / pageSize))
})
const rowRangeText = computed(() => {
  if (!visibleRows.value.length) return '没有匹配的记录'
  const start = (currentPage.value - 1) * pageSize + 1
  const end = start + visibleRows.value.length - 1
  return total.value === null || total.value === undefined
    ? `第 ${start}–${end} 行`
    : `共 ${total.value} 行 · 第 ${start}–${end} 行`
})

function unwrapList(value, keys = []) {
  if (Array.isArray(value)) return value
  for (const key of keys) if (Array.isArray(value?.[key])) return value[key]
  return []
}

function normalizeColumn(column) {
  return {
    ...column,
    name: column.name || column.columnName,
    jdbcType: column.jdbcType || column.typeName || column.type || 'VARCHAR',
    typeName: column.typeName || column.jdbcType || column.type || 'VARCHAR',
    nullable: column.nullable !== false,
    primaryKey: column.primaryKey === true || column.key === 'PRI',
    autoIncrement: column.autoIncrement === true,
    generated: column.generated === true || column.generatedColumn === true || column.isGenerated === true,
    readOnly: column.readOnly === true || column.writable === false,
    defaultValue: column.defaultValue ?? (column.defaultValuePresent ? '有默认值' : '')
  }
}

function normalizeTable(table) {
  return {
    ...table,
    name: table.name || table.tableName,
    rowCountEstimate: table.rowCountEstimate ?? table.rowCount ?? null,
    primaryKeyMode: String(table.primaryKeyMode || (table.primaryKey ? 'SINGLE' : 'NONE')).toUpperCase()
  }
}

function normalizeRow(row) {
  if (row?.values && typeof row.values === 'object') return { values: row.values, rowVersion: row.rowVersion }
  return { values: row || {}, rowVersion: undefined }
}

function apiError(error, fallback = '操作失败，请稍后重试') {
  const rawMessage = error?.message || error?.response?.data?.msg || ''
  const code = error?.code || error?.response?.data?.code || error?.response?.data?.errorCode || rawMessage.match(/OPS_DB_[A-Z_]+/)?.[0]
  const messages = {
    OPS_DB_TABLE_READ_ONLY: '当前表没有单列主键，页面仅支持只读查看。',
    OPS_DB_CONFLICT: '记录已被其他操作修改，请刷新后重试。',
    OPS_DB_METADATA_CHANGED: '表结构已变化，请刷新表结构后重试。',
    OPS_DB_INVALID_REQUEST: '提交内容不符合要求，请检查字段和值。',
    OPS_DB_NOT_ALLOWED: '该数据库或数据表不在允许管理范围内。',
    OPS_DB_LIMIT: '查询或操作频率超过限制，请稍后重试。',
    OPS_DB_UPSTREAM: '数据库暂时不可用，请稍后重试。',
    OPS_DB_DDL_REJECTED: '表结构变更未通过安全校验，请检查名称、类型和默认值。'
  }
  return messages[code] || error?.message || fallback
}

function notifyError(error, fallback) {
  const message = apiError(error, fallback)
  errorMessage.value = message
  ElMessage.error(message)
}

async function loadCatalogs() {
  databasesLoading.value = true
  errorMessage.value = ''
  try {
    const data = await listDatabaseCatalogs()
    catalogs.value = unwrapList(data, ['catalogs', 'items']).map((catalog) => ({
      ...catalog,
      name: catalog.name || catalog.database,
      displayName: catalog.displayName || catalog.name || catalog.database
    }))
    if (!selectedDatabase.value && catalogs.value.length) selectedDatabase.value = catalogs.value[0].name
  } catch (error) {
    notifyError(error, '数据库列表加载失败')
  } finally {
    databasesLoading.value = false
  }
}

async function loadTables() {
  if (!selectedDatabase.value) return
  tablesLoading.value = true
  try {
    const data = await listDatabaseTables(selectedDatabase.value)
    tables.value = unwrapList(data, ['tables', 'items']).map(normalizeTable)
    const nextTable = tables.value.some((table) => table.name === selectedTable.value) ? selectedTable.value : tables.value[0]?.name || ''
    selectedTable.value = nextTable
  } catch (error) {
    tables.value = []
    selectedTable.value = ''
    notifyError(error, '数据表列表加载失败')
  } finally {
    tablesLoading.value = false
  }
}

async function loadTable() {
  if (!selectedDatabase.value || !selectedTable.value) return
  metadataLoading.value = true
  rowsLoading.value = true
  errorMessage.value = ''
  try {
    const metadataData = await getTableMetadata(selectedDatabase.value, selectedTable.value)
    metadata.value = {
      ...metadataData,
      columns: (metadataData?.columns || []).map(normalizeColumn),
      primaryKeyMode: String(metadataData?.primaryKeyMode || currentTableInfo.value.primaryKeyMode || 'NONE').toUpperCase()
    }
    if (!sortColumn.value) sortColumn.value = primaryKey.value || metadata.value.columns[0]?.name || ''
    await loadRows()
  } catch (error) {
    metadata.value = { columns: [], indexes: [], primaryKey: [], primaryKeyMode: 'NONE', writable: false }
    notifyError(error, '数据表结构加载失败')
  } finally {
    metadataLoading.value = false
    rowsLoading.value = false
  }
}

async function loadRows() {
  if (!selectedDatabase.value || !selectedTable.value) return
  const params = { page: currentPage.value, pageSize, sort: sortColumn.value || undefined, order: sortOrder.value }
  if (rowFilter.value.length) params.filter = JSON.stringify(rowFilter.value)
  const data = await listTableRows(selectedDatabase.value, selectedTable.value, params)
  rows.value = (data?.items || data?.rows || []).map(normalizeRow)
  total.value = data?.total ?? null
  hasNext.value = data?.hasNext === true || (total.value !== null && currentPage.value * pageSize < Number(total.value))
}

async function refresh() {
  currentPage.value = 1
  errorMessage.value = ''
  await loadTables()
  if (selectedTable.value) await loadTable()
}

function selectTable(name) {
  if (selectedTable.value === name) return
  selectedTable.value = name
  currentPage.value = 1
  sortColumn.value = ''
  rowQuery.value = ''
  statusQuery.value = ''
  view.value = 'data'
}

function toggleSort(column) {
  if (sortColumn.value === column) sortOrder.value = sortOrder.value === 'asc' ? 'desc' : 'asc'
  else {
    sortColumn.value = column
    sortOrder.value = 'asc'
  }
  currentPage.value = 1
  loadRows().catch((error) => notifyError(error, '排序查询失败'))
}

function rowValue(row, column) {
  return row?.values?.[typeof column === 'string' ? column : column?.name]
}

function isNullValue(value) {
  return value === null || value === undefined || value === ''
}

function displayValue(value) {
  if (isNullValue(value)) return 'NULL'
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

function statusClass(value) {
  if (value === '已完成' || value === '成功') return 'status-success'
  if (value === '待处理' || value === '处理中') return 'status-warning'
  return 'status-muted'
}

function openRowDrawer(row = null) {
  if (!writable.value) return ElMessage.warning(readOnlyReason.value)
  rowMode.value = row ? 'edit' : 'create'
  editingRow.value = row
  const values = {}
  rowColumns.value.forEach((column) => {
    if (!(rowMode.value === 'create' && column.autoIncrement)) values[column.name] = rowValue(row, column) ?? ''
  })
  rowForm.value = values
  rowDrawerVisible.value = true
  nextTick(() => document.querySelector('.database-row-drawer input:not([disabled])')?.focus())
}

function closeRowDrawer(done) {
  if (saving.value) return
  rowDrawerVisible.value = false
  done?.()
}

async function saveRow() {
  if (!writable.value) return ElMessage.warning(readOnlyReason.value)
  saving.value = true
  try {
    const values = {}
    rowColumns.value.forEach((column) => {
      const protectedForCreate = column.autoIncrement || column.generated || column.readOnly
      const protectedForUpdate = column.primaryKey || column.name === primaryKey.value || column.autoIncrement || column.generated || column.readOnly
      if (rowMode.value === 'create' ? protectedForCreate : protectedForUpdate) return
      const value = rowForm.value[column.name]
      if (value !== '' || !column.nullable) values[column.name] = value === '' && column.nullable ? null : value
    })
    if (rowMode.value === 'create') {
      await createTableRow(selectedDatabase.value, selectedTable.value, values)
      ElMessage.success('已新增 1 行')
    } else {
      await updateTableRow(selectedDatabase.value, selectedTable.value, rowValue(editingRow.value, primaryKey.value), values, editingRow.value?.rowVersion)
      ElMessage.success('已保存修改')
    }
    rowDrawerVisible.value = false
    await loadRows()
  } catch (error) {
    notifyError(error, rowMode.value === 'create' ? '新增记录失败' : '保存修改失败')
  } finally {
    saving.value = false
  }
}

async function confirmDelete(row) {
  if (!writable.value) return ElMessage.warning(readOnlyReason.value)
  const keyValue = rowValue(row, primaryKey.value)
  try {
    await ElMessageBox.confirm(`将删除 ${selectedTable.value} 中 ${primaryKey.value} = ${keyValue} 的记录。`, '删除这行记录？', {
      confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning'
    })
    saving.value = true
    await deleteTableRow(selectedDatabase.value, selectedTable.value, keyValue)
    ElMessage.success('已删除记录')
    await loadRows()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') notifyError(error, '删除记录失败')
  } finally {
    saving.value = false
  }
}

async function submitNewTable() {
  saving.value = true
  try {
    const createdName = newTableForm.value.name.trim()
    await createTable(selectedDatabase.value, {
      name: createdName,
      comment: newTableForm.value.comment.trim() || undefined,
      columns: [
        { name: 'id', type: 'BIGINT', nullable: false, primaryKey: true, autoIncrement: true },
        { name: 'created_at', type: 'DATETIME', nullable: false, defaultValue: 'CURRENT_TIMESTAMP' }
      ]
    })
    newTableVisible.value = false
    newTableForm.value = { name: '', comment: '' }
    ElMessage.success('表已创建')
    await loadTables()
    selectedTable.value = tables.value.find((table) => table.name === createdName)?.name || selectedTable.value
  } catch (error) {
    notifyError(error, '新建表失败')
  } finally {
    saving.value = false
  }
}

async function submitField() {
  saving.value = true
  try {
    await addTableColumn(selectedDatabase.value, selectedTable.value, {
      name: fieldForm.value.name.trim(), type: fieldForm.value.type,
      nullable: fieldForm.value.nullable, defaultValue: fieldForm.value.defaultValue.trim() || undefined
    })
    fieldVisible.value = false
    fieldForm.value = { name: '', type: 'VARCHAR(255)', nullable: true, defaultValue: '' }
    ElMessage.success('字段已添加')
    await loadTable()
  } catch (error) {
    notifyError(error, '添加字段失败')
  } finally {
    saving.value = false
  }
}

watch(selectedDatabase, async () => {
  selectedTable.value = ''
  currentPage.value = 1
  await loadTables()
})
watch(selectedTable, () => loadTable())
watch([rowQuery, statusQuery], () => {
  currentPage.value = 1
  if (selectedTable.value) loadRows().catch((error) => notifyError(error, '筛选查询失败'))
})

onMounted(loadCatalogs)
</script>

<template>
  <section class="database-workspace" aria-label="数据库管理">
    <aside class="database-sidebar">
      <label class="database-label" for="database-select">数据库</label>
      <el-select id="database-select" v-model="selectedDatabase" class="database-select" :loading="databasesLoading" aria-label="数据库">
        <el-option v-for="catalog in catalogs" :key="catalog.name" :label="catalog.displayName" :value="catalog.name" />
      </el-select>
      <div class="database-meta"><span class="online-dot">连接正常</span><span>{{ catalogs.length }} 个数据库</span></div>
      <div class="database-table-heading"><span>数据表</span><small>({{ tables.length }})</small></div>
      <el-input v-model="tableQuery" placeholder="搜索表名" clearable :prefix-icon="Search" />
      <div class="database-table-list" role="listbox" aria-label="数据表">
        <div v-if="tablesLoading" class="database-empty">正在加载数据表…</div>
        <button v-for="table in visibleTables" :key="table.name" type="button" class="database-table-item" :class="{ active: selectedTable === table.name }" @click="selectTable(table.name)">
          <span class="database-table-name"><span class="table-glyph">▤</span>{{ table.name }}</span>
          <span class="database-table-count">{{ table.rowCountEstimate ?? '—' }}<small v-if="String(table.primaryKeyMode).toUpperCase() !== 'SINGLE'"> · 只读</small></span>
        </button>
        <div v-if="!tablesLoading && !visibleTables.length" class="database-empty">没有匹配的表</div>
      </div>
      <div class="database-sidebar-foot">仅允许管理服务端白名单中的数据库和字段。</div>
    </aside>

    <main class="database-main">
      <div v-if="errorMessage" class="database-error" role="alert"><WarningFilled /> {{ errorMessage }}</div>
      <div class="database-main-head">
        <div class="database-title">
          <h2>{{ selectedTable || '数据库' }} <span v-if="selectedTable" class="table-status" :class="{ readonly: !writable }">{{ writable ? '可编辑' : '只读' }}</span></h2>
          <p v-if="selectedTable">{{ currentTableInfo.comment || '数据表' }} · {{ currentTableInfo.rowCountEstimate ?? '—' }} 行 · {{ rowColumns.length }} 个字段 · {{ writable ? `主键 ${primaryKey}` : readOnlyReason }}</p>
          <p v-else>请选择一个数据表开始查看</p>
        </div>
        <div class="database-actions">
          <el-button text :icon="Refresh" :loading="tablesLoading || metadataLoading || rowsLoading" @click="refresh">刷新</el-button>
          <el-button :icon="Plus" @click="newTableVisible = true">新建表</el-button>
        </div>
      </div>

      <template v-if="selectedTable">
        <div class="database-view-tabs" role="tablist">
          <button type="button" role="tab" :aria-selected="view === 'data'" class="database-view-tab" :class="{ active: view === 'data' }" @click="view = 'data'">数据</button>
          <button type="button" role="tab" :aria-selected="view === 'schema'" class="database-view-tab" :class="{ active: view === 'schema' }" @click="view = 'schema'">表结构</button>
        </div>

        <div v-if="view === 'data'" class="database-data-view">
          <div class="database-toolbar">
            <div class="database-filters">
              <el-input v-model="rowQuery" class="row-filter" placeholder="按任意字段筛选" clearable />
              <el-select v-if="hasStatusColumn" v-model="statusQuery" class="status-filter" clearable placeholder="全部状态"><el-option v-for="status in ['待处理', '已支付', '已发货', '已完成']" :key="status" :label="status" :value="status" /></el-select>
            </div>
            <el-button type="primary" :icon="Plus" :disabled="!writable" @click="openRowDrawer()">新增行</el-button>
          </div>
          <div class="database-table-scroll">
            <el-table v-loading="rowsLoading" :data="visibleRows" class="database-data-table" height="100%" stripe>
              <el-table-column v-for="column in rowColumns" :key="column.name" :prop="`values.${column.name}`" min-width="150">
                <template #header><button type="button" class="sort-button" :class="{ sorted: sortColumn === column.name }" @click="toggleSort(column.name)">{{ column.name }} <span>{{ sortColumn === column.name ? (sortOrder === 'asc' ? '↑' : '↓') : '↕' }}</span></button></template>
                <template #default="scope"><span :class="{ 'muted-cell': isNullValue(rowValue(scope.row, column)), 'key-cell': column.name === primaryKey, 'status-pill': column.name === 'status' && !isNullValue(rowValue(scope.row, column)), [statusClass(rowValue(scope.row, column))]: column.name === 'status' && !isNullValue(rowValue(scope.row, column)) }">{{ displayValue(rowValue(scope.row, column)) }}</span></template>
              </el-table-column>
              <el-table-column label="操作" width="130" fixed="right">
                <template #default="scope"><div v-if="writable" class="row-actions"><el-button text size="small" :icon="Edit" @click="openRowDrawer(scope.row)">编辑</el-button><el-button text type="danger" size="small" :icon="Delete" :disabled="saving" @click="confirmDelete(scope.row)">删除</el-button></div><span v-else class="muted-cell">只读</span></template>
              </el-table-column>
              <template #empty><div class="database-empty">没有匹配的记录</div></template>
            </el-table>
          </div>
          <div class="database-footer"><span>{{ rowRangeText }}</span><el-pagination v-model:current-page="currentPage" layout="prev, pager, next" :page-count="pageCount" :disabled="rowsLoading" @current-change="loadRows" /></div>
        </div>

        <div v-else class="database-schema-view">
          <div class="schema-intro"><p>字段定义 · 类型和默认值由服务端安全校验</p><el-button class="add-field-button" :icon="Plus" :disabled="!selectedTable || tablesLoading || metadataLoading || !rowColumns.length" @click="fieldVisible = true">添加字段</el-button></div>
          <el-table v-loading="metadataLoading" :data="rowColumns" class="schema-table">
            <el-table-column prop="name" label="字段" min-width="190"><template #default="scope"><code>{{ scope.row.name }}</code><el-tag v-if="scope.row.name === primaryKey" size="small" effect="plain"><Key /> 主键</el-tag><el-tag v-if="scope.row.generated || scope.row.readOnly" size="small" type="info" effect="plain">只读</el-tag></template></el-table-column>
            <el-table-column label="类型" min-width="140"><template #default="scope"><code>{{ scope.row.typeName }}</code></template></el-table-column>
            <el-table-column label="允许空值" width="110"><template #default="scope">{{ scope.row.nullable ? '是' : '否' }}</template></el-table-column>
            <el-table-column label="默认值" min-width="150"><template #default="scope">{{ scope.row.autoIncrement ? '自增' : (scope.row.defaultValue || '—') }}</template></el-table-column>
            <el-table-column label="说明" prop="comment" min-width="180"><template #default="scope"><span class="muted-cell">{{ scope.row.comment || '—' }}</span></template></el-table-column>
          </el-table>
          <p class="schema-note"><WarningFilled /> {{ readOnlyReason }}；数据库页面只允许单列主键表进行行级新增、编辑和删除。</p>
        </div>
      </template>
    </main>

    <el-drawer v-model="rowDrawerVisible" class="database-row-drawer" :title="rowMode === 'create' ? '新增行' : '编辑行'" size="420px" :before-close="closeRowDrawer">
      <p class="drawer-intro">填写字段值，空值和默认值会按表定义处理。</p>
      <el-form label-position="top" @submit.prevent="saveRow">
        <el-form-item v-for="column in rowColumns" :key="column.name" :label="column.name" :required="!column.nullable && !(rowMode === 'create' && (column.autoIncrement || column.generated || column.readOnly))">
          <el-input v-model="rowForm[column.name]" :disabled="column.autoIncrement || column.generated || column.readOnly || (rowMode === 'edit' && column.name === primaryKey)" :placeholder="column.autoIncrement ? '自增，保存时生成' : (column.generated || column.readOnly ? '只读字段' : (column.defaultValue || (column.nullable ? '可留空' : '请输入值')))" />
          <div class="field-hint">{{ column.generated || column.readOnly ? '只读字段' : (column.nullable ? '允许空值' : '必填') }} · {{ column.autoIncrement ? '系统自动生成' : `默认值：${column.defaultValue || '无'}` }}</div>
        </el-form-item>
        <div class="drawer-actions"><el-button @click="rowDrawerVisible = false">取消</el-button><el-button type="primary" native-type="submit" :loading="saving">保存</el-button></div>
      </el-form>
    </el-drawer>

    <el-dialog v-model="newTableVisible" title="新建表" width="420px"><el-form label-position="top" @submit.prevent="submitNewTable"><el-form-item label="表名" required><el-input v-model="newTableForm.name" pattern="[A-Za-z][A-Za-z0-9_]*" placeholder="例如 customer_notes" /></el-form-item><p class="field-hint">使用字母、数字和下划线，需以字母开头。将创建 id（自增主键）和 created_at 字段。</p><el-form-item label="说明"><el-input v-model="newTableForm.comment" placeholder="例如 客户备注" /></el-form-item><div class="dialog-actions"><el-button @click="newTableVisible = false">取消</el-button><el-button type="primary" native-type="submit" :loading="saving">创建表</el-button></div></el-form></el-dialog>
    <el-dialog v-model="fieldVisible" title="添加字段" width="460px"><el-form label-position="top" @submit.prevent="submitField"><el-form-item label="字段名" required><el-input v-model="fieldForm.name" pattern="[A-Za-z][A-Za-z0-9_]*" placeholder="例如 note" /></el-form-item><el-form-item label="类型"><el-select v-model="fieldForm.type" class="full-width"><el-option v-for="type in ['VARCHAR(255)', 'INT', 'BIGINT', 'DECIMAL(10,2)', 'DATETIME', 'DATE', 'TEXT', 'JSON']" :key="type" :label="type" :value="type" /></el-select></el-form-item><el-form-item label="允许空值"><el-switch v-model="fieldForm.nullable" /></el-form-item><el-form-item label="默认值"><el-input v-model="fieldForm.defaultValue" placeholder="留空表示无默认值" /></el-form-item><div class="dialog-actions"><el-button @click="fieldVisible = false">取消</el-button><el-button type="primary" native-type="submit" :loading="saving">添加字段</el-button></div></el-form></el-dialog>
  </section>
</template>

<style scoped>
.database-workspace { display:flex; flex:1 1 auto; min-height:560px; overflow:hidden; border:1px solid var(--color-border-subtle); border-radius:var(--radius-lg); background:var(--color-bg-surface); box-shadow:var(--shadow-sm); }
.database-sidebar { display:flex; flex:0 0 252px; flex-direction:column; min-width:0; padding:var(--space-4) var(--space-3); border-right:1px solid var(--color-border-subtle); background:#FCFBF9; }
.database-label,.database-table-heading { display:block; margin:0 0 6px; color:var(--color-text-secondary); font-size:var(--font-size-xs); font-weight:600; }
.database-select { width:100%; }.database-meta { display:flex; justify-content:space-between; gap:8px; margin:8px 1px 18px; color:var(--color-text-tertiary); font-size:var(--font-size-xs); }.online-dot { display:inline-flex; align-items:center; gap:5px; color:var(--color-success); }.online-dot:before { content:""; width:6px; height:6px; border-radius:50%; background:var(--color-success); }.database-table-heading small { color:var(--color-text-tertiary); font-weight:400; }.database-table-list { display:flex; flex:1 1 auto; flex-direction:column; gap:2px; min-height:100px; margin-top:9px; overflow:auto; }.database-table-item { display:flex; align-items:center; justify-content:space-between; gap:8px; padding:10px 9px; border:1px solid transparent; border-radius:7px; color:var(--color-text-secondary); background:transparent; text-align:left; }.database-table-item:hover { background:var(--color-bg-hover); }.database-table-item.active { border-color:#F1C4A9; color:var(--accent); background:var(--accent-soft); }.database-table-name { display:flex; min-width:0; align-items:center; gap:8px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font-family:var(--font-family-mono); font-size:13px; }.table-glyph { color:var(--color-text-tertiary); }.database-table-count { flex:none; color:var(--color-text-tertiary); font-size:11px; }.database-table-count small { font-size:10px; }.database-empty { padding:24px 8px; color:var(--color-text-tertiary); font-size:var(--font-size-xs); text-align:center; }.database-sidebar-foot { margin-top:12px; padding:12px 4px 0; border-top:1px solid var(--color-border-subtle); color:var(--color-text-tertiary); font-size:var(--font-size-xs); }.database-main { display:flex; flex:1 1 auto; flex-direction:column; min-width:0; }.database-error { display:flex; align-items:center; gap:6px; padding:8px 20px; border-bottom:1px solid var(--color-danger-soft); color:var(--color-danger); font-size:var(--font-size-xs); }.database-main-head { display:flex; align-items:flex-start; justify-content:space-between; gap:12px; padding:18px 20px 14px; border-bottom:1px solid var(--color-border-subtle); }.database-title { min-width:0; }.database-title h2 { display:flex; align-items:center; gap:8px; margin:0; font-size:18px; }.database-title p { margin:4px 0 0; color:var(--color-text-tertiary); font-size:12px; }.table-status { display:inline-flex; padding:2px 7px; border-radius:999px; color:var(--color-success); background:var(--color-success-soft); font-size:11px; font-weight:500; }.table-status.readonly { color:var(--color-text-tertiary); background:var(--color-bg-muted); }.database-actions,.database-filters,.row-actions,.drawer-actions,.dialog-actions { display:flex; align-items:center; gap:8px; }.database-actions { flex:none; }.database-view-tabs { display:flex; gap:22px; padding:0 20px; border-bottom:1px solid var(--color-border-subtle); }.database-view-tab { position:relative; padding:13px 2px 11px; border:0; color:var(--color-text-tertiary); background:transparent; }.database-view-tab.active { color:var(--accent); font-weight:600; }.database-view-tab.active:after { position:absolute; right:0; bottom:-1px; left:0; height:2px; border-radius:2px 2px 0 0; background:var(--accent); content:""; }.database-data-view,.database-schema-view { display:flex; flex:1 1 auto; flex-direction:column; min-height:0; }.database-toolbar { display:flex; align-items:center; justify-content:space-between; gap:10px; padding:14px 20px; }.row-filter { width:230px; }.status-filter { width:150px; }.database-table-scroll { flex:1 1 auto; min-height:260px; overflow:auto; border-top:1px solid var(--color-border-subtle); border-bottom:1px solid var(--color-border-subtle); }.database-data-table,.schema-table { min-width:760px; height:100%; }.database-data-table :deep(.el-table__body-wrapper),.schema-table :deep(.el-table__body-wrapper) { overflow-y:auto; }.sort-button { padding:0; border:0; color:inherit; background:transparent; font:inherit; cursor:pointer; }.sort-button span { color:var(--color-text-disabled); }.sort-button.sorted span { color:var(--accent); }.muted-cell { color:var(--color-text-tertiary); }.key-cell { color:var(--color-text-primary); font-family:var(--font-family-mono); }.status-pill { display:inline-flex; padding:2px 7px; border-radius:999px; font-size:11px; }.status-success { color:var(--color-success); background:var(--color-success-soft); }.status-warning { color:var(--color-warning); background:var(--color-warning-soft); }.status-muted { color:var(--color-text-tertiary); background:var(--color-bg-muted); }.database-footer { display:flex; align-items:center; justify-content:space-between; gap:12px; padding:13px 20px; color:var(--color-text-tertiary); font-size:12px; }.schema-intro { display:flex; align-items:center; justify-content:space-between; gap:12px; padding:18px 20px 12px; }.schema-intro p { margin:0; color:var(--color-text-tertiary); font-size:12px; }.add-field-button { color:var(--accent); border-color:#EDC1A7; background:var(--accent-soft); }.schema-table { height:auto; margin:0 20px; width:calc(100% - 40px); }.schema-note { display:flex; align-items:flex-start; gap:7px; margin:16px 20px; padding:10px 12px; border:1px solid var(--color-border-subtle); border-radius:6px; color:var(--color-text-tertiary); background:var(--color-bg-muted); font-size:12px; }.schema-note :deep(svg) { flex:none; width:15px; color:var(--color-warning); }.drawer-intro { margin:0 0 18px; color:var(--color-text-tertiary); font-size:12px; }.field-hint { margin-top:4px; color:var(--color-text-tertiary); font-size:11px; line-height:1.4; }.full-width { width:100%; }.drawer-actions,.dialog-actions { justify-content:flex-end; margin-top:20px; }
@media (max-width:640px) { .database-workspace { flex-direction:column; min-height:calc(100dvh - var(--layout-tab-bar-height) - 190px); border-radius:var(--radius-md); overflow:auto; }.database-sidebar { flex:0 0 auto; border-right:0; border-bottom:1px solid var(--color-border-subtle); }.database-table-list { flex:0 0 auto; max-height:116px; }.database-sidebar-foot { display:none; }.database-main { min-height:540px; }.database-main-head { padding:14px 12px 12px; }.database-title h2 { font-size:16px; }.database-title p { max-width:220px; line-height:1.45; }.database-actions { gap:2px; }.database-actions :deep(.el-button) { padding:0 7px; }.database-toolbar { align-items:stretch; flex-direction:column; padding:12px; }.database-filters { width:100%; }.row-filter { flex:1; width:auto; }.status-filter { width:130px; }.database-table-scroll { min-height:270px; }.database-footer { padding:10px 12px; }.database-footer :deep(.el-pagination) { --el-pagination-button-width:26px; }.database-view-tabs { padding:0 12px; }.schema-intro { align-items:flex-start; padding:14px 12px 10px; }.schema-table { margin:0 12px; width:calc(100% - 24px); }.schema-note { margin:12px; }.database-error { padding:8px 12px; }.database-row-drawer { width:100% !important; } }
</style>
