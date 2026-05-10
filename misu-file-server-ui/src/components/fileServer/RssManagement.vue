<template>
  <div class="rss-management-header">
    <div class="rss-management-header-choose">
      <el-select
          v-model="rssQueryParam.state"
          placeholder="是否可用"
          class="rss-state-select"
          placement="bottom"
          clearable
          @change="getRssInfoList()"
      >
        <el-option
            v-for="item in rssStateOption"
            :key="item.value"
            :label="item.label"
            :value="item.value"
        />
      </el-select>
    </div>
    <div class="rss-management-header-actions">
      <el-button type="primary" @click="addRssInfoVisible = true">{{ isMobile ? '添加订阅' : '添加rss订阅' }}</el-button>
    </div>
  </div>

  <div class="rss-search">
    <el-input v-model="rssQueryParam.keyword" @change="getRssInfoList" placeholder="筛选关键字" clearable>
      <template #append>
        <el-button @click="getRssInfoList" :icon="Search" />
      </template>
    </el-input>
  </div>

  <!-- 桌面端：表格 -->
  <div v-if="isDesktop" class="rss-table">
    <el-table v-loading="getRssInfoListLoading"
              :data="rssInfoList.list"
              style="width: 100%">
      <el-table-column prop="rssName" label="订阅名称" min-width="80">
        <template #default="scope">
          <el-tooltip
              class="box-item"
              effect="dark"
              :content="scope.row.rssName"
              placement="bottom">
            <div class="table-text">
              <span style="word-break: break-all;">{{ scope.row.rssName }}</span>
            </div>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column prop="rssUrl" label="订阅链接" min-width="100">
        <template #default="scope">
          <el-tooltip
              class="box-item"
              effect="dark"
              :content="scope.row.rssUrl"
              placement="bottom">
            <div class="table-text">
              <span style="word-break: break-all;">{{ scope.row.rssUrl }}</span>
            </div>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column prop="state" label="是否可用"  min-width="80">
        <template #default="scope">
          {{ formatRssState(scope.row.state) }}
        </template>
      </el-table-column>
      <el-table-column prop="downloadPath" label="订阅下载目录" min-width="80"/>
      <el-table-column label="操作" min-width="60" fixed="right">
        <template #default="scope">
          <div class="table-option">
            <div><el-button link type="primary" @click="openRssTorrentListDialog(scope.row)">详情</el-button></div>
            <div><el-button link type="primary" @click="openRssInfoDialog(scope.row)">修改</el-button></div>
            <div><el-button link type="danger" @click="deleteRss(scope.row)">删除</el-button></div>
          </div>
        </template>
      </el-table-column>
    </el-table>
    <div class="rss-table-tail">
      <el-pagination background
                     layout="prev, pager, next"
                     v-model:current-page="rssQueryParam.pageNum"
                     v-model:page-size="rssQueryParam.pageSize"
                     :total="rssInfoList.total"
                     :pager-count=5
                     @change="getRssInfoList()"/>
    </div>
  </div>

  <!-- 移动端：卡片 -->
  <div v-else class="rss-cards" v-loading="getRssInfoListLoading">
    <div v-if="!getRssInfoListLoading && (!rssInfoList.list || rssInfoList.list.length === 0)" class="rss-empty">
      暂无订阅
    </div>
    <article v-for="rss in rssInfoList.list" :key="rss.rssId" class="rss-card">
      <header class="rss-card-header">
        <h3 class="rss-card-title">{{ rss.rssName }}</h3>
        <span class="rss-card-state" :class="`rss-card-state-${rss.state}`">{{ formatRssState(rss.state) }}</span>
      </header>
      <p class="rss-card-meta">下载到：{{ rss.downloadPath }}</p>
      <p class="rss-card-url">{{ rss.rssUrl }}</p>
      <footer class="rss-card-actions">
        <el-button link type="primary" @click="openRssTorrentListDialog(rss)">详情</el-button>
        <el-button link type="primary" @click="openRssInfoDialog(rss)">修改</el-button>
        <el-button link type="danger" @click="deleteRss(rss)">删除</el-button>
      </footer>
    </article>
    <div v-if="rssInfoList.total > rssQueryParam.pageSize" class="rss-cards-tail">
      <el-pagination background
                     small
                     layout="prev, pager, next"
                     v-model:current-page="rssQueryParam.pageNum"
                     v-model:page-size="rssQueryParam.pageSize"
                     :total="rssInfoList.total"
                     :pager-count="5"
                     @change="getRssInfoList()"/>
    </div>
  </div>

  <el-dialog
      v-model="addRssInfoVisible"
      title="添加rss订阅">
    <div v-loading="addRssLoading">
      <el-form :model="addRssInfo"
               ref="addRssFormRef"
               :rules="rssRules"
               label-width="auto">
        <el-form-item label="rss订阅名称" prop="rssName">
          <el-input v-model="addRssInfo.rssName"/>
        </el-form-item>
        <el-form-item label="订阅下载的目录" prop="downloadPath">
          <file-path-selector v-model="addRssInfo.downloadPath" :open-type='0'/>
        </el-form-item>
        <el-form-item label="rss订阅链接" prop="rssUrl">
          <el-input :autosize="{ minRows: 4 }"
                    type="textarea"
                    v-model="addRssInfo.rssUrl"/>
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input :autosize="{ minRows: 4 }"
                    type="textarea"
                    v-model="addRssInfo.remark"/>
        </el-form-item>
      </el-form>
      <div style="display: flex; justify-content: center;">
        <el-button @click="addRssInfoVisible = false">取消</el-button>
        <el-button type="primary" @click="addRss()">添加</el-button>
      </div>
    </div>
  </el-dialog>

  <el-dialog
      v-model="updateRssVisible"
      title="修改rss订阅">
    <el-form :model="updateRssInfo"
             v-loading="updateRssLoading"
             ref="updateRssFormRef"
             :rules="rssRules"
             label-width="auto">
      <el-form-item label="rss订阅名称" prop="rssName">
        <el-input v-model="updateRssInfo.rssName"/>
      </el-form-item>
      <el-form-item label="订阅下载的目录" prop="downloadPath">
        <file-path-selector v-model="updateRssInfo.downloadPath" :open-type='0'/>
      </el-form-item>
      <el-form-item prop="rssUrl">
        <template v-slot:label>
          <span>
            <el-tooltip content="订阅链接无法修改，如果不可用，请删除后重新添加" placement="bottom">
              <el-icon :size="16" style="vertical-align: middle;"><QuestionFilled /></el-icon>
            </el-tooltip>
            rss订阅链接
          </span>
        </template>
        <el-input disabled
                  :autosize="{ minRows: 4 }"
                  type="textarea"
                  v-model="updateRssInfo.rssUrl"/>
      </el-form-item>
      <el-form-item label="备注" prop="remark">
        <el-input :autosize="{ minRows: 4 }"
                  type="textarea"
                  v-model="updateRssInfo.remark"/>
      </el-form-item>
    </el-form>
    <div style="display: flex; justify-content: center;">
      <el-button @click="updateRssVisible = false">取消</el-button>
      <el-button type="primary" @click="updateRss()">修改</el-button>
    </div>
  </el-dialog>

  <el-dialog
      v-model="rssDetailVisible"
      v-if="rssDetailVisible"
      title="rss订阅详情"
      :width="isMobile ? undefined : '92%'"
      :fullscreen="isMobile">
      <rss-torrent-detail
          :rss-info="rssDetailSelect" />
  </el-dialog>
