package com.xiaozhi.dialogue.llm.memory;

import com.xiaozhi.entity.SysDevice;
import com.xiaozhi.entity.SysMessage;
import com.xiaozhi.entity.SysRole;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Conversation 是一个 对应于 sys_message 表的，但高于 sys_message 的一个抽象实体。
 * deviceID, roleID, sessionID, 实质构成了一次Conversation的全局唯一ID。这个ID必须final 的。
 * 在关系型数据库里，可以将deviceID, roleID, sessionID 建一个组合索引，注意顺序sessionID放在最后。
 * 在图数据库里， conversation label的节点，连接 device节点、role节点。
 * deviceID与roleID本质上不是Conversation的真正属性，而是外键，代表连接的2个对象。
 * 只有sessionID是真正挂在Conversation的属性。
 *
 */
public class Conversation {
    private final SysDevice device;
    private final SysRole role;
    private final String sessionId;

    protected List<Message> messages = new ArrayList<>();

    public Conversation(SysDevice device, SysRole role, String sessionId) {
        // final 属性的规范要求
        Assert.notNull(device, "device must not be null");
        Assert.notNull(role, "role must not be null");
        Assert.notNull(device.getDeviceId(), "deviceId must not be null");
        Assert.notNull(role.getRoleId(), "roleId must not be null");
        Assert.notNull(sessionId, "sessionId must not be null");
        this.device = device;
        this.role = role;
        this.sessionId = sessionId;
    }

    public SysDevice device() {
        return device;
    }
    public SysRole role() {
        return role;
    }

    public String sessionId() {
        return sessionId;
    }

    public List<Message> messages() {
        return messages;
    }

    public void clear(){
        messages.clear();
    }

    public void addMessage(UserMessage userMessage, Long userTimeMillis,AssistantMessage assistantMessage, Long assistantTimeMillis){
        messages.add(userMessage);
        messages.add(assistantMessage);
    }

    /**
     * 获取适用于放入prompt提示词的多轮消息列表。
     * userMessage 不会因调用此方法而入库（或进入记忆）
     * @param userMessage 必须且不为空。
     * @return 新的消息列表对象，避免污染原有的列表。
     */
    public List<Message> prompt(UserMessage userMessage){
        List<Message> newMessages = new ArrayList<>(this.messages);
        newMessages.add(userMessage);
        return newMessages;
    }

    /**
     * 将数据库记录的SysMessag转换为spring-ai的Message。
     *
     * @param messages
     * @return
     */
    public static List<Message> convert(List<SysMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }
        return messages.stream()
                .filter(message -> MessageType.ASSISTANT.getValue().equals(message.getSender())
                        || MessageType.USER.getValue().equals(message.getSender()))
                .map(message -> {
                    String role = message.getSender();
                    // 一般消息("messageType", "NORMAL");//默认为普通消息
                    Map<String, Object> metadata = Map.of("messageId", message.getMessageId(), "messageType",
                            message.getMessageType());
                    return switch (role) {
                        case "assistant" -> new AssistantMessage(message.getMessage(), metadata);
                        case "user" -> UserMessage.builder().text(message.getMessage()).metadata(metadata).build();
                        default -> throw new IllegalArgumentException("Invalid role: " + role);
                    };
                }).collect(Collectors.toList());
    }
}
