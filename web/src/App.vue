<template>
  <a-config-provider :locale="antdLocales[$i18n.locale]">
    <div id="app">
      <router-view></router-view>
    </div>
  </a-config-provider>
</template>

<script>
import zhCN from 'ant-design-vue/lib/locale-provider/zh_CN';
import enUS from 'ant-design-vue/lib/locale-provider/en_US';
import koKR from 'ant-design-vue/lib/locale-provider/ko_KR';

export default {
  data () {
    return {
      antdLocales: {
        'zh': zhCN,
        'en': enUS,
        'ko': koKR
      }
    }
  }
}
</script>
