import { contextBridge, ipcRenderer } from 'electron'
import { electronAPI } from '@electron-toolkit/preload'
window.ipcRenderer = ipcRenderer;
// Custom APIs for renderer
const api = {
  /**
   * 发送正在输入状态帧
   * @param {string} contactId 对方用户 ID
   * @param {string} sessionId 会话 ID
   * @param {boolean} typing 是否正在输入
   */
  sendTypingStatus: (contactId, sessionId, typing) => {
    ipcRenderer.send('sendTypingStatus', { contactId, sessionId, typing });
  },
}

// Use `contextBridge` APIs to expose Electron APIs to
// renderer only if context isolation is enabled, otherwise
// just add to the DOM global.
if (process.contextIsolated) {
  try {
    contextBridge.exposeInMainWorld('electron', electronAPI)
    contextBridge.exposeInMainWorld('api', api)
  } catch (error) {
    console.error(error)
  }
} else {
  window.electron = electronAPI
  window.api = api
}
