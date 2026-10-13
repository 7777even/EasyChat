// 语音/视频通话 WebRTC 封装（full-mesh P2P，媒体不经服务器）
// 仅负责 RTCPeerConnection 生命周期与本地媒体采集；
// 信令帧的收发由调用方（useCallStore）经主进程 IPC（sendCallFrame）完成。
// onSignal 回调签名：(peerId, signalType, payload)，payload 含 { sdp } 或 { candidate }。

let localStream = null
const peers = new Map() // peerId -> RTCPeerConnection

// ⚠ 刻意**不再内置**公共 STUN 兜底（ADR-003，2026-10-13）。
//   原先这里与后端 CallService 各硬编码一份 stun.l.google.com:19302 ——
//   两处各持一份必然漂移，且依赖境外公共服务（可达性不可控）。
//   现 iceServers 一律由服务端随 call_invite / call_accept 引导帧下发；
//   未下发时用空数组，让浏览器只走 host/srflx 候选（不尝试连任何外部 STUN）。
const buildConfig = (iceServers) => ({
  iceServers: Array.isArray(iceServers) ? iceServers : []
})

// 获取本地音视频流（单例缓存，重复调用不重复请求设备）
export const getLocalStream = async () => {
  if (!localStream) {
    localStream = await navigator.mediaDevices.getUserMedia({ audio: true, video: true })
  }
  return localStream
}

// 释放全部连接与设备
export const stopAll = () => {
  if (localStream) {
    localStream.getTracks().forEach((t) => t.stop())
    localStream = null
  }
  peers.forEach((pc) => {
    try { pc.close() } catch (e) { /* ignore */ }
  })
  peers.clear()
}

const ensureTrack = (pc) => {
  if (!localStream) return
  localStream.getTracks().forEach((t) => {
    const exists = pc.getSenders().find((s) => s.track && s.track.kind === t.kind)
    if (!exists) pc.addTrack(t, localStream)
  })
}

const createPeer = (peerId, iceServers, onRemoteStream, onSignal) => {
  const pc = new RTCPeerConnection(buildConfig(iceServers))
  pc.onicecandidate = (e) => {
    if (e.candidate) onSignal(peerId, 'candidate', { candidate: e.candidate })
  }
  pc.ontrack = (e) => {
    if (e.streams && e.streams[0]) onRemoteStream(peerId, e.streams[0])
  }
  ensureTrack(pc)
  peers.set(peerId, pc)
  return pc
}

// 新成员向某对端发起 offer（full-mesh：每个新成员负责建到自己到所有人的连接）
export const createOfferTo = async (peerId, iceServers, onRemoteStream, onSignal) => {
  let pc = peers.get(peerId)
  if (!pc) pc = createPeer(peerId, iceServers, onRemoteStream, onSignal)
  const offer = await pc.createOffer()
  await pc.setLocalDescription(offer)
  return offer
}

// 收到对端 offer：建连（若需要）、设置远端描述、采集本地轨道、回 answer
export const handleRemoteOffer = async (peerId, sdp, iceServers, onRemoteStream, onSignal) => {
  let pc = peers.get(peerId)
  if (!pc) pc = createPeer(peerId, iceServers, onRemoteStream, onSignal)
  await pc.setRemoteDescription(new RTCSessionDescription({ type: 'offer', sdp }))
  const answer = await pc.createAnswer()
  await pc.setLocalDescription(answer)
  return answer
}

export const handleRemoteAnswer = async (peerId, sdp) => {
  const pc = peers.get(peerId)
  if (pc) await pc.setRemoteDescription(new RTCSessionDescription({ type: 'answer', sdp }))
}

export const handleRemoteCandidate = async (peerId, candidate) => {
  const pc = peers.get(peerId)
  if (pc && candidate) {
    try {
      await pc.addIceCandidate(new RTCIceCandidate(candidate))
    } catch (e) {
      console.warn('addIceCandidate 失败', e)
    }
  }
}

export const setMuted = (muted) => {
  if (localStream) localStream.getAudioTracks().forEach((t) => (t.enabled = !muted))
}

export const setCameraOff = (off) => {
  if (localStream) localStream.getVideoTracks().forEach((t) => (t.enabled = !off))
}

export const getPeerIds = () => Array.from(peers.keys())
