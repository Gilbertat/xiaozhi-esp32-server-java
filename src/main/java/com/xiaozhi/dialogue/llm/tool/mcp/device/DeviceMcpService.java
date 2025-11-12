package com.xiaozhi.dialogue.llm.tool.mcp.device;

import com.xiaozhi.communication.common.ChatSession;
import com.xiaozhi.communication.domain.DeviceMcpMessage;
import com.xiaozhi.communication.domain.mcp.device.DeviceMcpPayload;
import com.xiaozhi.communication.domain.mcp.device.initialize.DeviceMcpClientInfo;
import com.xiaozhi.communication.domain.mcp.device.initialize.DeviceMcpInitialize;
import com.xiaozhi.communication.domain.mcp.device.initialize.DeviceMcpVision;
import com.xiaozhi.dialogue.llm.tool.ToolCallStringResultConverter;
import com.xiaozhi.utils.CmsUtils;
import com.xiaozhi.utils.JsonUtil;
import jakarta.annotation.Resource;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
public class DeviceMcpService {
    private static final Logger logger = LoggerFactory.getLogger(DeviceMcpService.class);

    @Resource
    private Environment environment;

    @Resource
    private CmsUtils cmsUtils;

    @Value("${xiaozhi.mcp:device:max.tools.count:32}")
    private static int maxToolsCount = 32; // 最大工具数量限制

    /**
     * 初始化设备端MCP工具列表
     *
     * @param chatSession
     */
    public void initialize(ChatSession chatSession) {
        //1、调用始化命令
        DeviceMcpMessage initResult = sendInitialize(chatSession);
        //根据调用结果进行处理
        if (initResult != null) {
            chatSession.getDeviceMcpHolder().setMcpInitialized(true);
        }
        if (chatSession.getDeviceMcpHolder().isMcpInitialized()) {
            //2、获取工具列表
            sendToolsList(chatSession);
        }
    }

    /**
     * 发送初始化命令
     *
     * @param chatSession
     * @return
     */
    protected DeviceMcpMessage sendInitialize(ChatSession chatSession) {
        DeviceMcpMessage message = new DeviceMcpMessage();
        message.setSessionId(chatSession.getSessionId());
        DeviceMcpPayload payload = new DeviceMcpPayload();
        payload.setId(chatSession.getDeviceMcpHolder().getMcpRequestId());
        payload.setMethod("initialize");

        DeviceMcpInitialize initialize = deviceMcpInitialize(chatSession);

        payload.setParams(initialize);
        message.setPayload(payload);

        DeviceMcpMessage result = sendMcpRequest(chatSession, message);
        if (result != null) {
            logger.debug("SessionId: {}, MCP initialized successfully", chatSession.getSessionId());
            return result;
        }
        return null;
    }

    /**
     * 摄像头视觉相关, 根据实际需要设置vision的属性
     */
    @NotNull
    private DeviceMcpInitialize deviceMcpInitialize(ChatSession chatSession) {
        // MCP初始化参数
        DeviceMcpInitialize initialize = new DeviceMcpInitialize();
        initialize.setClientInfo(new DeviceMcpClientInfo());

        DeviceMcpVision vision = new DeviceMcpVision();

        //VLChatController
        String url = cmsUtils.getServerAddress() + "/vl/chat";
        vision.setUrl(url);
        vision.setToken(chatSession.getSessionId());

        initialize.setCapabilities(Map.of(
                "vision", vision
        ));
        return initialize;
    }

    /**
     * 发送工具列表请求
     *
     * @param chatSession
     */
    private void sendToolsList(ChatSession chatSession) {
        DeviceMcpMessage message = new DeviceMcpMessage();
        message.setSessionId(chatSession.getSessionId());
        DeviceMcpPayload payload = new DeviceMcpPayload();
        payload.setId(chatSession.getDeviceMcpHolder().getMcpRequestId());
        payload.setMethod("tools/list");
        if (chatSession.getDeviceMcpHolder().getMcpCursor() != null) {
            payload.setParams(Map.of(
                    "cursor", chatSession.getDeviceMcpHolder().getMcpCursor()));
        } else {
            payload.setParams(Map.of(
                    "cursor", "")); // 初始请求时使用空字符串
        }
        message.setPayload(payload);

        DeviceMcpMessage result = sendMcpRequest(chatSession, message);
        if (result != null) {
            //处理工具的注册
            List<Map<String, Object>> tools = (List<Map<String, Object>>) result.getPayload().getResult().get("tools");
            Object nextCursor = result.getPayload().getResult().get("nextCursor");
            int toolsCount = chatSession.getToolCallbacks().size();
            if (tools.isEmpty() || (toolsCount + tools.size()) > maxToolsCount) {//工具数量超过限制，不再添加
                return;
            } else {
                for (Map<String, Object> tool : tools) {
                    //开始注册工具
                    String name = (String) tool.get("name");
                    String funcName = "mcp_" + name.replace(".", "_");
                    String funcDescription = (String) tool.get("description");
                    Object inputSchema = tool.get("inputSchema");

                    ToolCallback toolCallback = FunctionToolCallback
                            .builder(funcName, (Map<String, Object> params, ToolContext toolContext) -> {
                                DeviceMcpMessage request = new DeviceMcpMessage();
                                request.setSessionId(chatSession.getSessionId());

                                DeviceMcpPayload requestPayload = new DeviceMcpPayload();
                                requestPayload.setMethod("tools/call");
                                requestPayload.setId(chatSession.getDeviceMcpHolder().getMcpRequestId());
                                requestPayload.setParams(Map.of(
                                        "name", name,
                                        "arguments", params
                                ));

                                request.setPayload(requestPayload);
                                DeviceMcpMessage response = sendMcpRequest(chatSession, request);
                                if (response != null) {
                                    logger.debug("SessionId: {},  MCP function call response: {}", chatSession.getSessionId(), response);
                                    //空指针
                                    if (response.getPayload().getResult() == null) {
                                        return response.getPayload().getError().get("message");//返回结果
                                    }
                                    if ("false".equals(String.valueOf(response.getPayload().getResult().get("isError")))) {
                                        return response.getPayload().getResult().get("content");//返回结果
                                    } else {
                                        return response.getPayload().getError();
                                    }
                                } else {
                                    return "操作失败";
                                }
                            })
                            .toolMetadata(ToolMetadata.builder().returnDirect(false).build())// 设置返回值需要ai再处理
                            .description(funcDescription)
                            .inputSchema(JsonUtil.toJson(inputSchema))
                            .inputType(Map.class)
                            .toolCallResultConverter(ToolCallStringResultConverter.INSTANCE)
                            .build();
                    // 注册到当前会话的函数持有者
                    chatSession.getToolsSessionHolder().registerFunction(funcName, toolCallback);
                }
            }
            // 如果cursor不为空，则迭代调用
            if (nextCursor != null && !nextCursor.toString().isEmpty()) {
                // 如果有下一页游标，继续请求下一页
                chatSession.getDeviceMcpHolder().setMcpCursor(nextCursor.toString());
                sendToolsList(chatSession);
            } else {
                // 所有工具加载完成
                chatSession.getDeviceMcpHolder().setMcpCursor(null);
                logger.debug("SessionId: {}, mcp tools loaded successfully", chatSession.getSessionId());
            }
        }
    }

