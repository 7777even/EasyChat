import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import GroupFile from '@/views/chat/GroupFile.vue'
import { useGlobalInfoStore } from '@/stores/GlobalInfoStore'

/**
 * 群文件弹窗的**真实挂载**测试（DOM 级）。
 *
 * 该组件直接 import Request/Api/上传 API（非 proxy 注入），故用 vi.mock 模块级桩。
 *
 * 被测点：
 *   1. show(gid) → 拉列表：分页参数、total 渲染、空态文案
 *   2. formatSize 三档（B/KB/MB）
 *   3. fileTypeOf → 图片/视频/其他三类缩略图分支
 *   4. downloadUrl 拼接（localhost:localServerPort + fileId 编码）
 *   5. 删除：groupFileDelete 参数 + 刷新列表
 *   6. 上传：uploadFile(file, gid, fileType, {onProgress}) + 成功提示 + 刷新
 */

const requestMock = vi.fn()
vi.mock('@/utils/Request', () => ({
  default: (...args) => requestMock(...args)
}))
vi.mock('@/utils/Api', () => ({
  default: {
    groupFileList: '/group/file/list',
    groupFileDelete: '/group/file/delete'
  }
}))
const uploadFileMock = vi.fn()
vi.mock('@/utils/GroupFileChunkUploadApi', () => ({
  default: { uploadFile: (...args) => uploadFileMock(...args) }
}))
vi.mock('@/components/ShowLocalImage.vue', () => ({
  default: {
    name: 'ShowLocalImage',
    props: ['fileId', 'partType', 'fileType', 'width'],
    template: '<div class="show-local-image" :data-fileid="fileId" />'
  }
}))

const MessageStub = { success: vi.fn(), warning: vi.fn(), error: vi.fn() }

const ElDialogStub = {
  props: ['modelValue', 'title', 'width'],
  emits: ['close', 'update:modelValue'],
  template: '<div v-if="modelValue" class="el-dialog"><div class="dialog-title">{{ title }}</div><slot /></div>'
}
const ElButtonStub = {
  props: ['type', 'size', 'disabled'],
  emits: ['click'],
  template: '<button class="el-button" :disabled="disabled" @click="$emit(\'click\')"><slot /></button>'
}
const ElProgressStub = {
  props: ['percentage', 'strokeWidth'],
  template: '<div class="el-progress" :data-pct="percentage" />'
}

function mountPanel () {
  const pinia = createPinia()
  setActivePinia(pinia)
  const wrapper = mount(GroupFile, {
    global: {
      stubs: {
        'el-dialog': ElDialogStub,
        'el-button': ElButtonStub,
        'el-progress': ElProgressStub
      },
      plugins: [pinia],
      config: { globalProperties: { Message: MessageStub } }
    }
  })
  useGlobalInfoStore().setInfo('localServerPort', 9999)
  return wrapper
}

async function flush () {
  await new Promise((r) => setTimeout(r, 0))
  await new Promise((r) => setTimeout(r, 0))
}

const FILE_LIST = {
  code: 0,
  data: {
    totalCount: 3,
    list: [
      { id: 1, fileName: 'a.png', fileSize: 512, fileType: 0, filePath: 'g/a.png', uploadUserId: 'U_1', uploadUserNickName: '甲' },
      { id: 2, fileName: 'b.mp4', fileSize: 2048, fileType: 1, filePath: 'g/b.mp4', uploadUserId: 'U_2' },
      { id: 3, fileName: 'c.docx', fileSize: 1024 * 1024 * 3.5, fileType: 2, filePath: 'g/c.docx', uploadUserId: 'U_3' }
    ]
  }
}

beforeEach(() => {
  requestMock.mockReset()
  uploadFileMock.mockReset()
  MessageStub.success.mockClear()
})

