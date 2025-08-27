package com.xiaozhi.dialogue.service;

import com.xiaozhi.communication.common.ChatSession;
import com.xiaozhi.communication.common.SessionManager;
import com.xiaozhi.entity.SysConfig;
import com.xiaozhi.entity.SysDevice;
import com.xiaozhi.service.SysConfigService;
import com.xiaozhi.utils.JsonUtil;
import com.xiaozhi.utils.OpusProcessor;
import jakarta.annotation.Resource;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
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
    
    @Resource
    private OpusProcessor opusProcessor;
    
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
            logger.error("Failed to start realtime conversation for sessionId: {}", sessionId, e);
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
     * 输入的opusData是OPUS编码的音频数据，需要先解码为PCM16
     */
    public void sendAudioData(String sessionId, byte[] opusData) {
        RealtimeConnection connection = realtimeConnections.get(sessionId);
        if (connection != null) {
            if (!connection.isConnected()) {
                logger.warn("Realtime connection exists but not connected for sessionId: {}, removing and recreating", sessionId);
                // 移除无效连接并重新创建
                realtimeConnections.remove(sessionId);
                connection = null;
            } else {
                connection.sendAudioData(opusData);
                return;
            }
        }
        
        if (connection == null) {
            logger.warn("No realtime connection found for sessionId: {}, attempting to start connection", sessionId);
            // 尝试启动连接
            if (startRealtimeConversation(sessionId)) {
                // 等待连接建立
                int attempts = 0;
                while (attempts < 10) { // 最多等待5秒
                    connection = realtimeConnections.get(sessionId);
                    if (connection != null && connection.isConnected()) {
                        logger.info("Realtime connection established for sessionId: {}, sending audio data", sessionId);
                        connection.sendAudioData(opusData);
                        return;
                    }
                    try {
                        Thread.sleep(500); // 等待500ms
                        attempts++;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                logger.error("Failed to establish realtime connection within 5 seconds for sessionId: {}", sessionId);
            }
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
        if (null == connection) {
            return startRealtimeConversation(sessionId);
        }
        return connection.isConnected();
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
            String model = config.getModelName() != null ? config.getModelName() : "gpt-4o-realtime-preview-2025-06-03";
            
            logger.info("Creating Realtime connection for sessionId: {}, baseUrl: {}, model: {}", sessionId, baseUrl, model);
            
            // 检查API密钥格式
            if (apiKey == null || apiKey.trim().isEmpty()) {
                logger.error("API key is null or empty for sessionId: {}", sessionId);
                return null;
            }
            
            // 检查API密钥格式
            String keyPrefix = apiKey.length() > 10 ? apiKey.substring(0, 10) + "..." : apiKey;
            logger.info("Using API key starting with: {} (length: {})", keyPrefix, apiKey.length());
            
            // 构建WebSocket URL
            String wsUrl = baseUrl + "?model=" + model;
            logger.info("WebSocket URL: {}", wsUrl);
            
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
            
            // 添加短暂延迟等待连接建立
            try {
                Thread.sleep(500); // 等待500ms让连接有时间建立
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            return connection;
            
        } catch (Exception e) {
            logger.error("Failed to create realtime connection for sessionId: {}", sessionId, e);
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
            logger.info("Attempting to connect Realtime WebSocket for sessionId: {}", sessionId);
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
                // 验证和转换音频格式到OpenAI Realtime要求的格式
                byte[] realtimeAudio = convertToRealtimeFormat(audioData);
                if (realtimeAudio == null || realtimeAudio.length == 0) {
                    logger.warn("Audio data is empty or conversion failed");
                    return;
                }
                
                // 将音频数据编码为base64
                String base64Audio = Base64.getEncoder().encodeToString(realtimeAudio);
                
                JSONObject message = new JSONObject();
                message.put("type", "input_audio_buffer.append");
                message.put("audio", base64Audio);
                
                webSocket.send(message.toString());
                logger.debug("Sent audio data to OpenAI Realtime API, size: {} bytes", realtimeAudio.length);
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
            public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
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
                    // 只设置输入采样率，OpenAI会自动处理输出采样率
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
            public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
                try {
                    JSONObject message = new JSONObject(text);
                    String type = message.optString("type");
                    
                    logger.debug("Received realtime message: {} for sessionId: {}", type, sessionId);
                    
                    switch (type) {
                        case "session.created":
                            handleSessionCreated(message);
                            break;
                        case "session.updated":
                            handleSessionUpdated(message);
                            break;
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
            public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, Response response) {
                connected = false;
                logger.error("Realtime WebSocket failed for sessionId: {}", sessionId, t);
                if (response != null) {
                    logger.error("Response code: {}, message: {}", response.code(), response.message());
                    try {
                        String responseBody = response.body() != null ? response.body().string() : "No body";
                        logger.error("Response body: {}", responseBody);
                    } catch (Exception e) {
                        logger.error("Failed to read response body", e);
                    }
                }
            }
            
            @Override
            public void onClosing(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
                connected = false;
                logger.info("Realtime WebSocket closing for sessionId: {}, code: {}, reason: {}", 
                           sessionId, code, reason);
            }
        }
        
        private void handleAudioDelta(JSONObject message) {
            try {
                String base64Audio = message.optString("delta");
                if (base64Audio != null && !base64Audio.isEmpty()) {
                    byte[] pcmAudioData = Base64.getDecoder().decode(base64Audio);
                    logger.info("Received audio delta from OpenAI: {} bytes PCM data", pcmAudioData.length);
                    
                    // OpenAI返回的是PCM16格式，需要转换为OPUS格式发送给设备
                    byte[] opusData = convertPcmToOpus(pcmAudioData);
                    if (opusData != null && opusData.length > 0) {
                        logger.info("Converted PCM to OPUS: {} bytes, sending to device", opusData.length);
                        // 发送OPUS音频数据到客户端
                        ChatSession chatSession = sessionManager.getSession(sessionId);
                        if (chatSession != null) {
                            audioService.sendRealTimeAudioChunk(chatSession, opusData);
                            logger.info("Audio chunk sent to device via audioService");
                        } else {
                            logger.error("ChatSession is null, cannot send audio to device");
                        }
                    } else {
                        logger.warn("PCM to OPUS conversion failed or resulted in empty data");
                    }
                } else {
                    logger.debug("Received audio delta with empty data");
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
            logger.info("🎤 Speech started for sessionId: {}", sessionId);
        }
        
        private void handleSpeechStopped() {
            logger.info("⏹️ Speech stopped for sessionId: {}", sessionId);
            
            // 提交音频缓冲区
            try {
                logger.info("📤 Committing audio buffer and requesting response for sessionId: {}", sessionId);
                JSONObject commitMessage = new JSONObject();
                commitMessage.put("type", "input_audio_buffer.commit");
                webSocket.send(commitMessage.toString());
                
                // 创建响应
                JSONObject responseMessage = new JSONObject();
                responseMessage.put("type", "response.create");
                webSocket.send(responseMessage.toString());
                logger.info("✅ Response create message sent to OpenAI for sessionId: {}", sessionId);
                
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
            logger.error("Realtime API error for sessionId: {}, error: {}", sessionId, message);
        }
        
        private void handleSessionCreated(JSONObject message) {
            logger.info("Realtime session created for sessionId: {}", sessionId);
        }
        
        private void handleSessionUpdated(JSONObject message) {
            logger.info("Realtime session configuration updated for sessionId: {}", sessionId);
        }
        
        /**
         * 转换音频格式到OpenAI Realtime API要求的格式
         * 输入：OPUS编码数据
         * 输出：mono PCM16 at 24kHz
         */
        private byte[] convertToRealtimeFormat(byte[] opusData) {
            if (opusData == null || opusData.length == 0) {
                logger.debug("Empty OPUS data received");
                return null;
            }
            
            try {
                // 第一步：OPUS解码为PCM16 (16kHz)
                byte[] pcm16Data = opusProcessor.opusToPcm(sessionId, opusData);
                if (pcm16Data == null || pcm16Data.length == 0) {
                    logger.debug("OPUS to PCM conversion failed or resulted in empty data");
                    return null;
                }
                
                // 检查PCM16数据长度是否为偶数（每样本2字节）
                if (pcm16Data.length % 2 != 0) {
                    logger.warn("Invalid PCM16 data length: {}, truncating to even length", pcm16Data.length);
                    // 截断到偶数长度
                    byte[] evenLengthData = new byte[pcm16Data.length - 1];
                    System.arraycopy(pcm16Data, 0, evenLengthData, 0, evenLengthData.length);
                    pcm16Data = evenLengthData;
                }
                
                // 第二步：采样率转换 16kHz -> 24kHz
                return upsampleTo24kHz(pcm16Data);
                
            } catch (Exception e) {
                logger.error("Failed to convert OPUS to Realtime format", e);
                return null;
            }
        }
        
        /**
         * 将16kHz PCM16数据上采样到24kHz
         */
        private byte[] upsampleTo24kHz(byte[] pcm16Data) {
            if (pcm16Data == null || pcm16Data.length == 0) {
                return null;
            }
            
            try {
                // 从16kHz转换到24kHz的简单线性插值
                // 24000 / 16000 = 1.5倍采样点
                int inputSampleCount = pcm16Data.length / 2; // 16位PCM，每样本2字节
                int outputSampleCount = (int) (inputSampleCount * 1.5); // 1.5倍插值
                byte[] outputAudio = new byte[outputSampleCount * 2];
                
                for (int i = 0; i < outputSampleCount; i++) {
                    // 计算在原始音频中的位置
                    double sourceIndex = i / 1.5;
                    int index1 = (int) sourceIndex;
                    int index2 = Math.min(index1 + 1, inputSampleCount - 1);
                    
                    // 获取两个样本点的值（16位有符号小端序）
                    short sample1 = (short) ((pcm16Data[index1 * 2] & 0xFF) | (pcm16Data[index1 * 2 + 1] << 8));
                    short sample2 = (short) ((pcm16Data[index2 * 2] & 0xFF) | (pcm16Data[index2 * 2 + 1] << 8));
                    
                    // 线性插值
                    double fraction = sourceIndex - index1;
                    short interpolatedSample = (short) (sample1 + (sample2 - sample1) * fraction);
                    
                    // 写入输出数组（16位有符号小端序）
                    outputAudio[i * 2] = (byte) (interpolatedSample & 0xFF);
                    outputAudio[i * 2 + 1] = (byte) ((interpolatedSample >> 8) & 0xFF);
                }
                
                logger.debug("Audio upsampled: 16kHz->24kHz, {} samples -> {} samples", 
                    inputSampleCount, outputSampleCount);
                return outputAudio;
                
            } catch (Exception e) {
                logger.error("Failed to upsample audio to 24kHz", e);
                return null;
            }
        }
        
        /**
         * 将OpenAI返回的PCM16音频转换为OPUS格式发送给设备
         * OpenAI返回的PCM可能是24kHz，需要先下采样到16kHz再编码为OPUS
         */
        private byte[] convertPcmToOpus(byte[] pcmData) {
            if (pcmData == null || pcmData.length == 0) {
                logger.debug("Empty PCM data received from OpenAI");
                return null;
            }
            
            try {
                // 检查PCM16数据长度是否为偶数（每样本2字节）
                if (pcmData.length % 2 != 0) {
                    logger.warn("Invalid PCM16 data length from OpenAI: {}, truncating to even length", pcmData.length);
                    // 截断到偶数长度
                    byte[] evenLengthData = new byte[pcmData.length - 1];
                    System.arraycopy(pcmData, 0, evenLengthData, 0, evenLengthData.length);
                    pcmData = evenLengthData;
                }
                
                // OpenAI可能返回24kHz的PCM，需要下采样到16kHz
                // 假设OpenAI返回的是24kHz，需要下采样到16kHz（2/3倍）
                byte[] pcm16kHz = downsampleTo16kHz(pcmData);
                
                // 将PCM16编码为OPUS
                List<byte[]> opusFramesList = opusProcessor.pcmToOpus(sessionId, pcm16kHz, false);
                if (opusFramesList != null && !opusFramesList.isEmpty()) {
                    // 将所有OPUS帧合并为一个数组
                    int totalLength = opusFramesList.stream().mapToInt(frame -> frame.length).sum();
                    byte[] opusData = new byte[totalLength];
                    int offset = 0;
                    for (byte[] frame : opusFramesList) {
                        System.arraycopy(frame, 0, opusData, offset, frame.length);
                        offset += frame.length;
                    }
                    logger.debug("Converted PCM to OPUS: {} bytes -> {} bytes", pcmData.length, opusData.length);
                    return opusData;
                } else {
                    logger.debug("PCM to OPUS conversion failed or resulted in empty data");
                    return null;
                }
                
            } catch (Exception e) {
                logger.error("Failed to convert PCM to OPUS", e);
                return null;
            }
        }
        
        /**
         * 将24kHz PCM16数据下采样到16kHz
         * 如果输入已经是16kHz，直接返回
         */
        private byte[] downsampleTo16kHz(byte[] pcmData) {
            if (pcmData == null || pcmData.length == 0) {
                return null;
            }
            
            try {
                int inputSampleCount = pcmData.length / 2; // 16位PCM，每样本2字节
                
                // 假设输入是24kHz，需要下采样到16kHz（2/3倍）
                // 如果输入样本数太少，可能已经是16kHz，直接返回
                if (inputSampleCount < 480) { // 20ms @ 24kHz = 480 samples
                    logger.debug("Input PCM seems to be 16kHz already, returning as-is");
                    return pcmData;
                }
                
                // 24kHz -> 16kHz: 每3个样本取2个（简单抽取）
                int outputSampleCount = (int) (inputSampleCount * 2.0 / 3.0);
                byte[] outputAudio = new byte[outputSampleCount * 2];
                
                for (int i = 0; i < outputSampleCount; i++) {
                    // 计算在原始音频中的位置（24kHz -> 16kHz：1.5倍索引）
                    int sourceIndex = (int) (i * 1.5);
                    sourceIndex = Math.min(sourceIndex, inputSampleCount - 1);
                    
                    // 直接复制样本（简单抽取，不做插值）
                    outputAudio[i * 2] = pcmData[sourceIndex * 2];
                    outputAudio[i * 2 + 1] = pcmData[sourceIndex * 2 + 1];
                }
                
                logger.debug("Audio downsampled: 24kHz->16kHz, {} samples -> {} samples", 
                    inputSampleCount, outputSampleCount);
                return outputAudio;
                
            } catch (Exception e) {
                logger.error("Failed to downsample audio to 16kHz", e);
                return pcmData; // 失败时返回原始数据
            }
        }
    }
}