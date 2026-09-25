<template>
  <ContentPanel>
    <div class="search-form">
      <el-input
        clearable
        placeholder="请输入用户ID或群组ID"
        v-model="contactId"
        size="large"
        @keydown.enter="search"
      ></el-input>
      <div class="search-btn iconfont icon-search" @click="search"></div>
    </div>

    <div v-if="searchResult && Object.keys(searchResult).length > 0" class="search-result-panel">
      <div class="search-result">
        <span class="contact-type">{{ contactTypeName }}</span>
        <UserBaseInfo
          :userInfo="searchResult"
          :showArea="searchResult.contactType == 'USER'"
        ></UserBaseInfo>
      </div>
      <div class="op-btn" v-if="searchResult.contactId != userInfoStore.getInfo().userId">
        <el-button
          type="primary"
          v-if="
            searchResult.status == null ||
            searchResult.status == 0 ||
            searchResult.status == 2 ||
            searchResult.status == 3 ||
            searchResult.status == 4
          "
          @click="applyContact"
          >{{ searchResult.contactType == 'USER' ? '添加到联系人' : '申请加入群组' }}</el-button
        >
        <el-button type="primary" v-if="searchResult.status == 1" @click="sendMessage"
          >发消息</el-button
        >
        <span v-if="searchResult.status == 5 || searchResult.status == 6">对方拉黑了你</span>
      </div>
    </div>
    <div v-if="!searchResult" class="no-data">没有搜索到任何结果</div>

    <!-- 按昵称 / 备注 / 分组搜索我的好友 -->
    <div class="keyword-search">
      <div class="keyword-form">
        <el-input
          clearable
          placeholder="按昵称、备注或分组搜索我的好友"
          v-model="keyword"
          size="large"
          @keydown.enter="searchByKeyword"
        ></el-input>
        <div class="search-btn iconfont icon-search" @click="searchByKeyword"></div>
      </div>

      <div class="keyword-result" v-if="keywordSearched">
        <div class="result-title">好友（{{ keywordList.length }}）</div>
        <div
          v-for="item in keywordList"
          :key="item.contactId"
          class="keyword-item"
          @click="sendMessage2Contact(item)"
        >
          <Avatar :userId="item.contactId" :width="36" :borderRadius="4"></Avatar>
          <div class="keyword-meta">
            <div class="keyword-name">{{ item.remark || item.contactName || item.contactId }}</div>
            <div class="keyword-sub">
              <span v-if="item.groupName">分组：{{ item.groupName }}</span>
              <span v-else-if="item.remark">昵称：{{ item.contactName }}</span>
              <span v-else>{{ item.contactId }}</span>
            </div>
          </div>
        </div>
        <div v-if="keywordList.length == 0" class="no-data">没有匹配的好友</div>
      </div>
    </div>
  </ContentPanel>
  <SearchAdd ref="searchAddRef" @reload="resetForm"></SearchAdd>
</template>

<script setup>
import SearchAdd from './SearchAdd.vue'
import { ref, reactive, getCurrentInstance, nextTick, computed } from 'vue'
const { proxy } = getCurrentInstance()
import { useUserInfoStore } from '@/stores/UserInfoStore'
const userInfoStore = useUserInfoStore()

import { useRouter } from 'vue-router'
const router = useRouter()

const contactTypeName = computed(() => {
  if (userInfoStore.getInfo().userId === searchResult.value.contactId) {
    return '自己'
  }
  if (searchResult.value.contactType == 'USER') {
    return '用户'
  }
  if (searchResult.value.contactType == 'GROUP') {
    return '群组'
  }
})

const contactId = ref()
const searchResult = ref({})
const search = async () => {
  let result = await proxy.Request({
    url: proxy.Api.search,
    params: {
      contactId: contactId.value
    }
  })
  if (!result) {
    return
  }
  searchResult.value = result.data
}

//添加到通讯录
const searchAddRef = ref()
const applyContact = async () => {
  searchAddRef.value.show(searchResult.value)
}

const resetForm = () => {
  searchResult.value = {}
  contactId.value = undefined
}

const sendMessage = () => {
  router.push({ path: '/chat', query: { chatId: searchResult.value.contactId } })
}

// ===== 按昵称 / 备注 / 分组搜索好友 =====
const keyword = ref()
const keywordList = ref([])
const keywordSearched = ref(false)

const searchByKeyword = async () => {
  const key = (keyword.value || '').trim()
  if (!key) {
    proxy.Message.warning('请输入搜索关键词')
    return
  }
  const result = await proxy.Request({
    url: proxy.Api.searchContactByKeyword,
    params: { keyword: key },
    showLoading: false,
    showError: false
  })
  if (!result) {
    return
  }
  keywordList.value = result.data || []
  keywordSearched.value = true
}

const sendMessage2Contact = (item) => {
  router.push({
    path: '/chat',
    query: { chatId: item.contactId, timestamp: new Date().getTime() }
  })
}
</script>

<style lang="scss" scoped>
.search-form {
  padding-top: 50px;
  display: flex;
  align-items: center;
  :deep(.el-input__wrapper) {
    border-radius: 4px 0px 0px 4px;
    border-right: none;
  }
  .search-btn {
    background: #07c160;
    color: #fff;
    line-height: 40px;
    width: 80px;
    text-align: center;
    border-radius: 0px 5px 5px 0px;
    cursor: pointer;
    &:hover {
      background: #0dd36c;
    }
  }
}
.no-data {
  padding: 30px 0px;
}
.search-result-panel {
  .search-result {
    padding: 30px 20px 20px 20px;
    background: #fff;
    border-radius: 5px;
    margin-top: 10px;
    position: relative;
    .contact-type {
      position: absolute;
      left: 0px;
      top: 0px;
      background: #2cb6fe;
      padding: 2px 5px;
      color: #fff;
      border-radius: 5px 0px 0px 0px;
      font-size: 12px;
    }
  }
  .op-btn {
    border-radius: 5px;
    margin-top: 10px;
    padding: 10px;
    background: #fff;
    text-align: center;
  }
}

.keyword-search {
  margin-top: 20px;

  .keyword-form {
    display: flex;
    align-items: center;
    :deep(.el-input__wrapper) {
      border-radius: 4px 0px 0px 4px;
      border-right: none;
    }
    .search-btn {
      background: #07c160;
      color: #fff;
      line-height: 40px;
      width: 80px;
      text-align: center;
      border-radius: 0px 5px 5px 0px;
      cursor: pointer;
      &:hover {
        background: #0dd36c;
      }
    }
  }

  .keyword-result {
    margin-top: 10px;
    background: #fff;
    border-radius: 5px;
    padding: 10px;

    .result-title {
      font-size: 12px;
      color: #999;
      padding-bottom: 6px;
    }

    .keyword-item {
      display: flex;
      align-items: center;
      padding: 8px 6px;
      border-radius: 4px;
      cursor: pointer;

      &:hover {
        background: #f7f7f7;
      }

      .keyword-meta {
        margin-left: 10px;

        .keyword-name {
          font-size: 14px;
          color: #1a1a1a;
        }

        .keyword-sub {
          font-size: 12px;
          color: #999;
          margin-top: 2px;
        }
      }
    }
  }
}
</style>
