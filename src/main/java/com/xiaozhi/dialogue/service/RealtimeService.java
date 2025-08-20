package com.xiaozhi.dialogue.service;

import com.xiaozhi.communication.common.ChatSession;
import com.xiaozhi.communication.common.SessionManager;
import com.xiaozhi.entity.SysConfig;
import com.xiaozhi.entity.SysDevice;
import com.xiaozhi.service.SysConfigService;
import com.xiaozhi.utils.JsonUtil;
import jakarta.annotation.Resource;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * OpenAI Realtime API服务
 * 提供实时双向语音对话功能
 */
@Service
public class RealtimeService {
    private static final Logger logger = LoggerFactory.getLogger(RealtimeService.class);
    
    private static final String REALTIME_API_URL = "wss://api.openai.com/v1/realtime";
    
    @Resource
    private SessionManager sessionManager;
    
    @Resource
    private SysConfigService configService;
    
    @Resource
    private AudioService audioService;
    
    // 存储每个设备的Realtime连接
    private final ConcurrentHashMap<String, RealtimeConnection> realtimeConnections = new ConcurrentHashMap<>();
    
    /**
     * 开始Realtime对话
     */
    public boolean startRealtimeConversation(String sessionId) {
        try {
            ChatSession chatSession = sessionManager.getSession(sessionId);
            if (chatSession == null) {
                logger.error("ChatSession not found for sessionId: {}", sessionId);
                return false;
            }
            
            SysDevice device = sessionManager.getDeviceConfig(sessionId);
            if (device == null || device.getRoleId() == null) {
                logger.error("Device or role not configured for sessionId: {}", sessionId);
                return false;
            }
            
            // 获取Realtime配置
            SysConfig realtimeConfig = getRealtimeConfig(device);
            if (realtimeConfig == null) {
                logger.error("Realtime configuration not found for device: {}", device.getDeviceId());
                return false;
            }
            
            // 创建Realtime连接
            RealtimeConnection connection = createRealtimeConnection(sessionId, realtimeConfig);
            if (connection == null) {
                return false;
            }
            
            realtimeConnections.put(sessionId, connection);
            logger.info("Realtime conversation started for sessionId: {}", sessionId);
            return true;
            
        } catch (Exception e) {
            logger.error("Failed to start realtime conversation for sessionId: " + sessionId, e);
            return false;
        }
    }
    
    /**
     * 停止Realtime对话
     */
    public void stopRealtimeConversation(String sessionId) {
        RealtimeConnection connection = realtimeConnections.remove(sessionId);
        if (connection != null) {
            connection.close();
            logger.info("Realtime conversation stopped for sessionId: {}", sessionId);
        }
    }
    
    /**
     * 发送音频数据到OpenAI Realtime
     */
    public void sendAudioData(String sessionId, byte[] audioData) {
        RealtimeConnection connection = realtimeConnections.get(sessionId);
        if (connection != null) {
            connection.sendAudioData(audioData);
        } else {
            logger.warn("No realtime connection found for sessionId: {}", sessionId);
        }
    }
    
    /**
     * 发送文本到OpenAI Realtime
     */
    public void sendTextInput(String sessionId, String text) {
        RealtimeConnection connection = realtimeConnections.get(sessionId);
        if (connection != null) {
            connection.sendTextInput(text);
        } else {
            logger.warn("No realtime connection found for sessionId: {}", sessionId);
        }
    }
    
    /**
     * 检查是否有活跃的Realtime连接
     */
    public boolean hasActiveRealtimeConnection(String sessionId) {
        RealtimeConnection connection = realtimeConnections.get(sessionId);
        return connection != null && connection.isConnected();
    }
    
    /**
     * 清理会话相关资源
     */
    public void cleanupSession(String sessionId) {
        stopRealtimeConversation(sessionId);
    }
    
    /**
     * 获取Realtime配置
     */
    private SysConfig getRealtimeConfig(SysDevice device) {
        // 查找专门的realtime配置
        SysConfig config = configService.selectConfigByConditions(new SysConfig()
                .setUserId(device.getUserId())
                .setConfigType("realtime")
                .setProvider("openai"));
        
        if (config == null) {
            // 如果没有专门的realtime配置，尝试使用LLM配置
            config = configService.selectConfigByCondition(new SysConfig()
                    .setUserId(device.getUserId())
                    .setConfigType("llm")
                    .setProvider("openai"));
        }
        
        return config;
    }
    
