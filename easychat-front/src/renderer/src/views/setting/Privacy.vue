<template>
  <ContentPanel :showTopBorder="true">
    <div v-loading="loading" class="privacy-wrap">
      <!-- ============ 1. 加我的方式 ============ -->
      <div class="part-item">
        <div class="part-title">加我的方式</div>
        <div class="part-content">
          <el-radio-group v-model="joinType" size="small" :disabled="joinTypeSaving" @change="joinTypeChange">
            <el-radio :label="0">直接加入</el-radio>
            <el-radio :label="1">加我时需验证</el-radio>
          </el-radio-group>
          <div class="tips">「直接加入」表示任何人可直接成为你的好友；「加我时需验证」表示需你同意后才成为好友。</div>
          <div v-if="origin.joinType == null" class="tips">
            当前账号未设置过（历史数据为 null），已按更保守的「加我时需验证」显示。
          </div>
        </div>
      </div>

      <!-- ============ 2. 朋友圈可见范围 ============ -->
      <div class="part-item">
        <div class="part-title">朋友圈可见范围</div>
        <div class="part-content">
          <el-radio-group v-model="momentVisibility" size="small" :disabled="momentSaving" @change="momentVisibilityChange">
            <el-radio v-for="opt in MOMENT_OPTIONS" :key="opt.value" :label="opt.value">
              {{ opt.label }}
            </el-radio>
          </el-radio-group>
          <div class="tips">
            决定你**新发布**朋友圈时的默认可见范围。单条仍可在发布时临时调整；本设置<b>不追溯</b>已发布的历史动态。
          </div>

          <div v-if="momentVisibility === 3" class="list-row">
            <div class="list-label">谁可以看（白名单）</div>
            <div class="list-values">
              <span v-if="whiteNames.length === 0" class="empty-hint">未指定</span>
              <el-tag v-for="n in whiteNames" :key="n" size="small" class="mr4">{{ n }}</el-tag>
            </div>
            <el-button size="small" @click="openPicker('white')">选择</el-button>
          </div>
          <div v-if="momentVisibility === 3 && whiteList.length === 0" class="tips warn">
            白名单为空：该设置下将<b>没有任何人</b>能看到你新发布的朋友圈。
          </div>

          <div v-if="momentVisibility === 4" class="list-row">
            <div class="list-label">不让谁看（黑名单）</div>
            <div class="list-values">
              <span v-if="blackNames.length === 0" class="empty-hint">未指定</span>
              <el-tag v-for="n in blackNames" :key="n" size="small" type="info" class="mr4">{{ n }}</el-tag>
            </div>
            <el-button size="small" @click="openPicker('black')">选择</el-button>
          </div>
          <div v-if="momentVisibility === 4 && blackList.length === 0" class="tips warn">
            黑名单为空：该设置下<b>所有人</b>都能看到你新发布的朋友圈。
          </div>
        </div>
      </div>

      <!-- ============ 3. 在线状态可见性 ============ -->
      <div class="part-item">
        <div class="part-title">在线状态</div>
        <div class="part-content">
          <el-switch v-model="onlineStatusVisible" :loading="onlineSaving" @change="onlineVisibleChange" />
          <div class="tips">
            关闭后好友将<b>立即</b>看不到你的在线状态，且此后的上线/忙碌/离线也不再推送给他们。
            重新开启会立即恢复展示。
          </div>
        </div>
      </div>

      <!-- ============ 4. 黑名单 ============ -->
      <div class="part-item">
        <div class="part-title">黑名单</div>
        <div class="part-content">
          <div class="tips">
            已被你拉黑的用户不会出现在好友列表中，也无法给你发送消息。解除后对方可重新向你发起好友申请。
          </div>
          <div v-if="blackListRows.length > 0" class="bl-list">
            <div class="bl-item" v-for="item in blackListRows" :key="item.contactId">
              <Avatar :width="38" :userId="item.contactId" :lastUpdateTime="item.lastUpdateTime" />
              <div class="bl-info">
                <div class="bl-name">{{ item.contactName || item.contactId }}</div>
                <div class="bl-time">拉黑于 {{ formatTime(item.lastUpdateTime) }}</div>
              </div>
              <el-button type="primary" size="small" plain :loading="removingId === item.contactId"
                @click="confirmRemove(item)">解除</el-button>
            </div>
          </div>
          <div v-else-if="blackListLoaded" class="empty-hint">黑名单为空</div>
        </div>
      </div>
    </div>

    <!-- 通用选人器（白/黑名单共用） -->
    <ContactPicker v-model="picker.show" :title="picker.title" :selected="picker.selected"
      @confirm="onPickerConfirm" />
  </ContentPanel>
</template>

