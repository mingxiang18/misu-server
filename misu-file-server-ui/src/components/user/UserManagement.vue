<template>
  <div class="user-management">
    <el-result v-if="!isAdmin" icon="warning" title="无权访问" sub-title="只有 ADMIN 用户可以使用用户管理">
      <template #extra>
        <el-button type="primary" @click="router.push('/')">返回首页</el-button>
      </template>
    </el-result>

    <template v-else>
      <div class="toolbar">
        <el-form :inline="true" :model="queryParam" class="query-form">
          <el-form-item label="用户名">
            <el-input v-model="queryParam.userName" clearable placeholder="搜索用户名" @keyup.enter="fetchUserList" />
          </el-form-item>
          <el-form-item label="手机号">
            <el-input v-model="queryParam.phoneNumber" clearable placeholder="搜索手机号" @keyup.enter="fetchUserList" />
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="queryParam.status" clearable placeholder="全部" class="status-select">
              <el-option label="正常" value="0" />
              <el-option label="停用" value="1" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :icon="Search" @click="fetchUserList">查询</el-button>
            <el-button :icon="Refresh" @click="resetQuery">重置</el-button>
          </el-form-item>
        </el-form>
        <el-button type="primary" :icon="Plus" @click="openAddDialog">新增用户</el-button>
      </div>

      <!-- 桌面：表格 -->
      <el-table v-if="isDesktop" v-loading="loading" :data="tableData" :max-height="tableMaxHeight" class="user-table">
        <el-table-column prop="userId" label="ID" width="80" />
        <el-table-column prop="userName" label="用户名" min-width="130" />
        <el-table-column prop="nickName" label="昵称" min-width="130" />
        <el-table-column prop="phoneNumber" label="手机号" min-width="140" />
        <el-table-column prop="email" label="邮箱" min-width="180" show-overflow-tooltip />
        <el-table-column label="角色" min-width="180">
          <template #default="{ row }">
            <el-tag v-for="role in row.roles" :key="role" class="role-tag" size="small">{{ role }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === '1' ? 'danger' : 'success'">
              {{ row.status === '1' ? '停用' : '正常' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :icon="View" @click="openViewDialog(row)">查看</el-button>
            <el-button link type="primary" :icon="Edit" @click="openEditDialog(row)">修改</el-button>
            <el-button link type="warning" :icon="Key" @click="openResetPasswordDialog(row)">重置密码</el-button>
            <el-button link type="danger" :icon="Delete" @click="deleteUserRow(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 移动：卡片 -->
      <div v-else class="user-cards" v-loading="loading">
        <div v-if="!loading && tableData.length === 0" class="user-empty">暂无用户</div>
        <article v-for="row in tableData" :key="row.userId" class="user-card">
          <header class="user-card-header">
            <div class="user-card-name">
              <span class="user-card-username">{{ row.userName }}</span>
              <span class="user-card-nick" v-if="row.nickName">（{{ row.nickName }}）</span>
            </div>
            <el-tag :type="row.status === '1' ? 'danger' : 'success'" size="small">
              {{ row.status === '1' ? '停用' : '正常' }}
            </el-tag>
          </header>
          <div class="user-card-meta" v-if="row.phoneNumber || row.email">
            <div v-if="row.phoneNumber">手机：{{ row.phoneNumber }}</div>
            <div v-if="row.email" class="user-card-email">邮箱：{{ row.email }}</div>
          </div>
          <div class="user-card-roles">
            <el-tag v-for="role in row.roles" :key="role" class="role-tag" size="small">{{ role }}</el-tag>
          </div>
          <footer class="user-card-actions">
            <el-button link type="primary" :icon="View" @click="openViewDialog(row)">查看</el-button>
            <el-button link type="primary" :icon="Edit" @click="openEditDialog(row)">修改</el-button>
            <el-button link type="warning" :icon="Key" @click="openResetPasswordDialog(row)">重置密码</el-button>
            <el-button link type="danger" :icon="Delete" @click="deleteUserRow(row)">删除</el-button>
          </footer>
        </article>
      </div>

      <div class="pagination">
        <el-pagination
            background
            layout="total, sizes, prev, pager, next"
            v-model:current-page="queryParam.pageNum"
            v-model:page-size="queryParam.pageSize"
            :page-sizes="[10, 20, 50]"
            :total="pageInfo.total"
            @change="fetchUserList" />
      </div>
    </template>

    <el-dialog v-model="userDialogVisible" :title="dialogTitle" :width="isMobile ? undefined : '560px'">
      <el-form ref="userFormRef" :model="userForm" :rules="userRules" :label-width="isMobile ? '74px' : '90px'" :disabled="dialogMode === 'view'">
        <el-form-item label="用户名" prop="userName">
          <el-input v-model="userForm.userName" :disabled="dialogMode === 'edit'" />
        </el-form-item>
        <el-form-item label="密码" prop="password" v-if="dialogMode === 'add'">
          <el-input v-model="userForm.password" type="password" show-password />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="userForm.nickName" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="userForm.phoneNumber" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="userForm.email" />
        </el-form-item>
        <el-form-item label="性别">
          <el-radio-group v-model="userForm.sex">
            <el-radio label="0">男</el-radio>
            <el-radio label="1">女</el-radio>
            <el-radio label="2">未知</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="userForm.status">
            <el-radio label="0">正常</el-radio>
            <el-radio label="1">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="角色" prop="roles">
          <el-checkbox-group v-model="userForm.roles">
            <el-checkbox v-for="role in roleOptions" :key="role" :label="role" />
          </el-checkbox-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="userDialogVisible = false">关闭</el-button>
        <el-button v-if="dialogMode !== 'view'" type="primary" @click="submitUserForm">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="passwordDialogVisible" title="重置密码" :width="isMobile ? undefined : '420px'">
      <el-form ref="passwordFormRef" :model="passwordForm" :rules="passwordRules" :label-width="isMobile ? '74px' : '90px'">
        <el-form-item label="用户">
          <el-input v-model="passwordForm.userName" disabled />
        </el-form-item>
        <el-form-item label="新密码" prop="password">
          <el-input v-model="passwordForm.password" type="password" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="passwordDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitResetPassword">确认重置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Edit, Key, Plus, Refresh, Search, View } from '@element-plus/icons-vue'
import { addUser, deleteUser, getUserDetail, getUserInfo, getUserInfoFromToken, listUsers, resetUserPassword, updateUser } from '@/api/user/user'
import { useBreakpoint } from '@/composables/useBreakpoint'

const router = useRouter()
const roleOptions = ['USER', 'ADMIN', 'FILE_ADMIN']
const { isMobile, isDesktop } = useBreakpoint()
const tableMaxHeight = computed(() => (isMobile.value ? '50vh' : 'calc(100vh - 240px)'))
const currentUserInfo = ref(getUserInfo())
const isAdmin = computed(() => (currentUserInfo.value.authorities || []).includes('ADMIN'))

const loading = ref(false)
const tableData = ref([])
const pageInfo = reactive({
  total: 0
})
const queryParam = reactive({
  userName: '',
  phoneNumber: '',
  status: '',
  pageNum: 1,
  pageSize: 10
})

const userDialogVisible = ref(false)
const passwordDialogVisible = ref(false)
const dialogMode = ref('add')
const userFormRef = ref()
const passwordFormRef = ref()
const userForm = reactive(defaultUserForm())
const passwordForm = reactive({
  userId: null,
  userName: '',
  password: ''
})

const dialogTitle = computed(() => {
  if (dialogMode.value === 'add') {
    return '新增用户'
  }
  if (dialogMode.value === 'edit') {
    return '修改用户'
  }
  return '查看用户'
})

const userRules = {
  userName: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  roles: [{ required: true, message: '请选择角色', trigger: 'change' }]
}
const passwordRules = {
  password: [{ required: true, message: '请输入新密码', trigger: 'blur' }]
}

onMounted(() => {
  getUserInfoFromToken().then(response => {
    currentUserInfo.value = response.data
    if (isAdmin.value) {
      fetchUserList()
    }
  })
})

function defaultUserForm() {
  return {
    userId: null,
    userName: '',
    nickName: '',
    email: '',
    phoneNumber: '',
    sex: '2',
    status: '0',
    roles: ['USER'],
    password: ''
  }
}

function assignUserForm(data) {
  Object.assign(userForm, defaultUserForm(), data || {})
  userForm.roles = data?.roles?.length ? [...data.roles] : ['USER']
  userForm.status = userForm.status || '0'
  userForm.sex = userForm.sex || '2'
  userForm.password = ''
}

function fetchUserList() {
  loading.value = true
  listUsers(queryParam).then(response => {
    pageInfo.total = response.data.total
    tableData.value = response.data.list
  }).finally(() => {
    loading.value = false
  })
}

function resetQuery() {
  Object.assign(queryParam, {
    userName: '',
    phoneNumber: '',
    status: '',
    pageNum: 1,
    pageSize: 10
  })
  fetchUserList()
}

function openAddDialog() {
  dialogMode.value = 'add'
  assignUserForm()
  userDialogVisible.value = true
}

function openViewDialog(row) {
  dialogMode.value = 'view'
  loadUserDetail(row.userId)
}

function openEditDialog(row) {
  dialogMode.value = 'edit'
  loadUserDetail(row.userId)
}

function loadUserDetail(userId) {
  getUserDetail(userId).then(response => {
    assignUserForm(response.data)
    userDialogVisible.value = true
  })
}

function submitUserForm() {
  userFormRef.value.validate(valid => {
    if (!valid) {
      return
    }
    const saveRequest = dialogMode.value === 'add' ? addUser(userForm) : updateUser(userForm)
    saveRequest.then(() => {
      ElMessage.success('保存成功')
      userDialogVisible.value = false
      fetchUserList()
    })
  })
}

function openResetPasswordDialog(row) {
  Object.assign(passwordForm, {
    userId: row.userId,
    userName: row.userName,
    password: ''
  })
  passwordDialogVisible.value = true
}

function submitResetPassword() {
  passwordFormRef.value.validate(valid => {
    if (!valid) {
      return
    }
    resetUserPassword({
      userId: passwordForm.userId,
      password: passwordForm.password
    }).then(() => {
      ElMessage.success('密码已重置')
      passwordDialogVisible.value = false
    })
  })
}

function deleteUserRow(row) {
  ElMessageBox.confirm(`确认删除用户「${row.userName}」？`, '系统提示', {
    confirmButtonText: '删除',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(() => {
    deleteUser(row.userId).then(() => {
      ElMessage.success('删除成功')
      fetchUserList()
    })
  })
}
</script>

<style scoped>
.user-management {
  height: 100%;
  padding: var(--space-4);
  box-sizing: border-box;
  background: var(--color-bg-base);
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: var(--space-3);
  margin-bottom: var(--space-3);
}

.query-form {
  flex: 1;
}

.status-select {
  width: min(160px, 100%);
}

.role-tag {
  margin-right: var(--space-1);
}

.pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-3);
}

/* ---------- Mobile cards ---------- */
.user-cards {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.user-empty {
  padding: var(--space-12) var(--space-4);
  text-align: center;
  color: var(--color-text-tertiary);
}

.user-card {
  background: var(--color-bg-surface);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-md);
  padding: var(--space-3) var(--space-4);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.user-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--space-2);
}

.user-card-name {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  flex: 1 1 auto;
  min-width: 0;
  word-break: break-all;
}

.user-card-nick {
  color: var(--color-text-tertiary);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-regular);
}

.user-card-meta {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: var(--line-height-relaxed);
}

.user-card-email {
  word-break: break-all;
}

.user-card-roles {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-1);
}

.user-card-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  justify-content: flex-end;
  border-top: 1px dashed var(--color-border-subtle);
  padding-top: var(--space-2);
  margin-top: var(--space-1);
}

@media (max-width: 640px) {
  .user-management {
    padding: var(--space-3);
  }

  .toolbar {
    display: block;
  }

  .toolbar > .el-button {
    width: 100%;
    margin-bottom: var(--space-3);
  }

  .query-form :deep(.el-form-item) {
    margin-right: 0;
    width: 100%;
  }
}
</style>
