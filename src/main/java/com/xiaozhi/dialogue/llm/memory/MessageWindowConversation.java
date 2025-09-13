package com.xiaozhi.dialogue.llm.memory;

import com.xiaozhi.communication.common.SessionManager;
import com.xiaozhi.entity.SysDevice;
import com.xiaozhi.entity.SysMessage;
import com.xiaozhi.entity.SysRole;
import org.springframework.ai.chat.messages.*;

import org.springframework.util.StringUtils;

import java.util.*;

/**
 * 限定消息条数（消息窗口）的Conversation实现。根据不同的策略，可实现聊天会话的持久化、加载、清除等功能。
 */
public class MessageWindowConversation extends Conversation {
    // 历史记录默认限制数量
    public static final int DEFAULT_HISTORY_LIMIT = 10;
    private final ChatMemory chatMemory;
    private final int maxMessages;
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(MessageWindowConversation.class);


    private final SessionManager sessionManager;

    public MessageWindowConversation(SysDevice device, SysRole role, String sessionId, int maxMessages, ChatMemory chatMemory, SessionManager sessionManager){
        super(device, role, sessionId);
        this.maxMessages = maxMessages;
        this.chatMemory = chatMemory;
        this.sessionManager = sessionManager;
        logger.info("加载设备{}的普通消息(SysMessage.MESSAGE_TYPE_NORMAL)作为对话历史",device.getDeviceId());
        List<SysMessage> history = chatMemory.getMessages(device.getDeviceId(), SysMessage.MESSAGE_TYPE_NORMAL, maxMessages);
        super.messages.addAll(convert(history)) ;
    }

    public static class Builder {
        private SysDevice device;
        private SysRole role;
        private String sessionId;
        private int maxMessages;
        private ChatMemory chatMemory;
        private SessionManager sessionManager;

        public Builder device(SysDevice device) {
            this.device = device;
            return this;
        }

        public Builder role(SysRole role) {
            this.role = role;
            return this;
        }
        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder chatMemory(ChatMemory chatMemory) {
            this.chatMemory = chatMemory;
            return this;
        }

        public Builder sessionManager(SessionManager sessionManager) {
            this.sessionManager = sessionManager;
            return this;
        }

        public Builder maxMessages(int maxMessages) {
            this.maxMessages = maxMessages;
            return this;
        }

        public MessageWindowConversation build(){
            return new MessageWindowConversation(device,role,sessionId,maxMessages,chatMemory,sessionManager);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public void clear() {
        messages().clear();
        chatMemory.clearMessages(device().getDeviceId());
    }


    /**
     * 添加消息
     * 后续考虑：继承封装UserMessage和AssistantMessage,UserMessageWithTime,AssistantMessageWithTime
     * 后续考虑：将function 或者 mcp 的相关信息封装在AssistantMessageWithTime，来精细处理。或者根据元数据判断是function_call还是mcp调用
     * @param userMessage
     * @param userTimeMillis
     * @param assistantMessage
     * @param assistantTimeMillis
     */
    @Override
    public void addMessage(UserMessage userMessage,  Long userTimeMillis, AssistantMessage assistantMessage, Long assistantTimeMillis) {

        //boolean hasToolCalls = assistantMessage.hasToolCalls();
        // 检查元数据中是否包含工具调用标识
        String toolName = (String) assistantMessage.getMetadata().get("toolName");

        // 发生了工具调用，获取函数调用的名称，通过名称反查类型
        // String functionName = chatResponse.getMetadata().get("function_name");

        boolean hasToolCalls = StringUtils.hasText(toolName);

        // 非function消息才加入对话历史，避免调用混乱。
        // 这个逻辑面对更多的工具调用时，可能是值得商榷的。有些工具调用的结果直接作为AssistantMessage加入对话历史并不会影响对话效果。
        // 后续考虑：在XiaozhiToolCallingManager实现类里，包装出的AssistantMessage由工具来添加标识是否影响对话效果。
        if(!hasToolCalls){
            // 更新缓存
            messages().add(userMessage);
            messages().add(assistantMessage);
        }

        // 判断消息类型（不是spring-ai的消息类型），同一轮对话里UserMessage和AssistantMessage的messageType相同
        String messageType = hasToolCalls ? SysMessage.MESSAGE_TYPE_FUNCTION_CALL : SysMessage.MESSAGE_TYPE_NORMAL;
        String deviceId = device().getDeviceId();
        int roleId = role().getRoleId();
        // 如果本轮对话是function_call或mcp调用(最后一条信息的类型)，把用户的消息类型也修正为同样类型
        chatMemory.addMessage(deviceId, sessionId(), userMessage.getMessageType().getValue(), userMessage.getText(),
                roleId, messageType, userTimeMillis);

        String response = assistantMessage.getText();
        if (StringUtils.hasText(response)) {
            chatMemory.addMessage(deviceId, sessionId(), assistantMessage.getMessageType().getValue(), response,
                    roleId, messageType, assistantTimeMillis);
        }
    }

    @Override
    public List<Message> prompt(UserMessage userMessage) {
        String roleDesc = role().getRoleDesc();
        
        // 获取设备昵称并拼接提示词
        String deviceNickname = sessionManager.getDeviceNickname(device().getDeviceId());
        String enhancedPrompt = StringUtils.hasText(deviceNickname) ? 
            "我的名字是" + deviceNickname + "。" + (StringUtils.hasText(roleDesc) ? roleDesc : "") : 
            (StringUtils.hasText(roleDesc) ? roleDesc : "");
            
        SystemMessage systemMessage = new SystemMessage(enhancedPrompt);

        final var historyMessages = messages();
        while (historyMessages.size() > maxMessages) {
            historyMessages.remove(0);
        }

        List<Message> messages = new ArrayList<>();
        messages.add(systemMessage);
        messages.addAll(historyMessages);
        messages.add(userMessage);

        return messages;
    }

}
