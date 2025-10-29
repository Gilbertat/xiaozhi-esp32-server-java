#!/bin/bash

# 小知前端项目启动脚本

echo "小知设备管理前端系统 - 启动脚本"
echo "================================"

# 检查是否已安装npm
if ! command -v npm &> /dev/null; then
    echo "错误: 未找到npm，请先安装Node.js"
    exit 1
fi

# 检查是否已安装yarn（可选）
USE_YARN=false
if command -v yarn &> /dev/null; then
    echo "检测到yarn，是否使用yarn而不是npm？(y/n): "
    read -r response
    if [[ "$response" =~ ^[Yy]$ ]]; then
        USE_YARN=true
    fi
fi

# 获取当前脚本所在目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 检查是否在frontend目录下
if [ ! -f "package.json" ]; then
    echo "错误: 未找到package.json文件，请确保在frontend目录下运行此脚本"
    exit 1
fi

echo "正在安装依赖..."

if [ "$USE_YARN" = true ]; then
    yarn install
else
    npm install
fi

if [ $? -ne 0 ]; then
    echo "错误: 依赖安装失败"
    exit 1
fi

echo "依赖安装完成！"

echo "可用命令："
echo "  启动开发服务器: npm start 或 yarn start"
echo "  构建生产版本: npm run build 或 yarn run build"
echo "  运行测试: npm test 或 yarn test"

echo ""
echo "现在启动开发服务器..."
if [ "$USE_YARN" = true ]; then
    yarn start
else
    npm start
fi