<script setup>
/**
 * 隐私设置统一页
 *
 * 四个区块：加我的方式 / 朋友圈可见范围 / 在线状态可见性 / 黑名单。
 * 对齐微信「设置 → 隐私」的信息架构（ADR-003）。
 * 本页取代原分散位置：
 *   · 「加我的方式」原在 `/setting/userInfo`（账号设置），只读不可改 → 现可改并迁入
 *   · 「黑名单」原在独立页 `/setting/blacklist` → 迁入本页；两个旧路由保留 redirect
 *
 * 前三项一次性由 `getUserInfo` 回填（UserInfoVO 已带 4 个隐私字段）。
 * @since 2026-10-02 隐私设置（openspec/specs/privacy-settings）
 */
import { ref, reactive, getCurrentInstance } from 'vue'
import moment from 'moment'
import ContactPicker from '@/components/ContactPicker.vue'
import Avatar from '@/components/Avatar.vue'

const { proxy } = getCurrentInstance()

const MOMENT_OPTIONS = [
  { value: 0, label: '公开' },
  { value: 1, label: '仅好友可见' },
  { value: 2, label: '仅自己可见' },
  { value: 3, label: '自定义（白名单）' },
  { value: 4, label: '黑名单（除指定人外都可见）' }
]

const loading = ref(true)
// 保存前的原值，用于接口失败时回滚
const origin = reactive({ joinType: 1, momentVisibility: 0, onlineStatusVisible: 1 })

const joinType = ref(1)
const joinTypeSaving = ref(false)
const momentVisibility = ref(0)
const momentSaving = ref(false)
const onlineStatusVisible = ref(true)
const onlineSaving = ref(false)

const whiteList = ref([])
const blackList = ref([])
const friendNameMap = ref({})

const blackListRows = ref([])
const blackListLoaded = ref(false)
const removingId = ref('')

const picker = reactive({ show: false, target: '', title: '', selected: [] })

// 名单 id → 昵称（ContactPicker 返回 id，展示时映射成昵称）
const toNames = (ids) => ids.map((id) => friendNameMap.value[id] || id)
const whiteNames = () => toNames(whiteList.value)
const blackNames = () => toNames(blackList.value)

const formatTime = (ts) => (ts ? moment(Number(ts)).format('YYYY-MM-DD HH:mm') : '-')

// 解析后端存的 JSON 数组字符串（宽松解析，容忍历史脏数据）
const parseIdList = (raw) => {
  if (!raw) return []
  try {
    const arr = JSON.parse(raw)
    return Array.isArray(arr) ? arr.filter((x) => typeof x === 'string' && x) : []
  } catch (e) {
    return []
  }
}

const loadUserInfo = async () => {
  loading.value = true
  try {
    let result = await proxy.Request({ url: proxy.Api.getUserInfo })
    if (!result) return
    const info = result.data || {}
    joinType.value = info.joinType == null ? 1 : info.joinType
    origin.joinType = joinType.value
    momentVisibility.value = info.momentVisibility == null ? 0 : info.momentVisibility
    origin.momentVisibility = momentVisibility.value
    whiteList.value = parseIdList(info.momentVisibleList)
    blackList.value = parseIdList(info.momentInvisibleList)
    // onlineStatusVisible 为 0/1；兼容 null（历史数据）视为展示
    const vis = info.onlineStatusVisible == null ? 1 : info.onlineStatusVisible
    onlineStatusVisible.value = vis === 1
    origin.onlineStatusVisible = onlineStatusVisible.value
  } finally {
    loading.value = false
  }
}

const loadFriends = async () => {
  let result = await proxy.Request({
    url: proxy.Api.loadContact,
    showLoading: false,
    params: { contactType: 'USER' }
  })
  if (!result) return
  const map = {}
  ;(result.data || []).forEach((item) => {
    map[item.contactId] = item.contactName || item.contactId
  })
  friendNameMap.value = map
}

const loadBlackList = async () => {
  let result = await proxy.Request({ url: proxy.Api.loadBlackList })
  if (!result) {
    // 失败保留旧列表，不清空
    blackListLoaded.value = blackListRows.value.length > 0
    return
  }
  blackListRows.value = result.data || []
  blackListLoaded.value = true
}

loadUserInfo()
loadFriends()
loadBlackList()

// ============ 加我的方式 ============
const joinTypeChange = async (value) => {
  if (joinTypeSaving.value) return
  joinTypeSaving.value = true
  let result
  try {
    result = await proxy.Request({
      url: proxy.Api.updateJoinType,
      params: { joinType: value },
      showLoading: true
    })
  } finally {
    joinTypeSaving.value = false
  }
  if (!result) {
    joinType.value = origin.joinType
    return
  }
  origin.joinType = value
  proxy.Message.success('已更新加我的方式')
}

