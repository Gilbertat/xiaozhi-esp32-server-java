# OpenAI Whisper ChatModel 集成指南

## 概述

本方案实现了OpenAI `/v1/audio/transcriptions` 接口与现有ChatModel架构的兼容，使得音频转录功能可以通过统一的ChatModel接口使用，同时保持与现有代码的完全兼容性。

## 核心组件

### 1. AudioTranscriptionModel 接口
```java
public interface AudioTranscriptionModel {
    String transcribe(byte[] audioData, String language, String prompt);
    Flux<String> transcribeStream(byte[] audioData, String language, String prompt);
    default boolean supportsStreamingTranscription() { return false; }
}
```

### 2. OpenAIWhisperChatModel 实现
- 实现了 `ChatModel` 和 `AudioTranscriptionModel` 接口
- 支持multipart/form-data上传到OpenAI Whisper API
- 兼容现有的ChatModel调用方式
- 支持两种消息格式：特殊标记格式和元数据格式

### 3. ChatModelFactory 扩展
- 新增 `takeTranscriptionModel()` 方法
- 支持通过配置类型 `transcription` 或提供商 `whisper` 创建音频转录模型
- 完全向后兼容现有代码

## 使用方式

### 方式1: 通过ChatModelFactory创建
```java
@Autowired
private ChatModelFactory chatModelFactory;

// 通过配置类型创建
ChatModel transcriptionModel = chatModelFactory.takeTranscriptionModel();

// 通过自定义配置创建
SysConfig whisperConfig = new SysConfig()
    .setProvider("whisper")
    .setConfigType("transcription")
    .setApiKey("your-openai-api-key")
    .setApiUrl("https://api.openai.com/v1/audio/transcriptions")
    .setConfigName("whisper-1");

ChatModel customModel = chatModelFactory.takeTranscriptionModel(whisperConfig);
```

### 方式2: 使用工具类进行转录
```java
// 特殊标记格式
String result = AudioTranscriptionUtils.transcribeWithChatModel(
    transcriptionModel, audioData, "zh", "这是一段中文语音");

// 元数据格式
String result = AudioTranscriptionUtils.transcribeWithMetadata(
    transcriptionModel, audioData, "zh", "请转录这段音频");

// 安全转录（自动检测模型类型）
String result = AudioTranscriptionUtils.safeTranscribe(
    transcriptionModel, audioData, "zh", "请转录这段音频");
```

### 方式3: 直接使用AudioTranscriptionModel接口
```java
if (chatModel instanceof AudioTranscriptionModel transcriptionModel) {
    String result = transcriptionModel.transcribe(audioData, "zh", "提示词");
}
```

## 数据库配置

在 `sys_config` 表中添加音频转录模型配置：

```sql
INSERT INTO sys_config (
    config_name, 
    provider, 
    config_type, 
    api_key, 
    api_url, 
    model_name,
    description
) VALUES (
    'whisper-1',
    'whisper', 
    'transcription',
    'your-openai-api-key',
    'https://api.openai.com/v1/audio/transcriptions',
    'whisper-1',
    'OpenAI Whisper音频转录模型'
);
```

## 消息格式

### 特殊标记格式（推荐）
由于Spring AI框架的UserMessage构造函数限制，我们主要使用特殊标记格式：

```json
{
  "type": "user",
  "content": "[AUDIO_TRANSCRIPTION]{\"audio_data\":\"base64_encoded_audio\",\"language\":\"zh\",\"prompt\":\"提示词\"}"
}
```

### 使用示例
```java
// 推荐使用特殊标记格式
String result = AudioTranscriptionUtils.transcribeWithChatModel(
    transcriptionModel, audioData, "zh", "这是一段中文语音");

// 或者使用安全转录方法（自动检测模型类型）
String result = AudioTranscriptionUtils.safeTranscribe(
    transcriptionModel, audioData, "zh", "请转录这段音频");
```

## 与现有流程集成

### 在DialogueService中集成
```java
@Service
public class DialogueService {
    
    @Resource
    private ChatModelFactory chatModelFactory;
    
    public String processAudioTranscription(byte[] audioData, String language) {
        try {
            // 获取音频转录模型
            ChatModel transcriptionModel = chatModelFactory.takeTranscriptionModel();
            
            // 使用工具类进行转录
            return AudioTranscriptionUtils.safeTranscribe(
                transcriptionModel, audioData, language, null);
                
        } catch (Exception e) {
            logger.error("音频转录失败", e);
            return "音频转录失败";
        }
    }
}
```

### 在WebSocket消息处理中集成
```java
// 在MessageHandler中添加音频转录处理
public void handleAudioTranscriptionMessage(ChatSession session, byte[] audioData) {
    try {
        ChatModel transcriptionModel = chatModelFactory.takeTranscriptionModel();
        String transcriptionResult = AudioTranscriptionUtils.safeTranscribe(
            transcriptionModel, audioData, "zh", null);
        
        // 发送转录结果给客户端
        session.sendTextMessage(transcriptionResult);
        
    } catch (Exception e) {
        logger.error("处理音频转录消息失败", e);
    }
}
```

## 兼容性保证

1. **接口兼容**: `OpenAIWhisperChatModel` 实现了标准的 `ChatModel` 接口
2. **工厂兼容**: `ChatModelFactory` 保持现有方法不变，仅新增转录相关方法
3. **配置兼容**: 通过配置类型和提供商字段区分，不影响现有配置
4. **调用兼容**: 现有代码可以继续使用 `ChatModel` 接口，需要时可安全转换

## 错误处理

```java
try {
    String result = AudioTranscriptionUtils.safeTranscribe(model, audioData, "zh", null);
} catch (UnsupportedOperationException e) {
    // 模型不支持音频转录
    logger.warn("当前模型不支持音频转录功能");
} catch (RuntimeException e) {
    // API调用失败
    logger.error("音频转录API调用失败", e);
}
```

## 性能优化建议

1. **音频格式**: 确保音频数据为WAV格式，减少转换开销
2. **文件大小**: 控制音频文件大小，避免超时
3. **并发控制**: 使用连接池管理HTTP连接
4. **缓存策略**: 对相同音频内容进行缓存（可选）

## 测试验证

使用提供的 `AudioTranscriptionIntegrationTest` 类进行集成测试：

```java
@Autowired
private AudioTranscriptionIntegrationTest integrationTest;

public void runTests() {
    integrationTest.testTranscriptionModelCreation();
    integrationTest.testAudioTranscription("path/to/audio.wav");
    integrationTest.testModelCompatibility();
    integrationTest.demonstrateIntegrationWithExistingFlow();
}
```

## 总结

此方案成功实现了OpenAI Whisper API与ChatModel架构的无缝集成，具有以下优势：

- ✅ 完全兼容现有ChatModel接口
- ✅ 支持multipart/form-data上传
- ✅ 提供多种使用方式
- ✅ 保持代码架构一致性
- ✅ 支持配置化管理
- ✅ 提供完整的错误处理
- ✅ 包含详细的测试示例