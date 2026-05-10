<template>
  <el-tabs v-model="activeTab" class="rss-detail-tabs">
    <el-tab-pane label="条目历史" name="items">
      <div class="rss-toolbar">
        <div class="rss-toolbar-filters">
          <el-input
              v-model="itemQuery.keyword"
              placeholder="筛选标题或描述"
              clearable
              @change="getRssItems()" />
          <el-select v-model="itemQuery.matchState" placeholder="匹配状态" clearable @change="getRssItems()">
            <el-option label="已匹配" :value="1" />
            <el-option label="未匹配" :value="0" />
          </el-select>
          <el-select v-model="itemQuery.downloadState" placeholder="下载状态" clearable @change="getRssItems()">
            <el-option label="未下载" :value="0" />
            <el-option label="已下载" :value="1" />
            <el-option label="下载失败" :value="2" />
          </el-select>
        </div>
        <div class="rss-toolbar-actions">
          <el-button @click="refreshRss" :loading="refreshLoading">刷新</el-button>
          <el-button type="primary" :disabled="selectedItemList.length === 0" @click="batchDownloadSelectedItems">
            批量下载
          </el-button>
        </div>
      </div>

      <el-table
          class="rss-table-desktop"
          v-loading="itemLoading"
          :data="rssItemPage.list"
          max-height="58svh"
          style="width: 100%"
          @selection-change="selectedItemList = $event">
        <el-table-column type="selection" width="45" />
        <el-table-column prop="title" label="标题" min-width="180">
          <template #default="scope">
            <el-tooltip effect="dark" :content="scope.row.title" placement="bottom">
              <div class="table-text">{{ scope.row.title }}</div>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column prop="publishTime" label="发布时间" min-width="150" />
        <el-table-column prop="matchState" label="匹配" min-width="80">
          <template #default="scope">
            <el-tag v-if="scope.row.matchState === 1" type="success" size="small">已匹配</el-tag>
            <el-tag v-else type="info" size="small">未匹配</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="downloadState" label="下载" min-width="90">
          <template #default="scope">
            <el-tag v-if="scope.row.downloadState === 1" type="success" size="small">已下载</el-tag>
            <el-tooltip v-else-if="scope.row.downloadState === 2" :content="scope.row.errorMessage" placement="bottom">
              <el-tag type="danger" size="small">失败</el-tag>
            </el-tooltip>
            <el-tag v-else type="info" size="small">未下载</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="torrentUrl" label="磁力链接" min-width="180">
          <template #default="scope">
            <el-tooltip effect="dark" :content="scope.row.torrentUrl" placement="bottom">
              <div class="table-text">{{ scope.row.torrentUrl }}</div>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column prop="author" label="发布人" min-width="100" />
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="scope">
            <el-button link type="primary" @click="batchDownloadItems([scope.row.itemId])">
              {{ scope.row.downloadState === 1 ? '重新下载' : '下载' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="rss-mobile-list" v-loading="itemLoading">
        <div class="rss-mobile-card" v-for="item in rssItemPage.list" :key="item.itemId">
          <div class="mobile-card-title">{{ item.title }}</div>
          <div class="mobile-card-meta">
            <el-tag v-if="item.matchState === 1" type="success" size="small">已匹配</el-tag>
            <el-tag v-else type="info" size="small">未匹配</el-tag>
            <el-tooltip v-if="item.downloadState === 2" :content="item.errorMessage" placement="bottom">
              <el-tag type="danger" size="small">失败</el-tag>
            </el-tooltip>
            <el-tag v-else-if="item.downloadState === 1" type="success" size="small">已下载</el-tag>
            <el-tag v-else type="info" size="small">未下载</el-tag>
          </div>
          <div class="mobile-card-time">{{ item.publishTime || '无发布时间' }}</div>
          <div class="mobile-card-url">{{ item.torrentUrl }}</div>
          <div class="mobile-card-actions">
            <el-checkbox
                :model-value="isItemSelected(item.itemId)"
                @change="toggleItemSelection(item, $event)">
              选择
            </el-checkbox>
            <el-button link type="primary" @click="batchDownloadItems([item.itemId])">
              {{ item.downloadState === 1 ? '重新下载' : '下载' }}
            </el-button>
          </div>
        </div>
      </div>

      <div class="rss-table-tail">
        <el-pagination
            background
            layout="prev, pager, next"
            v-model:current-page="itemQuery.pageNum"
            v-model:page-size="itemQuery.pageSize"
            :total="rssItemPage.total"
            @change="getRssItems(false)" />
      </div>
    </el-tab-pane>

    <el-tab-pane label="匹配规则" name="rules">
      <div class="rss-toolbar">
        <div />
        <el-button type="primary" @click="openRuleDialog()">添加规则</el-button>
      </div>
      <el-table class="rss-table-desktop" v-loading="ruleLoading" :data="rssRuleList" max-height="58svh" style="width: 100%">
        <el-table-column prop="ruleName" label="规则名称" min-width="120" />
        <el-table-column prop="includeKeywords" label="包含关键词" min-width="150" />
        <el-table-column prop="excludeKeywords" label="排除关键词" min-width="150" />
        <el-table-column prop="regex" label="正则" min-width="160" />
        <el-table-column prop="downloadPath" label="下载目录" min-width="160">
          <template #default="scope">
            <div class="table-text">{{ scope.row.downloadPath }}</div>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="70">
          <template #default="scope">
            <el-switch v-model="scope.row.enabled" disabled />
          </template>
        </el-table-column>
        <el-table-column label="自动下载" width="90">
          <template #default="scope">
            <el-switch v-model="scope.row.autoDownload" disabled />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="scope">
            <el-button link type="primary" @click="openRuleDialog(scope.row)">修改</el-button>
            <el-button link type="danger" @click="deleteRule(scope.row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="rss-mobile-list" v-loading="ruleLoading">
        <div class="rss-mobile-card" v-for="rule in rssRuleList" :key="rule.ruleId">
          <div class="mobile-card-title">{{ rule.ruleName || '未命名规则' }}</div>
          <div class="mobile-card-meta">
            <el-tag :type="rule.enabled ? 'success' : 'info'" size="small">{{ rule.enabled ? '启用' : '停用' }}</el-tag>
            <el-tag :type="rule.autoDownload ? 'warning' : 'info'" size="small">{{ rule.autoDownload ? '自动下载' : '手动下载' }}</el-tag>
          </div>
          <div class="mobile-card-field" v-if="rule.includeKeywords">包含：{{ rule.includeKeywords }}</div>
          <div class="mobile-card-field" v-if="rule.excludeKeywords">排除：{{ rule.excludeKeywords }}</div>
          <div class="mobile-card-field" v-if="rule.regex">正则：{{ rule.regex }}</div>
          <div class="mobile-card-url">{{ rule.downloadPath }}</div>
          <div class="mobile-card-actions">
            <el-button link type="primary" @click="openRuleDialog(rule)">修改</el-button>
            <el-button link type="danger" @click="deleteRule(rule)">删除</el-button>
          </div>
        </div>
      </div>
    </el-tab-pane>
  </el-tabs>

  <el-dialog v-model="ruleDialogVisible" :title="ruleForm.ruleId ? '修改规则' : '添加规则'" width="min(560px, 92vw)">
    <el-form :model="ruleForm" ref="ruleFormRef" label-width="92px" :rules="ruleRules">
      <el-form-item label="规则名称" prop="ruleName">
        <el-input v-model="ruleForm.ruleName" />
      </el-form-item>
      <el-form-item label="包含关键词">
        <el-input v-model="ruleForm.includeKeywords" placeholder="多个关键词用逗号分隔" />
      </el-form-item>
      <el-form-item label="排除关键词">
        <el-input v-model="ruleForm.excludeKeywords" placeholder="多个关键词用逗号分隔" />
      </el-form-item>
      <el-form-item label="正则">
        <el-input v-model="ruleForm.regex" />
      </el-form-item>
      <el-form-item label="下载目录" prop="downloadPath">
        <file-path-selector v-model="ruleForm.downloadPath" :open-type="0" />
      </el-form-item>
      <el-form-item label="状态">
        <el-checkbox v-model="ruleForm.enabled">启用</el-checkbox>
        <el-checkbox v-model="ruleForm.autoDownload">自动下载</el-checkbox>
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="ruleForm.remark" type="textarea" :rows="2" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="ruleDialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="ruleSubmitLoading" @click="submitRule">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import {onMounted, ref} from "vue";
import {
  addRssRule as addRssRuleApi,
  batchDownloadRssItems as batchDownloadRssItemsApi,
  getRssItems as getRssItemsApi,
  getRssRuleList as getRssRuleListApi,
  refreshRss as refreshRssApi,
  removeRssRule as removeRssRuleApi,
  updateRssRule as updateRssRuleApi,
} from '@/api/fileServer/rss';
import {ElMessage, ElMessageBox} from "element-plus";
import FilePathSelector from "@/components/fileServer/FilePathSelector.vue";

const props = defineProps({
  rssInfo: {
    type: Object,
    required: true,
  }
});

const activeTab = ref('items');
const itemLoading = ref(false);
const refreshLoading = ref(false);
const selectedItemList = ref([]);
const itemQuery = ref({
  rssId: props.rssInfo.rssId,
  keyword: '',
  matchState: null,
  downloadState: null,
  pageNum: 1,
  pageSize: 10
});
const rssItemPage = ref({
  list: [],
  total: 0
});

const ruleLoading = ref(false);
const ruleSubmitLoading = ref(false);
const ruleDialogVisible = ref(false);
const ruleFormRef = ref();
const rssRuleList = ref([]);
const ruleForm = ref(getEmptyRuleForm());
const ruleRules = {
  ruleName: [
    { required: true, message: '规则名称不能为空', trigger: 'blur' }
  ],
  downloadPath: [
    { required: true, message: '下载目录不能为空', trigger: 'blur' }
  ]
};

function getEmptyRuleForm() {
  return {
    rssId: props.rssInfo.rssId,
    ruleName: '',
    includeKeywords: '',
    excludeKeywords: '',
    regex: '',
    downloadPath: props.rssInfo.downloadPath,
    enabled: true,
    autoDownload: false,
    remark: ''
  };
}

const getRssItems = (resetPage = true) => {
  if (resetPage) {
    itemQuery.value.pageNum = 1;
  }
  itemLoading.value = true;
  getRssItemsApi(itemQuery.value).then((response) => {
    rssItemPage.value = response.data;
  }).finally(() => {
    itemLoading.value = false;
  });
};

const refreshRss = () => {
  refreshLoading.value = true;
  refreshRssApi({rssId: props.rssInfo.rssId}).then(() => {
    ElMessage.success("RSS订阅已刷新");
    getRssItems();
    getRssRuleList();
  }).finally(() => {
    refreshLoading.value = false;
  });
};

const batchDownloadSelectedItems = () => {
  batchDownloadItems(selectedItemList.value.map(item => item.itemId));
};

const isItemSelected = (itemId) => {
  return selectedItemList.value.some((item) => item.itemId === itemId);
};

const toggleItemSelection = (item, selected) => {
  if (selected) {
    if (!isItemSelected(item.itemId)) {
      selectedItemList.value = [...selectedItemList.value, item];
    }
    return;
  }
  selectedItemList.value = selectedItemList.value.filter((selectedItem) => selectedItem.itemId !== item.itemId);
};

const batchDownloadItems = (itemIdList) => {
  itemLoading.value = true;
  batchDownloadRssItemsApi({itemIdList}).then(() => {
    ElMessage.success("已添加到下载列表");
    getRssItems(false);
  }).finally(() => {
    itemLoading.value = false;
  });
};

const getRssRuleList = () => {
  ruleLoading.value = true;
  getRssRuleListApi({rssId: props.rssInfo.rssId}).then((response) => {
    rssRuleList.value = response.data;
  }).finally(() => {
    ruleLoading.value = false;
  });
};

const openRuleDialog = (ruleInfo) => {
  ruleForm.value = ruleInfo ? {...ruleInfo} : getEmptyRuleForm();
  ruleDialogVisible.value = true;
};

const submitRule = () => {
  ruleFormRef.value.validate((valid) => {
    if (!valid) {
      return;
    }
    ruleSubmitLoading.value = true;
    const request = ruleForm.value.ruleId ? updateRssRuleApi : addRssRuleApi;
    request(ruleForm.value).then(() => {
      ElMessage.success("规则已保存");
      ruleDialogVisible.value = false;
      getRssRuleList();
    }).finally(() => {
      ruleSubmitLoading.value = false;
    });
  });
};

const deleteRule = (ruleInfo) => {
  ElMessageBox.confirm('是否确定删除当前匹配规则？', '提示', {
    confirmButtonText: '确认',
    cancelButtonText: '取消',
    type: 'warning',
  }).then(() => {
    return removeRssRuleApi({ruleId: ruleInfo.ruleId});
  }).then(() => {
    ElMessage.success("规则已删除");
    getRssRuleList();
  });
};

onMounted(() => {
  getRssItems();
  getRssRuleList();
});
</script>

<style scoped>
.rss-detail-tabs {
  min-height: 60vh;
  min-height: 60dvh;
}

.rss-toolbar {
  display: flex;
  justify-content: space-between;
  gap: var(--space-3);
  margin-bottom: var(--space-3);
}

.rss-toolbar-filters {
  display: grid;
  grid-template-columns: minmax(180px, 260px) 140px 140px;
  gap: var(--space-2);
}

.rss-toolbar-actions {
  display: flex;
  gap: var(--space-2);
}

.rss-mobile-list {
  display: none;
}

.table-text {
  width: 100%;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
  overflow: hidden;
  text-overflow: ellipsis;
  word-break: break-all;
}

.rss-table-tail {
  display: flex;
  justify-content: flex-end;
  padding-top: var(--space-3);
}

@media (max-width: 640px) {
  .rss-toolbar {
    flex-direction: column;
  }

  .rss-toolbar-filters {
    grid-template-columns: 1fr;
  }

  .rss-toolbar-actions {
    display: grid;
    grid-template-columns: 1fr 1fr;
  }

  .rss-toolbar-actions .el-button {
    margin-left: 0;
  }

  .rss-table-desktop {
    display: none;
  }

  .rss-mobile-list {
    display: grid;
    gap: var(--space-3);
  }

  .rss-mobile-card {
    border: 1px solid var(--color-border-subtle);
    border-radius: var(--radius-md);
    padding: var(--space-3);
    background: var(--color-bg-surface);
  }

  .mobile-card-title {
    font-weight: var(--font-weight-medium);
    line-height: var(--line-height-tight);
    word-break: break-all;
    color: var(--color-text-primary);
    font-size: var(--font-size-sm);
  }

  .mobile-card-meta {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-1);
    padding-top: var(--space-2);
  }

  .mobile-card-time,
  .mobile-card-field {
    color: var(--color-text-tertiary);
    font-size: var(--font-size-xs);
    padding-top: var(--space-2);
    word-break: break-all;
  }

  .mobile-card-url {
    color: var(--color-text-tertiary);
    font-size: var(--font-size-xs);
    padding-top: var(--space-2);
    word-break: break-all;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }

  .mobile-card-actions {
    align-items: center;
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
    padding-top: var(--space-3);
  }

  .mobile-card-actions .el-button {
    margin-left: 0;
  }
}
</style>
