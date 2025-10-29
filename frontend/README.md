# 小知设备管理前端系统

这是一个为非管理员用户设计的设备管理前端应用，使用React、TypeScript、i18next等现代前端技术栈构建，并与现有的Java后端系统集成。

## 功能特性

1. **多语言支持**
   - 根据用户浏览器Accept-Language请求头自动切换语言
   - 支持中文、英语、韩语三种语言

2. **用户注册**
   - 邮箱验证
   - 用户名设置
   - 密码和确认密码验证
   - 通过邮箱验证码验证

3. **用户登录**
   - 支持邮箱或用户名登录
   - 密码验证

4. **新手引导**
   - 首次登录后自动显示引导流程
   - 多步骤操作指南
   - 可跳过或完成引导

5. **设备管理**
   - 通过验证码绑定新设备
   - 查看设备列表
   - 解绑设备

## 技术栈

- **框架**: React 18
- **语言**: TypeScript
- **路由**: React Router v6
- **状态管理**: React Context API
- **国际化**: i18next + react-i18next
- **HTTP客户端**: Axios
- **样式**: CSS Modules

## 与Java后端集成

该前端系统与位于 `/Users/shiyue/code/xiaozhi-esp32-server-java` 的Java后端系统集成，使用的API端点包括：

- `/api/user/login` - 用户登录
- `/api/user/add` - 用户注册
- `/api/user/checkUser` - 检查用户名/邮箱是否已存在
- `/api/user/sendEmailCaptcha` - 发送邮箱验证码
- `/api/user/checkCaptcha` - 验证验证码
- `/api/device/query` - 获取设备列表
- `/api/device/add` - 绑定设备（通过验证码）
- `/api/device/delete` - 解绑设备

## 项目结构

```
src/
├── components/     # 可复用组件
├── contexts/       # React Context
├── hooks/          # 自定义Hooks
├── locales/        # 多语言资源文件
├── pages/          # 页面组件
├── styles/         # 样式文件
├── types/          # TypeScript类型定义
├── utils/          # 工具函数和API调用
├── App.tsx         # 应用主组件
├── i18n.ts         # 国际化配置
└── index.tsx       # 应用入口
```

## 本地开发

```bash
# 安装依赖
npm install

# 确保Java后端服务正在运行（默认端口8080）
# 启动Java后端: cd ../.. && mvn spring-boot:run

# 启动前端开发服务器
npm start

# 构建生产版本
npm run build
```

## 环境变量

- `REACT_APP_API_URL`: 后端API基础URL（可选，默认为 http://localhost:8080/api）

## 认证机制

该应用使用Java后端的Session-based认证机制，而不是JWT。用户登录后，会话信息由后端管理。

## 安全特性

- 与后端一致的认证机制
- 请求拦截器处理认证相关逻辑
- 会话管理