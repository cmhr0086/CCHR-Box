# CCHR-Box

CCHR-Box 是基于 NekoBox for Android 的开源 Android 代理客户端，定位为 **self-hosted / bring-your-own-config**：由使用者自行构建，并提供自己管理或获授权使用的配置。

**本仓库不提供代理服务器、公共订阅、邀请码或网络接入服务，也不通过 GitHub Releases 或 Actions Artifact 分发 APK / AAB。** 服务端是独立私有组件，不包含在本仓库中。

## 使用自有配置

默认构建不内置任何订阅服务、公告服务或远程应用控制地址。

- 首页“导入配置”支持自有订阅 URL、兼容的订阅导入链接和单个节点分享链接。
- 导入后通过现有节点选择页选择节点，再连接。
- 未配置订阅服务 Endpoint 时，只在用户明确操作时更新订阅；启动、连接前和后台均不自动刷新。
- 覆盖安装时保留已有订阅及节点，不清空数据。独立节点也可选择、测速和连接。

## 自行构建

Android 构建使用 JDK 17 或兼容版本、Android SDK 35、Build Tools 35.0.1；原生核心使用 Go 1.25 和 Android NDK 25.0.8775105。构建工具和依赖版本以仓库脚本为准。

在支持现有 Bash 构建脚本的环境中准备原生核心与资源：

```sh
./run lib core
./run init action gradle
```

Android Studio / Gradle 构建需要先准备好 `app/libs/libcore.aar` 及资源。本地 SDK 路径写入不提交 Git 的 `local.properties`。在 Windows 中使用 `gradlew.bat`，其他环境使用 `./gradlew`。

例如构建不含可选服务地址的客户端：

```sh
./gradlew app:assembleOssDebug \
  -PCCHR_SUBSCRIPTION_ENDPOINT= \
  -PCCHR_ANNOUNCEMENT_ENDPOINT= \
  -PCCHR_APP_CONTROL_ENDPOINT=
```

产物仅保存在本地构建目录，工作流不上传产物。自行分发修改版本时，应继续遵守 GPL 和依赖许可证要求。

## 可选的兼容服务配置

三个客户端服务地址独立配置、校验和启停，不存在统一总开关：

| 配置键 | 对应功能 | 默认值 |
| --- | --- | --- |
| `CCHR_SUBSCRIPTION_ENDPOINT` | 兼容订阅服务 | 空 |
| `CCHR_ANNOUNCEMENT_ENDPOINT` | 公告 | 空 |
| `CCHR_APP_CONTROL_ENDPOINT` | 远程应用控制 | 空 |

每项读取顺序为 **Gradle property → 同名环境变量 → 根目录 local.properties → 空字符串**。显式空值覆盖低优先级配置，值两端空白会被移除。非空值必须是有效 HTTPS 地址，不包含 URL 用户凭据或片段。

参考 [local.properties.example](local.properties.example)，将需要的键加入本机 `local.properties`，保留已有 SDK 路径和签名设置。Android Studio 和 Gradle 随后会自动读取，无需每次传参。不要把真实地址写入受跟踪的 `gradle.properties`、工作流、示例或文档。

某项为空只停用该项；配置有效订阅 Endpoint 的构建保留原有订阅交互与自动更新行为。公告及远程应用控制始终由各自地址独立决定是否启用。

构建配置会进入应用二进制，不能用来保存服务端密钥或管理凭据。私人构建产物由构建者自行管理。

## 验证

```sh
./gradlew -p buildSrc test
./gradlew app:testOssDebugUnitTest \
  -PCCHR_SUBSCRIPTION_ENDPOINT= \
  -PCCHR_ANNOUNCEMENT_ENDPOINT= \
  -PCCHR_APP_CONTROL_ENDPOINT=
```

测试使用进程内模拟连接，不访问生产服务。Release / Preview 工作流仅用于构建和测试。

## 许可证与上游

保留上游 GPLv3-or-later 许可声明、版权信息和贡献者记录，详见 [LICENSE](LICENSE)、[AUTHORS](AUTHORS)、`libcore/LICENSE` 及应用内许可证。第三方组件继续适用各自许可证。

- [MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)
- [SagerNet/SagerNet](https://github.com/SagerNet/SagerNet)
- [SagerNet/sing-box](https://github.com/SagerNet/sing-box)
- [MatsuriDayo/sing-box](https://github.com/MatsuriDayo/sing-box)