    /**
     * 创建Realtime连接
     */
    private RealtimeConnection createRealtimeConnection(String sessionId, SysConfig config) {
        try {
            String apiKey = config.getApiKey();
            String baseUrl = config.getBaseUrl() != null ? config.getBaseUrl() : REALTIME_API_URL;
            String model = config.getModelName() != null ? config.getModelName() : "gpt-4o-realtime-preview-2024-10-01";
            
            // 构建WebSocket URL
            String wsUrl = baseUrl + "?model=" + model;
            
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.SECONDS) // 实时连接不设读取超时
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build();
            
            Request request = new Request.Builder()
                    .url(wsUrl)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("OpenAI-Beta", "realtime=v1")
                    .build();
            
            RealtimeConnection connection = new RealtimeConnection(sessionId, client, request);
            connection.connect();
            
            return connection;
            
        } catch (Exception e) {
            logger.error("Failed to create realtime connection for sessionId: " + sessionId, e);
            return null;
        }
    }
    
    /**
     * Realtime连接管理类
     */
    private class RealtimeConnection {
        private final String sessionId;
        private final OkHttpClient client;
        private final Request request;
        private WebSocket webSocket;
        private volatile boolean connected = false;
        
        public RealtimeConnection(String sessionId, OkHttpClient client, Request request) {
            this.sessionId = sessionId;
            this.client = client;
            this.request = request;
        }
        
        public void connect() {
            webSocket = client.newWebSocket(request, new RealtimeWebSocketListener());
        }
        
        public boolean isConnected() {
            return connected && webSocket != null;
        }
        
        public void sendAudioData(byte[] audioData) {
            if (!isConnected()) {
                return;
            }
            
            try {
                // 将音频数据编码为base64
                String base64Audio = Base64.getEncoder().encodeToString(audioData);
                
                JSONObject message = new JSONObject();
                message.put("type", "input_audio_buffer.append");
                message.put("audio", base64Audio);
                
                webSocket.send(message.toString());
            } catch (Exception e) {
                logger.error("Failed to send audio data", e);
            }
        }
        
        public void sendTextInput(String text) {
            if (!isConnected()) {
                return;
            }
            
            try {
                // 创建用户消息
                JSONObject userMessage = new JSONObject();
                userMessage.put("type", "conversation.item.create");
                
                JSONObject item = new JSONObject();
                item.put("type", "message");
                item.put("role", "user");
                
                JSONArray content = new JSONArray();
                JSONObject textContent = new JSONObject();
                textContent.put("type", "input_text");
                textContent.put("text", text);
                content.put(textContent);
                
                item.put("content", content);
                userMessage.put("item", item);
                
                webSocket.send(userMessage.toString());
                
                // 触发响应生成
                JSONObject responseMessage = new JSONObject();
                responseMessage.put("type", "response.create");
                
                webSocket.send(responseMessage.toString());
                
            } catch (Exception e) {
                logger.error("Failed to send text input", e);
            }
        }
        
        public void close() {
            if (webSocket != null) {
                webSocket.close(1000, "Session ended");
            }
            connected = false;
        }
        
        private class RealtimeWebSocketListener extends WebSocketListener {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                connected = true;
                logger.info("Realtime WebSocket connected for sessionId: {}", sessionId);
                
                // 发送会话配置
                try {
                    JSONObject sessionUpdate = new JSONObject();
                    sessionUpdate.put("type", "session.update");
                    
                    JSONObject session = new JSONObject();
                    session.put("modalities", new JSONArray().put("text").put("audio"));
                    session.put("instructions", "你是一个友好的AI助手，请用中文与用户对话。");
                    session.put("voice", "alloy");
                    session.put("input_audio_format", "pcm16");
                    session.put("output_audio_format", "pcm16");
                    session.put("input_audio_transcription", new JSONObject().put("model", "whisper-1"));
                    session.put("turn_detection", new JSONObject().put("type", "server_vad"));
                    session.put("temperature", 0.8);
                    session.put("max_response_output_tokens", 4096);
                    
                    sessionUpdate.put("session", session);
                    
                    webSocket.send(sessionUpdate.toString());
                    
                } catch (Exception e) {
                    logger.error("Failed to send session configuration", e);
                }
            }
            
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    JSONObject message = new JSONObject(text);
                    String type = message.optString("type");
                    
                    logger.debug("Received realtime message: {} for sessionId: {}", type, sessionId);
                    
                    switch (type) {
                        case "response.audio.delta":
                            handleAudioDelta(message);
                            break;
                        case "response.text.delta":
                            handleTextDelta(message);
                            break;
                        case "response.done":
                            handleResponseDone(message);
                            break;
                        case "input_audio_buffer.speech_started":
                            handleSpeechStarted();
                            break;
                        case "input_audio_buffer.speech_stopped":
                            handleSpeechStopped();
                            break;
                        case "conversation.item.input_audio_transcription.completed":
                            handleTranscriptionCompleted(message);
                            break;
                        case "error":
                            handleError(message);
                            break;
                        default:
                            // 记录其他类型的消息
                            logger.debug("Unhandled realtime message type: {}", type);
                    }
                    
                } catch (Exception e) {
                    logger.error("Failed to process realtime message", e);
                }
            }
            
            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                connected = false;
                logger.error("Realtime WebSocket failed for sessionId: " + sessionId, t);
            }
            
            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                connected = false;
                logger.info("Realtime WebSocket closing for sessionId: {}, code: {}, reason: {}", 
                           sessionId, code, reason);
            }
        }
        
        private void handleAudioDelta(JSONObject message) {
            try {
                String base64Audio = message.optString("delta");
                if (base64Audio != null && !base64Audio.isEmpty()) {
                    byte[] audioData = Base64.getDecoder().decode(base64Audio);
                    
                    // 发送音频数据到客户端
                    ChatSession chatSession = sessionManager.getSession(sessionId);
                    if (chatSession != null) {
                        audioService.sendRealTimeAudioChunk(chatSession, audioData);
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to handle audio delta", e);
            }
        }
        
        private void handleTextDelta(JSONObject message) {
            try {
                String textDelta = message.optString("delta");
                if (textDelta != null && !textDelta.isEmpty()) {
                    // 发送文本增量到客户端
                    ChatSession chatSession = sessionManager.getSession(sessionId);
                    if (chatSession != null) {
                        audioService.sendRealtimeTextDelta(chatSession, textDelta);
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to handle text delta", e);
            }
        }
        
        private void handleResponseDone(JSONObject message) {
            logger.info("Realtime response completed for sessionId: {}", sessionId);
            
            // 通知客户端响应完成
            ChatSession chatSession = sessionManager.getSession(sessionId);
            if (chatSession != null) {
                audioService.sendRealtimeResponseComplete(chatSession);
            }
        }
        
        private void handleSpeechStarted() {
            logger.info("Speech started for sessionId: {}", sessionId);
        }
        
        private void handleSpeechStopped() {
            logger.info("Speech stopped for sessionId: {}", sessionId);
            
            // 提交音频缓冲区
            try {
                JSONObject commitMessage = new JSONObject();
                commitMessage.put("type", "input_audio_buffer.commit");
                webSocket.send(commitMessage.toString());
                
                // 创建响应
                JSONObject responseMessage = new JSONObject();
                responseMessage.put("type", "response.create");
                webSocket.send(responseMessage.toString());
                
            } catch (Exception e) {
                logger.error("Failed to commit audio buffer", e);
            }
        }
        
        private void handleTranscriptionCompleted(JSONObject message) {
            try {
                String transcript = message.optString("transcript");
                if (transcript != null && !transcript.isEmpty()) {
                    logger.info("Transcription completed for sessionId: {}, text: {}", sessionId, transcript);
                    
                    // 发送转录文本到客户端
                    ChatSession chatSession = sessionManager.getSession(sessionId);
                    if (chatSession != null) {
                        audioService.sendTranscriptionResult(chatSession, transcript);
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to handle transcription", e);
            }
        }
        
        private void handleError(JSONObject message) {
            String errorMessage = message.optString("message", "Unknown error");
            logger.error("Realtime API error for sessionId: {}, error: {}", sessionId, errorMessage);
        }
    }
}