</template>

<script setup>
import {onMounted, onBeforeUnmount, ref, onUnmounted} from "vue";
import {
  getRssList as getRssListApi,
  addRss as addRssApi,
  updateRss as updateRssApi,
  removeRss as removeRssApi
} from '@/api/fileServer/rss';
import {ElMessage, ElMessageBox} from "element-plus";
import FilePathSelector from "@/components/fileServer/FilePathSelector.vue";
import RssTorrentDetail from "@/components/fileServer/RssTorrentDetail.vue";
import {QuestionFilled, Search} from "@element-plus/icons-vue";
import FileUpload from "@/components/fileServer/FileUpload.vue";
import { useBreakpoint } from '@/composables/useBreakpoint';

const { isMobile, isDesktop } = useBreakpoint();

const rssStateOption = ref([
  {label: "未知", value: 0},
  {label: "可用", value: 1},
  {label: "不可用", value: 99}
])

// rss订阅列表
const getRssInfoListLoading = ref(false);
const rssQueryParam = ref({
  state: null,
  pageNum: 1,
  pageSize: 10
})
const rssInfoList = ref({
  "total": 0,
  "totalPages": 0,
  "pageSize": 0,
  "pageNumber": 0,
  "list": []
});

const rssRules = {
  rssName: [
    { required: true, message: 'rss订阅名称不能为空', trigger: 'blur' }
  ],
  downloadPath: [
    { required: true, message: 'rss订阅保存目录不能为空', trigger: 'blur' }
  ],
  rssUrl: [
    { required: true, message: 'rss订阅链接不能为空', trigger: 'blur' },
    {
      pattern: /^https?:\/\/.*$/,
      message: '请输入有效的订阅链接',
      trigger: ['blur', 'change']
    }
  ],
}

//添加rss订阅
const addRssInfoVisible = ref(false);
const addRssLoading = ref(false);
const addRssFormRef = ref();
const addRssInfo = ref({
  rssName: '',
  downloadPath: '/',
  rssUrl: ''
});