describe('GroupFile.vue 真实挂载（DOM 级）', () => {
  it('show(gid)：带分页参数拉列表，渲染条目数与「共 N 个文件」', async () => {
    requestMock.mockResolvedValue(FILE_LIST)
    const wrapper = mountPanel()
    wrapper.vm.show('G1')
    await flush()

    expect(requestMock).toHaveBeenCalledTimes(1)
    expect(requestMock.mock.calls[0][0].url).toBe('/group/file/list')
    expect(requestMock.mock.calls[0][0].params).toEqual({
      groupId: 'G1',
      pageNo: 1,
      pageSize: 100
    })
    expect(wrapper.findAll('.file-item')).toHaveLength(3)
    expect(wrapper.find('.count-tip').text()).toBe('共 3 个文件')
    expect(wrapper.find('.file-name').text()).toBe('a.png')
    // 上传者昵称兜底
    const subs = wrapper.findAll('.file-sub').map((n) => n.text())
    expect(subs[0]).toContain('甲')
    expect(subs[1]).toContain('U_2')
  })

  it('列表为空 → 空态文案', async () => {
    requestMock.mockResolvedValue({ code: 0, data: { list: [], totalCount: 0 } })
    const wrapper = mountPanel()
    wrapper.vm.show('G1')
    await flush()
    expect(wrapper.find('.empty-tip').text()).toContain('还没有群文件')
    expect(wrapper.find('.count-tip').text()).toBe('共 0 个文件')
  })

  it('formatSize 三档：B / KB / MB', async () => {
    requestMock.mockResolvedValue({
      code: 0,
      data: {
        totalCount: 3,
        list: [
          { id: 1, fileName: 'x', fileSize: 512, fileType: 2, filePath: 'p1' },
          { id: 2, fileName: 'y', fileSize: 2048, fileType: 2, filePath: 'p2' },
          { id: 3, fileName: 'z', fileSize: 1024 * 1024 * 3.5, fileType: 2, filePath: 'p3' }
        ]
      }
    })
    const wrapper = mountPanel()
    wrapper.vm.show('G1')
    await flush()
    const subs = wrapper.findAll('.file-sub').map((n) => n.text())
    expect(subs[0]).toContain('512 B')
    expect(subs[1]).toContain('2.0 KB')
    expect(subs[2]).toContain('3.5 MB')
  })

  it('缩略图分支：图片走 ShowLocalImage，视频/其他走图标类名', async () => {
    requestMock.mockResolvedValue(FILE_LIST)
    const wrapper = mountPanel()
    wrapper.vm.show('G1')
    await flush()

    const thumbs = wrapper.findAll('.file-thumb')
    expect(thumbs[0].find('.show-local-image').exists()).toBe(true)
    expect(thumbs[0].find('.show-local-image').attributes('data-fileid')).toBe('g/a.png')
    expect(thumbs[1].find('.icon-video').exists()).toBe(true)
    expect(thumbs[2].find('.icon-file').exists()).toBe(true)
  })

  it('downloadUrl：localServerPort + fileId 编码 + partType=group', async () => {
    requestMock.mockResolvedValue(FILE_LIST)
    const wrapper = mountPanel()
    wrapper.vm.show('G1')
    await flush()
    const href = wrapper.find('.action-link').attributes('href')
    expect(href).toBe(
      'http://localhost:9999/file?fileId=' + encodeURIComponent('g/a.png') + '&partType=group&showCover=false'
    )
    expect(wrapper.find('.action-link').attributes('download')).toBe('a.png')
  })

  it('删除：groupFileDelete(groupId, fileId) → 已删除 → 刷新列表', async () => {
    requestMock
      .mockResolvedValueOnce(FILE_LIST)
      .mockResolvedValueOnce({ code: 0, data: null })
      .mockResolvedValueOnce({ code: 0, data: { totalCount: 2, list: FILE_LIST.data.list.slice(0, 2) } })
    const wrapper = mountPanel()
    wrapper.vm.show('G1')
    await flush()

    await wrapper.findAll('.action-link')[1].trigger('click')
    await flush()

    const del = requestMock.mock.calls[1][0]
    expect(del.url).toBe('/group/file/delete')
    expect(del.params).toEqual({ groupId: 'G1', fileId: 1 })
    expect(MessageStub.success).toHaveBeenCalledWith('已删除')
    // 删除后重新拉取
    expect(requestMock).toHaveBeenCalledTimes(3)
    expect(wrapper.findAll('.file-item')).toHaveLength(2)
  })

  it('上传：按扩展名识别 fileType，onProgress 更新进度，成功后提示并刷新', async () => {
    requestMock
      .mockResolvedValueOnce(FILE_LIST)
      .mockResolvedValueOnce({ code: 0, data: FILE_LIST.data })
    uploadFileMock.mockImplementation(async (file, gid, fileType, opts) => {
      opts.onProgress(50)
      opts.onProgress(100)
    })
    const wrapper = mountPanel()
    wrapper.vm.show('G1')
    await flush()

    const file = new File(['x'], '新图片.JPEG', { type: 'image/jpeg' })
    const input = wrapper.find('input[type="file"]').element
    Object.defineProperty(input, 'files', { value: [file], configurable: true })
    await wrapper.find('input[type="file"]').trigger('change')
    await flush()

    expect(uploadFileMock).toHaveBeenCalledTimes(1)
    const [f, gid, fileType, opts] = uploadFileMock.mock.calls[0]
    expect(f.name).toBe('新图片.JPEG')
    expect(gid).toBe('G1')
    expect(fileType).toBe(0) // jpeg 大小写不敏感 → 图片
    expect(typeof opts.onProgress).toBe('function')
    expect(MessageStub.success).toHaveBeenCalledWith('上传成功')
    // 上传成功后刷新列表
    expect(requestMock).toHaveBeenCalledTimes(2)
    // 上传结束 uploading 复位
    expect(wrapper.find('.upload-progress').exists()).toBe(false)
  })

  it('非图片/视频扩展名 → fileType=2', async () => {
    requestMock.mockResolvedValueOnce(FILE_LIST)
    uploadFileMock.mockResolvedValue(undefined)
    const wrapper = mountPanel()
    wrapper.vm.show('G1')
    await flush()

    const file = new File(['x'], 'report.pdf', { type: 'application/pdf' })
    const input = wrapper.find('input[type="file"]').element
    Object.defineProperty(input, 'files', { value: [file], configurable: true })
    await wrapper.find('input[type="file"]').trigger('change')
    await flush()
    expect(uploadFileMock.mock.calls[0][2]).toBe(2)
  })

  it('上传中：disabled + 进度条渲染（uploadingName）', async () => {
    requestMock.mockResolvedValueOnce(FILE_LIST)
    let release
    uploadFileMock.mockImplementation(
      () => new Promise((resolve) => { release = resolve })
    )
    const wrapper = mountPanel()
    wrapper.vm.show('G1')
    await flush()

    const file = new File(['x'], 'big.mp4', { type: 'video/mp4' })
    const input = wrapper.find('input[type="file"]').element
    Object.defineProperty(input, 'files', { value: [file], configurable: true })
    await wrapper.find('input[type="file"]').trigger('change')
    await wrapper.vm.$nextTick()

    expect(wrapper.find('.upload-progress').exists()).toBe(true)
    expect(wrapper.find('.upload-tip').text()).toBe('正在上传：big.mp4')
    // 布尔属性：Vue 序列为 disabled=""（空串即存在）
    expect(wrapper.find('.el-button').attributes('disabled')).toBe('')

    release()
    await flush()
    expect(wrapper.find('.upload-progress').exists()).toBe(false)
  })
})
