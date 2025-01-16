<template>
  <div class="rss-management-header">
    <div class="rss-management-header-choose">
      <el-select
          v-model="rssQueryParam.state"
          placeholder="是否可用"
          style="width: 110px"
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
      <el-button type="primary" @click="addRssInfoVisible = true">添加rss订阅</el-button>
    </div>
  </div>

  <div style="width: 100%; padding-top: 10px">
    <el-input v-model="rssQueryParam.keyword" @change="getRssInfoList" placeholder="筛选关键字" clearable>
      <template #append>
        <el-button @click="getRssInfoList" :icon="Search" />
      </template>
    </el-input>
  </div>

  <div class="rss-table">
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

  <el-dialog
      v-model="addRssInfoVisible"
      title="添加rss订阅"
      style="width: 90%;">
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
      title="修改rss订阅"
      style="width: 90%;">
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
      style="width: 95%;">
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
  display: flex; /* 使用 flexbox 排列子元素 */
  justify-content: space-between; /* 左侧是路径，右侧是按钮区域 */
  align-items: center; /* 垂直居中 */
}

.rss-management-header-choose {
  display: flex;
  align-items: center;
}

.rss-management-header-actions {
  display: flex; /* 用 flex 布局让按钮排成一行 */
  gap: 10px; /* 按钮之间的间距 */
  padding-left: 10px;
  padding-right: 10px;
}

.table-text {
  width: 100%;
  display: -webkit-box; /* 使用多行文本框 */
  -webkit-box-orient: vertical; /* 垂直排列 */
  -webkit-line-clamp: 4; /* 限制最多显示 2 行 */
  overflow: hidden; /* 隐藏超出文本 */
  text-overflow: ellipsis; /* 超出部分显示省略号 */
}

.table-option {
  display: flex; /* 启用flex布局 */
  flex-wrap: wrap; /* 启用自动换行 */
  justify-content: flex-start; /* 子项水平对齐方式 */
  align-items: flex-start; /* 子项垂直对齐方式 */
}

.rss-table {
  padding-top: 10px;
}

.rss-table-tail {
  padding-top: 10px;
  display: flex; /* 启用flex布局 */
  flex-wrap: wrap; /* 启用自动换行 */
  justify-content: flex-end; /* 子项水平对齐方式 */
}
</style>