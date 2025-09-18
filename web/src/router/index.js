import Vue from "vue";
import Router from "vue-router";

Vue.use(Router);

export default new Router({
  mode: "history",
  routes: [
    {
      path: "/",
      redirect: "login"
    },
    {
      path: "/",
      component: resolve => require(["@/views/common/Home"], resolve),
      meta: { title: "首页", titleKey: "dashboard" },
      children: [
        {
          path: "/dashboard",
          component: resolve => require(["@/views/page/Dashboard"], resolve),
          name: "Dashboard",
          meta: { title: "Dashboard", titleKey: "dashboard", icon: "dashboard" }
        },
        {
          path: "/user",
          component: resolve => require(["@/views/page/User"], resolve),
          name: "User",
          meta: {
            title: "用户管理",
            titleKey: "userManager",
            icon: "team",
            breadcrumb: [{ breadcrumbName: "用户管理" }],
            isAdmin: 1
          }
        },
        {
          path: "/device",
          component: resolve => require(["@/views/page/Device"], resolve),
          name: "Device",
          meta: {
            title: "设备管理",
            titleKey: "deviceManager",
            icon: "robot",
            breadcrumb: [{ breadcrumbName: "设备管理" }]
          }
        },
        {
          path: "/message",
          component: resolve => require(["@/views/page/Message"], resolve),
          name: "message",
          meta: { title: "对话管理", titleKey: "messageManager", icon: "message" }
        },
        {
          path: "/role",
          component: resolve => require(["@/views/page/Role"], resolve),
          name: "role",
          meta: { title: "角色配置", titleKey: "roleConfig", icon: "user-add" }
        },
        {
          path: '/prompt-template',
          name: 'PromptTemplate',
          component: resolve => require(['@/views/page/PromptTemplate'], resolve),
          meta: {
            title: '提示词模板管理',
            titleKey: 'promptTemplate',
            icon: 'snippets',
            isAdmin: true,
            parent: '角色管理',
            hideInMenu: true
          }
        },
        {
          path: "/config",
          component: resolve => require(["@/views/common/PageView"], resolve),
          name: "Config",
          redirect: "/config/model",
          meta: { title: "配置管理", titleKey: "configManager", icon: "setting", isAdmin: 1 },
          children: [
            {
              path: "/config/model",
              component: resolve => require(["@/views/page/config/ModelConfig"], resolve),
              meta: { title: "模型配置", titleKey: "modelConfig", parent: "配置管理", isAdmin: 1 },
            },
            {
              path: "/config/agent",
              component: resolve => require(["@/views/page/config/Agent"], resolve),
              name: "Agent",
              meta: {
                title: "智能体管理",
                titleKey: "agentManager",
                parent: "配置管理",
                isAdmin: 1
              },
            },
            {
              path: "/config/stt",
              component: resolve => require(["@/views/page/config/SttConfig"], resolve),
              meta: { title: "语音识别配置", titleKey: "sttConfig", parent: "配置管理", isAdmin: 1 },
            },
            {
              path: "/config/tts",
              component: resolve => require(["@/views/page/config/TtsConfig"], resolve),
              meta: { title: "语音合成配置", titleKey: "ttsConfig", parent: "配置管理", isAdmin: 1 },
            },
            {
              path: "/config/realtime",
              component: resolve => require(["@/views/page/config/RealtimeConfig"], resolve),
              meta: { title: "实时对话配置", titleKey: "realtimeConfig", parent: "配置管理", isAdmin: 1 },
            },
          ]
        },
        {
          path: "/setting",
          component: resolve => require(["@/views/common/PageView"], resolve),
          name: "Setting",
          redirect: "/setting/account",
          meta: { title: "设置", titleKey: "settings", icon: "setting" },
          children: [
            {
              path: "/setting/account",
              component: resolve =>
                require(["@/views/page/setting/Account"], resolve),
              meta: { title: "个人中心", titleKey: "accountCenter", parent: "设置" }
            },
            {
              path: "/setting/config",
              component: resolve =>
                require(["@/views/page/setting/Config"], resolve),
              meta: { title: "个人设置", titleKey: "accountSettings", parent: "设置" }
            }
          ]
        },
        {
          path: "/chat",
          component: resolve => require(["@/views/page/Chat"], resolve),
          name: "Chat",
          meta: {
            title: "聊天",
            titleKey: "chat",
            icon: "message",
            breadcrumb: [{ breadcrumbName: "聊天" }]
          }
        },
      ]
    },
    {
      path: "/login",
      component: resolve => require(["@/views/page/Login"], resolve)
    },
    {
      path: "/forget",
      component: resolve => require(["@/views/page/Forget"], resolve)
    },
    {
      path: "/register",
      component: resolve => require(["@/views/page/Register"], resolve)
    },
    {
      path: "/404",
      component: resolve => require(["@/views/exception/404.vue"], resolve)
    },
    {
      path: "/403",
      component: resolve => require(["@/views/exception/403.vue"], resolve)
    },
    {
      path: "/*",
      redirect: "/404"
    }
  ]
});