//更新rss订阅
const updateRssVisible = ref(false);
const updateRssLoading = ref(false)
const updateRssInfo = ref({});
const updateRssFormRef = ref();

//rss详情
const rssDetailVisible = ref(false);
const rssDetailSelect = ref({});

//添加torrent链接
const addRss = () => {
  addRssFormRef.value.validate((valid, fields) => {
    if (valid) {
      addRssLoading.value = true;
      addRssApi(addRssInfo.value).then((response) => {
        ElMessage.success("添加成功");
        addRssInfo.value = {
          rssName: '',
          downloadPath: '/',
          rssUrl: ''
        };
        addRssInfoVisible.value = false;
        getRssInfoList();
      }).finally(() => {
        addRssLoading.value = false;
      })
    }
  })
}

//更新rss订阅信息
const updateRss = () => {
  updateRssFormRef.value.validate((valid, fields) => {
    if (valid) {
      updateRssLoading.value = true;
      updateRssApi(updateRssInfo.value).then((response) => {
        ElMessage.success("修改成功");
        updateRssInfo.value = {
          rssName: '',
          downloadPath: '/',
          rssUrl: ''
        };
        updateRssVisible.value = false;
        getRssListApi();
      }).finally(() => {
        updateRssLoading.value = false;
      })
    }
  })
}

//获取rss订阅列表
const getRssInfoList = (needLoading = true) => {
  if (needLoading) {
    getRssInfoListLoading.value = true;
  }
  getRssListApi(rssQueryParam.value).then((response) => {
    rssInfoList.value = response.data;
  }).finally(() => {
    getRssInfoListLoading.value = false;
  })
}

//更新rss订阅
const openRssInfoDialog = (rssInfo) => {
  updateRssInfo.value = rssInfo;
  updateRssVisible.value = true;
}

//rss订阅获取的磁力链接列表
const openRssTorrentListDialog = (rssInfo) => {
  rssDetailSelect.value = rssInfo;
  rssDetailVisible.value = true;
}

const deleteRss = (rssInfo) => {
  ElMessageBox.confirm(
      '是否确定删除当前rss订阅链接？',
      '是否确定',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning',
        dangerouslyUseHTMLString: true,
      }
  ).then(() => {
    const removeRssRequest = {
      rssId: rssInfo.rssId
    }
    removeRssApi(removeRssRequest).then((response) => {
      ElMessage.success("删除成功");
      getRssInfoList();
    })
  }).catch((error) => {
    return false;
  })
}

const formatRssState = (rssState) => {
  if (rssState === 0) {
    return "未知";
  } else if (rssState === 1) {
    return "可用";
  } else if (rssState === 99) {
    return "不可用";
  }
}

onMounted(() => {
  getRssInfoList();
})

onBeforeUnmount(() => {
})

</script>

<style scoped>
.rss-management-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.rss-management-header-choose {
  display: flex;
  align-items: center;
  flex: 1 1 auto;
  min-width: 0;
}

.rss-state-select {
  width: min(160px, 100%);
}

.rss-management-header-actions {
  display: flex;
  gap: var(--space-2);
}

.rss-search {
  width: 100%;
  padding-top: var(--space-3);
}

.table-text {
  width: 100%;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 4;
  overflow: hidden;
  text-overflow: ellipsis;
}

.table-option {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-1);
}

.rss-table {
  padding-top: var(--space-3);
}

.rss-table-tail {
  padding-top: var(--space-3);
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
}

/* ---------- Mobile cards ---------- */
.rss-cards {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding-top: var(--space-3);
}

.rss-empty {
  padding: var(--space-12) var(--space-4);
  text-align: center;
  color: var(--color-text-tertiary);
}

.rss-card {
  background: var(--color-bg-surface);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-md);
  padding: var(--space-3) var(--space-4);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.rss-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--space-2);
}

.rss-card-title {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  word-break: break-all;
  flex: 1 1 auto;
}

.rss-card-state {
  flex-shrink: 0;
  padding: 2px var(--space-2);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-xs);
  background: var(--color-bg-muted);
  color: var(--color-text-secondary);
}

.rss-card-state-1 {
  background: var(--color-success-soft, #e7f0d6);
  color: var(--color-success);
}

.rss-card-state-99 {
  background: var(--color-danger-soft, #fbe2dc);
  color: var(--color-danger);
}

.rss-card-meta {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.rss-card-url {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
  word-break: break-all;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.rss-card-actions {
  display: flex;
  gap: var(--space-2);
  justify-content: flex-end;
  margin-top: var(--space-2);
}

.rss-cards-tail {
  display: flex;
  justify-content: center;
  margin-top: var(--space-3);
}
</style>