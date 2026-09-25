package com.easychat.entity.query;


import java.util.List;

/**
 * 聊天消息表参数
 */
public class ChatMessageQuery extends BaseParam {


    /**
     * 消息自增ID
     */
    private Long messageId;

    /**
     * 会话ID
     */
    private String sessionId;

    private String sessionIdFuzzy;

    /**
     * 消息类型
     */
    private Integer messageType;
    
    /**
     * 消息类型列表
     */
    private Integer[] messageTypeList;

    /**
     * 消息内容
     */
    private String messageContent;

    private String messageContentFuzzy;

    /**
     * 发送人ID
     */
    private String sendUserId;

    private String sendUserIdFuzzy;

    /**
     * 发送人昵称
     */
    private String sendUserNickName;

    private String sendUserNickNameFuzzy;

    /**
     * 发送时间
     */
    private Long sendTime;
    
    /**
     * 发送时间开始
     */
    private Long sendTimeStart;
    
    /**
     * 发送时间结束
     */
    private Long sendTimeEnd;

    /**
     * 接收联系人ID
     */
    private String contactId;

    private String contactIdFuzzy;

    /**
     * 联系人类型 0:单聊 1:群聊
     */
    private Integer contactType;

    /**
     * 文件大小
     */
    private Long fileSize;

    /**
     * 文件名
     */
    private String fileName;

    private String fileNameFuzzy;

    /**
     * 文件类型
     */
    private Integer fileType;

    /**
     * 状态 0:正在发送 1:已发送
     */
    private Integer status;

    /**
     * 会话内序列号起始（查 seq > seqStart 的消息，用于 SYNC 补推）
     */
    private Long seqStart;

    /**
     * 是否只查 seq 不为 NULL 的消息
     */
    private Boolean seqNotNull;


    private List<String> contactIdList;

    /**
     * 会话ID列表（全局搜索：限定在当前用户参与的会话内）
     */
    private List<String> sessionIdList;

    public List<String> getSessionIdList() {
        return sessionIdList;
    }

    public void setSessionIdList(List<String> sessionIdList) {
        this.sessionIdList = sessionIdList;
    }

    /**
     * 消息ID Long 列表（用于按 messageId 批量查询）
     */
    private List<Long> messageIdLongList;

    private Long lastReceiveTime;

    /**
     * message_id &lt; messageIdLt：用于历史消息向上翻页（云端漫游）
     */
    private Long messageIdLt;

    /**
     * message_id &gt;= messageIdGe：用于定位到某条消息并取其所在页
     */
    private Long messageIdGe;

    /**
     * 被 @ 的用户 ID（精确匹配 at_user_ids 包含该 ID）
     */
    private String atUserId;

    public Long getMessageIdLt() {
        return messageIdLt;
    }

    public void setMessageIdLt(Long messageIdLt) {
        this.messageIdLt = messageIdLt;
    }

    public Long getMessageIdGe() {
        return messageIdGe;
    }

    public void setMessageIdGe(Long messageIdGe) {
        this.messageIdGe = messageIdGe;
    }

    public String getAtUserId() {
        return atUserId;
    }

    public void setAtUserId(String atUserId) {
        this.atUserId = atUserId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    public Long getMessageId() {
        return this.messageId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getSessionId() {
        return this.sessionId;
    }

    public void setSessionIdFuzzy(String sessionIdFuzzy) {
        this.sessionIdFuzzy = sessionIdFuzzy;
    }

    public String getSessionIdFuzzy() {
        return this.sessionIdFuzzy;
    }

    public void setMessageType(Integer messageType) {
        this.messageType = messageType;
    }

    public Integer getMessageType() {
        return this.messageType;
    }
    
    public Integer[] getMessageTypeList() {
        return messageTypeList;
    }

    public void setMessageTypeList(Integer[] messageTypeList) {
        this.messageTypeList = messageTypeList;
    }

    public void setMessageContent(String messageContent) {
        this.messageContent = messageContent;
    }

    public String getMessageContent() {
        return this.messageContent;
    }

    public void setMessageContentFuzzy(String messageContentFuzzy) {
        this.messageContentFuzzy = messageContentFuzzy;
    }

    public String getMessageContentFuzzy() {
        return this.messageContentFuzzy;
    }

    public void setSendUserId(String sendUserId) {
        this.sendUserId = sendUserId;
    }

    public String getSendUserId() {
        return this.sendUserId;
    }

    public void setSendUserIdFuzzy(String sendUserIdFuzzy) {
        this.sendUserIdFuzzy = sendUserIdFuzzy;
    }

    public String getSendUserIdFuzzy() {
        return this.sendUserIdFuzzy;
    }

    public void setSendUserNickName(String sendUserNickName) {
        this.sendUserNickName = sendUserNickName;
    }

    public String getSendUserNickName() {
        return this.sendUserNickName;
    }

    public void setSendUserNickNameFuzzy(String sendUserNickNameFuzzy) {
        this.sendUserNickNameFuzzy = sendUserNickNameFuzzy;
    }

    public String getSendUserNickNameFuzzy() {
        return this.sendUserNickNameFuzzy;
    }

    public void setSendTime(Long sendTime) {
        this.sendTime = sendTime;
    }

    public Long getSendTime() {
        return this.sendTime;
    }
    
    public Long getSendTimeStart() {
        return sendTimeStart;
    }

    public void setSendTimeStart(Long sendTimeStart) {
        this.sendTimeStart = sendTimeStart;
    }

    public Long getSendTimeEnd() {
        return sendTimeEnd;
    }

    public void setSendTimeEnd(Long sendTimeEnd) {
        this.sendTimeEnd = sendTimeEnd;
    }

    public void setContactId(String contactId) {
        this.contactId = contactId;
    }

    public String getContactId() {
        return this.contactId;
    }

    public void setContactIdFuzzy(String contactIdFuzzy) {
        this.contactIdFuzzy = contactIdFuzzy;
    }

    public String getContactIdFuzzy() {
        return this.contactIdFuzzy;
    }

    public void setContactType(Integer contactType) {
        this.contactType = contactType;
    }

    public Integer getContactType() {
        return this.contactType;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public Long getFileSize() {
        return this.fileSize;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileName() {
        return this.fileName;
    }

    public void setFileNameFuzzy(String fileNameFuzzy) {
        this.fileNameFuzzy = fileNameFuzzy;
    }

    public String getFileNameFuzzy() {
        return this.fileNameFuzzy;
    }

    public void setFileType(Integer fileType) {
        this.fileType = fileType;
    }

    public Integer getFileType() {
        return this.fileType;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getStatus() {
        return this.status;
    }

    public Long getSeqStart() {
        return seqStart;
    }

    public void setSeqStart(Long seqStart) {
        this.seqStart = seqStart;
    }

    public Boolean getSeqNotNull() {
        return seqNotNull;
    }

    public void setSeqNotNull(Boolean seqNotNull) {
        this.seqNotNull = seqNotNull;
    }

    public List<String> getContactIdList() {
        return contactIdList;
    }

    public void setContactIdList(List<String> contactIdList) {
        this.contactIdList = contactIdList;
    }

    public Long getLastReceiveTime() {
        return lastReceiveTime;
    }

    public void setLastReceiveTime(Long lastReceiveTime) {
        this.lastReceiveTime = lastReceiveTime;
    }

    public List<Long> getMessageIdLongList() {
        return messageIdLongList;
    }

    public void setMessageIdLongList(List<Long> messageIdLongList) {
        this.messageIdLongList = messageIdLongList;
    }
}
