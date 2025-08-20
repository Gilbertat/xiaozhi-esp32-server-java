<template>
  <div class="realtime-config">
    <div class="config-header">
      <h3>实时对话配置</h3>
      <p class="config-description">配置OpenAI Realtime API，实现实时双向语音对话</p>
    </div>

    <ConfigManager
      ref="configManager"
      config-type="realtime"
      :type-options="typeOptions"
      :type-fields="typeFields"
      @config-updated="handleConfigUpdated"
    />
  </div>
</template>

<script>
import ConfigManager from '@/components/ConfigManager.vue'
import { configTypeMap } from '@/config/providerConfig.js'

export default {
  name: 'RealtimeConfig',
  components: {
    ConfigManager
  },
  data() {
    return {
      typeOptions: [],
      typeFields: {}
    }
  },
  created() {
    this.initConfigOptions()
  },
  methods: {
    initConfigOptions() {
      const realtimeConfig = configTypeMap.realtime
      if (realtimeConfig) {
        this.typeOptions = realtimeConfig.typeOptions || []
        this.typeFields = realtimeConfig.typeFields || {}
      }
    },
    handleConfigUpdated(data) {
      // 处理配置更新
      this.$emit('config-updated', data)
      
      // 显示成功提示
      this.$message({
        type: 'success',
        message: '实时对话配置已更新'
      })
    },
    // 刷新配置列表
    refreshConfigs() {
      if (this.$refs.configManager) {
        this.$refs.configManager.refreshConfigs()
      }
    }
  }
}
</script>

<style scoped>
.realtime-config {
  padding: 20px;
}

.config-header {
  margin-bottom: 20px;
}

.config-header h3 {
  margin: 0 0 8px 0;
  color: #333;
  font-size: 18px;
  font-weight: 600;
}

.config-description {
  margin: 0;
  color: #666;
  font-size: 14px;
  line-height: 1.5;
}
</style>