    public DeviceMcpMessage sendMcpRequest(ChatSession chatSession, DeviceMcpMessage mcpMessage) {
        return sendMcpRequestWithRetry(chatSession, mcpMessage);
    }

    /**
     * 发送MCP请求（带重试机制）
     */
    private DeviceMcpMessage sendMcpRequestWithRetry(ChatSession chatSession, DeviceMcpMessage mcpMessage) {
        final int maxRetries = 3; // 最多重试3次，总共4次尝试
        Long originalId = mcpMessage.getPayload().getId();
        DeviceMcpMessage response = null;

        for (int attempt = 1; attempt <= maxRetries + 1; attempt++) {
            // 为每次重试创建新的唯一ID，避免重复ID导致的资源混乱
            Long currentId = originalId + attempt;
            CompletableFuture<DeviceMcpMessage> future = new CompletableFuture<>();

            // 先清理可能存在的旧future（使用当前ID）
            CompletableFuture<DeviceMcpMessage> oldFuture = chatSession.getDeviceMcpHolder().getMcpPendingRequests().remove(currentId);
            if (oldFuture != null) {
                // 如果存在旧的future，先完成它避免内存泄漏
                oldFuture.completeExceptionally(new IllegalStateException("Replaced by retry attempt " + attempt));
            }

            // 使用新的ID注册future
            chatSession.getDeviceMcpHolder().getMcpPendingRequests().put(currentId, future);

            // 更新消息的ID为当前重试使用的ID
            mcpMessage.getPayload().setId(currentId);

            try {
                // 检查WebSocket连接状态
                if (!chatSession.isOpen()) {
                    logger.warn("SessionId: {}, WebSocket连接已断开，跳过MCP请求", chatSession.getSessionId());
                    // 清理当前future
                    future.completeExceptionally(new IllegalStateException("WebSocket disconnected"));
                    chatSession.getDeviceMcpHolder().getMcpPendingRequests().remove(currentId);
                    break;
                }

                // 发送消息
                String messageJson = JsonUtil.toJson(mcpMessage);
                chatSession.sendTextMessage(messageJson);

                // 缩短超时时间到3秒
                response = future.get(3, TimeUnit.SECONDS);

                // 成功获取响应，清除pending请求并返回
                chatSession.getDeviceMcpHolder().getMcpPendingRequests().remove(currentId);
                return response;

            } catch (java.util.concurrent.TimeoutException e) {
                logger.warn("SessionId: {}, MCP request timeout (attempt {}/{}), method: {}, id: {}",
                    chatSession.getSessionId(), attempt, maxRetries + 1, mcpMessage.getPayload().getMethod(), currentId);

                // 超时后确保future被完成，避免内存泄漏
                future.completeExceptionally(e);
                chatSession.getDeviceMcpHolder().getMcpPendingRequests().remove(currentId);

                // 如果不是最后一次尝试，则等待后重试
                if (attempt <= maxRetries) {
                    try {
                        Thread.sleep(500); // 等待500ms后重试
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        logger.error("SessionId: {}, Retry interrupted", chatSession.getSessionId(), ie);
                        break;
                    }
                }
            } catch (Exception e) {
                logger.error("SessionId: {}, Error sending MCP request (attempt {}/{}), method: {}, id: {}",
                    chatSession.getSessionId(), attempt, maxRetries + 1, mcpMessage.getPayload().getMethod(), currentId, e);

                // 确保future被完成
                future.completeExceptionally(e);
                chatSession.getDeviceMcpHolder().getMcpPendingRequests().remove(currentId);
                break;
            }
        }

        if (response == null) {
            logger.error("SessionId: {}, MCP request failed after {} attempts, method: {}, originalId: {}",
                chatSession.getSessionId(), maxRetries + 1, mcpMessage.getPayload().getMethod(), originalId);
        }

        return response;
    }

}