// ============ 朋友圈可见范围 ============
const momentVisibilityChange = async (value) => {
  if (momentSaving.value) return
  // 前端先拦一道：白/黑名单模式必须先选人，否则后端会 1001
  if (value === 3 && whiteList.value.length === 0) {
    proxy.Message.warning('请先选择白名单成员')
    momentVisibility.value = origin.momentVisibility
    picker.target = 'white'
    picker.title = '选择白名单成员'
    picker.selected = []
    picker.show = true
    return
  }
  if (value === 4 && blackList.value.length === 0) {
    proxy.Message.warning('请先选择黑名单成员')
    momentVisibility.value = origin.momentVisibility
    picker.target = 'black'
    picker.title = '选择黑名单成员'
    picker.selected = []
    picker.show = true
    return
  }
  await saveMomentPrivacy(value)
}

const saveMomentPrivacy = async (value, silent = false) => {
  momentSaving.value = true
  let result
  try {
    result = await proxy.Request({
      url: proxy.Api.updateMomentPrivacy,
      params: {
        momentVisibility: value,
        // 只在对应模式下提交名单，避免误清另一侧
        visibleList: value === 3 ? JSON.stringify(whiteList.value) : null,
        invisibleList: value === 4 ? JSON.stringify(blackList.value) : null
      },
      showLoading: !silent
    })
  } finally {
    momentSaving.value = false
  }
  if (!result) {
    momentVisibility.value = origin.momentVisibility
    return
  }
  origin.momentVisibility = value
  if (!silent) proxy.Message.success('已更新朋友圈可见范围')
}

const openPicker = (target) => {
  picker.target = target
  picker.title = target === 'white' ? '选择白名单成员' : '选择黑名单成员'
  picker.selected = target === 'white' ? whiteList.value : blackList.value
  picker.show = true
}

const onPickerConfirm = async (ids) => {
  if (picker.target === 'white') {
    whiteList.value = ids
  } else {
    blackList.value = ids
  }
  const target = momentVisibility.value
  if ((target === 3 && ids.length > 0) || (target === 4 && ids.length > 0)) {
    await saveMomentPrivacy(target, true)
  }
}

// ============ 在线状态可见性 ============
const onlineVisibleChange = async (value) => {
  if (onlineSaving.value) return
  onlineSaving.value = true
  let result
  try {
    result = await proxy.Request({
      url: proxy.Api.updateOnlineStatusVisible,
      params: { visible: value ? 1 : 0 },
      showLoading: true
    })
  } finally {
    onlineSaving.value = false
  }
  if (!result) {
    onlineStatusVisible.value = origin.onlineStatusVisible
    return
  }
  origin.onlineStatusVisible = value
  proxy.Message.success(value ? '好友将能看到你的在线状态' : '已隐藏你的在线状态')
}

// ============ 黑名单 ============
const confirmRemove = (item) => {
  proxy.Confirm({
    message: `确定将「${item.contactName || item.contactId}」移出黑名单？解除后对方可重新申请添加你。`,
    okText: '解除',
    showCancelBtn: true,
    okfun: async () => {
      removingId.value = item.contactId
      let result
      try {
        result = await proxy.Request({
          url: proxy.Api.removeBlackList,
          params: { contactId: item.contactId },
          showLoading: true
        })
      } finally {
        removingId.value = ''
      }
      if (!result) return
      blackListRows.value = blackListRows.value.filter((row) => row.contactId !== item.contactId)
      proxy.Message.success('已移出黑名单')
    }
  })
}
</script>

<style lang="scss" scoped>
.privacy-wrap {
  min-height: 200px;
}

.part-item {
  padding: 16px 4px;
  border-bottom: 1px solid var(--ec-border);

  &:last-child {
    border-bottom: none;
  }

  .part-title {
    margin-bottom: 10px;
    color: var(--ec-text-primary);
    font-size: 14px;
    font-weight: 600;
  }

  .tips {
    margin-top: 8px;
    color: var(--ec-text-secondary);
    font-size: 12px;
    line-height: 18px;

    &.warn {
      color: var(--el-color-warning);
    }
  }
}

.list-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;

  .list-label {
    flex-shrink: 0;
    color: var(--ec-text-secondary);
    font-size: 13px;
  }

  .list-values {
    flex: 1;
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 4px;
    min-height: 24px;
  }
}

.mr4 {
  margin-right: 4px;
}

.empty-hint {
  color: var(--ec-text-secondary);
  font-size: 12px;
}

.bl-list {
  margin-top: 10px;
}

.bl-item {
  display: flex;
  align-items: center;
  padding: 8px 0;
  border-bottom: 1px solid var(--ec-border);
  &:last-child {
    border-bottom: none;
  }

  .bl-info {
    flex: 1;
    margin-left: 10px;
    overflow: hidden;
  }

  .bl-name {
    color: var(--ec-text-primary);
    font-size: 13px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .bl-time {
    margin-top: 2px;
    color: var(--ec-text-secondary);
    font-size: 11px;
  }
}
</style>
