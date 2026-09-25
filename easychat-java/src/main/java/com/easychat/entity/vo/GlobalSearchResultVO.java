package com.easychat.entity.vo;

import com.easychat.entity.po.ChatMessage;
import com.easychat.entity.po.UserContact;

import java.util.ArrayList;
import java.util.List;

/**
 * 全局搜索结果（跨会话消息 + 联系人 + 群组）
 */
public class GlobalSearchResultVO {

    /** 命中的聊天消息 */
    private List<ChatMessage> messageList = new ArrayList<>();

    /** 命中的好友联系人 */
    private List<UserContact> contactList = new ArrayList<>();

    /** 命中的群组 */
    private List<UserContact> groupList = new ArrayList<>();

    public List<ChatMessage> getMessageList() {
        return messageList;
    }

    public void setMessageList(List<ChatMessage> messageList) {
        this.messageList = messageList;
    }

    public List<UserContact> getContactList() {
        return contactList;
    }

    public void setContactList(List<UserContact> contactList) {
        this.contactList = contactList;
    }

    public List<UserContact> getGroupList() {
        return groupList;
    }

    public void setGroupList(List<UserContact> groupList) {
        this.groupList = groupList;
    }
}
