# CCHR-Box

CCHR-Box 是基于 NekoBox for Android 二次开发的私有化 Android 代理客户端。

当前版本聚焦私有化使用场景：用户通过邀请码获取默认订阅，客户端自动导入订阅并选择默认节点，主界面只展示订阅状态和匿名节点延迟。

## 下载与安装

请从本仓库的 GitHub Releases 下载 `v1.0` 对应 APK。

Release 会按 CPU 架构提供多个 APK：

- `arm64-v8a`：大多数近年 Android 手机，优先选择。
- `armeabi-v7a`：较旧的 32 位 ARM 设备。
- `x86` / `x86_64`：模拟器或少数 Intel 设备。

如果不确定设备架构，通常先安装 `arm64-v8a`。

## 私有化订阅

客户端首次连接时会要求输入邀请码。

邀请码会发送到私有订阅服务：

`https://sub-json.1630086.xyz`

服务端返回订阅链接后，客户端会自动创建并更新固定名称的“默认订阅”。之后启动时会自动刷新订阅状态，连接前仅在无订阅、订阅失效、无节点或默认节点失效时尝试修复订阅。

## 后台管理

订阅邀请码由 Cloudflare Worker 简易后台维护：

`https://sub-json.1630086.xyz/admin`

后台用于新增、编辑、启用/禁用和删除邀请码与订阅链接映射。后台密码通过 Cloudflare Secret 配置，不应写入仓库。

## 1.0 功能说明

- 应用名称：CCHR-Box。
- 包名：`com.cchr.box`。
- 默认主题色：蓝灰色。
- 主界面仅展示订阅状态和匿名节点延迟。
- 侧边栏仅保留配置、设置、关于。
- 设置页仅保留私有化所需基础选项。
- 无默认订阅时，连接按钮会弹出邀请码输入框。
- 已有有效订阅时，连接按钮直接启动 VPN，不再每次强制更新订阅。
- 订阅导入或更新后自动选择默认订阅内的第一个节点。
- VPN 连接成功后自动测试默认节点延迟并回写首页显示。

## 开源声明

本项目基于 NekoBox for Android 二次开发，遵循原项目许可证要求保留开源说明。

上游项目：

- [MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)
- [SagerNet/sing-box](https://github.com/SagerNet/sing-box)
