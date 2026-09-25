<template>
  <div>
    <Dialog
      :show="dialogConfig.show"
      :title="dialogConfig.title"
      :buttons="dialogConfig.buttons"
      width="660px"
      @close="closeDialog"
    >
      <el-transfer
        v-model="selectedIds"
        :titles="['全部会话', '转发到']"
        :format="{
          noChecked: '${total}',
          hasChecked: '${checked}/${total}'
        }"
        :data="dataList"
        :props="{ key: 'contactId', label: 'contactName' }"
        filterable
        :filter-method="search"
      >
        <template #default="{ option }">
          <div class="select-item">
            <div class="avatar">
              <AvatarBase
                :userId="option.contactId"
                :width="30"
                :borderRadius="5"
                :showDetail="false"
              >
              </AvatarBase>
            </div>
            <div class="nick-name">{{ option.contactName }}</div>
          </div>
        </template>
      </el-transfer>
    </Dialog>
  </div>
</template>

<script setup>
import AvatarBase from '@/components/AvatarBase.vue'
import { ref, reactive, getCurrentInstance } from 'vue'
const { proxy } = getCurrentInstance()

const dialogConfig = ref({
  show: false,
  title: '转发到',
  buttons: [
    {
      type: 'primary',
      text: '确定',
      click: () => {
        submitData()
      }
    }
  ]
})

const dataList = ref([])
const selectedIds = ref([])
// 回调：选中的联系人对象数组
let callbackFun = null

const search = (query, item) => {
  return (item.contactName || '').toLowerCase().includes((query || '').toLowerCase())
}

const loadContact = async () => {
  const [userResult, groupResult] = await Promise.all([
    proxy.Request({
      url: proxy.Api.loadContact,
      showLoading: false,
      params: { contactType: 'USER' }
    }),
    proxy.Request({
      url: proxy.Api.loadContact,
      showLoading: false,
      params: { contactType: 'GROUP' }
    })
  ])
  const list = []
  if (userResult && userResult.data) {
    userResult.data.forEach((item) => {
      list.push({
        contactId: item.contactId,
        contactName: item.contactName || item.contactId,
        contactType: 0
      })
    })
  }
  if (groupResult && groupResult.data) {
    groupResult.data.forEach((item) => {
      list.push({
        contactId: item.contactId,
        contactName: item.contactName || item.contactId,
        contactType: 1
      })
    })
  }
  dataList.value = list
}

const show = async (callback) => {
  callbackFun = callback
  selectedIds.value = []
  dialogConfig.value.show = true
  await loadContact()
}

const closeDialog = () => {
  dialogConfig.value.show = false
}

const submitData = () => {
  if (selectedIds.value.length == 0) {
    proxy.Message.warning('请选择转发的会话')
    return
  }
  const selected = dataList.value.filter((item) => selectedIds.value.includes(item.contactId))
  dialogConfig.value.show = false
  if (callbackFun) {
    callbackFun(selected)
  }
}

defineExpose({ show })
</script>

<style lang="scss" scoped>
.el-transfer {
  width: 100%;
  display: block !important;
  :deep(.el-transfer-panel) {
    width: 280px;
  }
}
.select-item {
  display: flex;
  .avatar {
    width: 30px;
    height: 30px;
  }
  .nick-name {
    flex: 1;
    margin-left: 5px;
  }
}
</style>
