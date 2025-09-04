#!/bin/bash

# ChatModelFactory 测试运行脚本

echo "开始运行 ChatModelFactory 测试..."

# 设置测试环境
export SPRING_PROFILES_ACTIVE=test

# 运行单元测试
echo "运行单元测试..."
mvn test -Dtest=ChatModelFactoryTest

# 检查单元测试结果
if [ $? -eq 0 ]; then
    echo "✅ 单元测试通过"
else
    echo "❌ 单元测试失败"
    exit 1
fi

# 运行集成测试
echo "运行集成测试..."
mvn test -Dtest=ChatModelFactoryIntegrationTest

# 检查集成测试结果
if [ $? -eq 0 ]; then
    echo "✅ 集成测试通过"
else
    echo "❌ 集成测试失败"
    exit 1
fi

# 运行所有相关测试
echo "运行所有 ChatModelFactory 相关测试..."
mvn test -Dtest="*ChatModelFactory*"

# 生成测试报告
echo "生成测试报告..."
mvn surefire-report:report

echo "🎉 所有测试完成！"
echo "测试报告位置: target/site/surefire-report.html"