# CCHR-Box 配置解耦与公开仓库审计

> 本文保留第一阶段完成时的记录。第二阶段已提交源码改动、删除历史 Release APK 并重写公开历史；本文中的远端清理待办已由 [历史清理记录](HISTORY_CLEANUP.md) 更新。GitHub 服务器端旧对象清除仍待 Support 处理。

日期：2026-09-30。以下为本地实施与只读核查结果；未推送代码、删除远端资产、修改服务端、重写历史或轮换签名。

## 完成情况

- 三个 Endpoint 独立从 Gradle property、环境变量、根目录 `local.properties` 读取，按此顺序覆盖，最后默认为空。显式空值保留覆盖效力。
- 本机原生产配置已迁入被忽略的 `local.properties`，原 SDK 与签名配置保留。未配置项才会补入，已有本地值不覆盖。
- 邀请码、私有订阅、公告与远程应用控制实现保留。私人构建继续使用现有请求协议；未配置某一项只停用对应功能。
- 无订阅 Endpoint 的构建复用首页按钮导入自有配置，保留节点与订阅，停止自动刷新，支持手动刷新。原自动更新字段不被永久修改。
- 核心代码、协议实现、数据库结构和页面布局 XML 未修改。
- 两个 GitHub 工作流只编译和测试，不上传 APK/AAB，不发布 Release，不注入生产签名 Secrets。

## 改动文件与原因

下表列出全部 19 个公开文件；另有 1 个忽略的本地文件发生配置追加。

| 文件 | 改动与原因 |
| --- | --- |
| `.github/workflows/release.yml` | 删除 Release 发布、APK/AAB Artifact、签名 Secrets 和分发参数；保留核心构建、OSS Debug 编译与测试，显式空 Endpoint，仓库只读权限。 |
| `.github/workflows/preview.yml` | 移除 Preview APK Artifact 和签名 Secrets；保留 Preview Debug 编译与测试，显式空 Endpoint，仓库只读权限。 |
| `.gitignore` | 忽略本地环境、签名容器、Kotlin 缓存和临时附件，保留原私有服务目录忽略规则。 |
| `README.md` | 改为自托管 / 自带配置定位；移除生产、后台、APK 下载与服务分发说明，补充本地构建和独立配置方法，保留上游 attribution。 |
| `local.properties.example` | 新增不含真实地址的空配置示例及优先级说明。 |
| `buildSrc/src/main/kotlin/EndpointConfiguration.kt` | 新增配置解析、独立 HTTPS 校验和 Java 字符串转义；校验失败不回显值。 |
| `buildSrc/src/test/kotlin/EndpointConfigurationTest.kt` | 测试优先级、空值覆盖、8 种组合、每个字段的非法输入和错误脱敏。 |
| `buildSrc/build.gradle.kts` | 添加构建配置测试所需的 JUnit 依赖。 |
| `app/build.gradle.kts` | 从实际本地文件和 Gradle providers 注入三个 BuildConfig 字段；添加 JVM 测试依赖。 |
| `app/src/main/java/io/nekohasekai/sagernet/Constants.kt` | 生产地址常量改为读取 BuildConfig。 |
| `app/src/main/java/io/nekohasekai/sagernet/cchr/PrivateSubscriptionManager.kt` | 保留私人业务和请求处理；补充空配置保护、公开模式的手动刷新及任意本地节点选择，并避免公开模式按到期状态删除数据。 |
| `app/src/main/java/io/nekohasekai/sagernet/bg/SubscriptionUpdater.kt` | 空订阅 Endpoint 时取消调度；已排队任务也在执行入口返回，不改订阅数据。 |
| `app/src/main/java/io/nekohasekai/sagernet/ui/MainActivity.kt` | 独立执行应用控制检查；禁止空订阅配置的邀请码引导，复用导入流程，兼容独立节点和订阅组。HTTP(S) 链接允许用户选择作为订阅或 HTTP 节点导入。 |
| `app/src/main/java/io/nekohasekai/sagernet/ui/PrivateHomeFragment.kt` | 空订阅 Endpoint 时原按钮改为导入配置；独立节点可显示和选择；手动刷新显式标记，公告仍由自身配置控制。 |
| `app/src/main/java/io/nekohasekai/sagernet/ui/InviteCodeActivity.kt` | 无订阅 Endpoint 时禁用输入和提交，避免直接打开 Activity 绕过入口保护；私人界面保留。 |
| `app/src/main/res/values/strings.xml` | 新增导入配置及无订阅状态文案。 |
| `app/src/main/res/values-zh-rCN/strings.xml` | 新增对应简体中文文案。 |
| `app/src/test/java/io/nekohasekai/sagernet/cchr/PrivateServiceRequestsTest.kt` | 用进程内模拟连接测试实际 HTTP 请求处理、8 种组合、空配置零请求、错误响应及公开模式保留到期旧数据。 |
| `COMPLIANCE_AUDIT.md` | 本报告：记录改动、发现、验证范围和仍需处理的历史暴露。 |
| `local.properties`（忽略、不提交） | 追加本机私有 Endpoint，使本地 Android Studio / Gradle 无需反复传参。 |

## 发现与处理结果

| 发现 | 处理结果 / 状态 |
| --- | --- |
| 当前 `Constants.kt` 的订阅、公告、远程控制生产地址 | 已迁出公开源码；只保留 BuildConfig 引用。本机私有配置和私人构建产物仍包含显式配置的值，符合私人构建要求。 |
| README 的生产订阅地址、后台地址、邀请码服务流程与 APK 下载说明 | 已删除并改为通用客户端文档；未将真实地址复制到示例或本报告。 |
| Release 工作流公开 APK、AAB 与 Preview APK 上传 | 发布和上传步骤已移除；未触发远端工作流。 |
| 工作流的签名 Secrets 引用 | 已移除；仓库 Secrets 本身未删除，其值未读取。 |
| 私人代码中的 GitHub Releases 更新兜底链接 | 按私人行为兼容要求保留原更新逻辑；它不是生产服务 Endpoint，也不会发布资产。历史资产仍需单独删除。 |
| 当前根目录私有服务、签名文件、本地构建说明与配置 | 保留在本机且被 Git 忽略；没有重新加入公开候选文件。本地签名配置字段不在报告中回显。 |
| Git 历史中的 `README.md` 和 `Constants.kt` | 仍含生产地址；当前修改不能清除历史。匹配到 README 1 个历史 blob、Constants 4 个历史 blob。 |
| Git 历史中的私有服务目录 | 源码、管理路由、配置和说明仍可取回；其中历史 README、服务配置文件含生产地址。删除提交为 `b58e1cc`，并不代表从历史抹除。 |
| Git 历史中的 `release.keystore` | 有 2 个历史文件版本，其中 1 个与本机现用文件的 SHA-256 完全相同。未读取私钥、未尝试密码、未更换文件。需独立评估和处理。 |
| token、secret、password、API key 等关键词 | 当前源码的协议字段、设置键、正常凭据读取逻辑不是明文服务凭据，已保留。本次模式扫描未发现额外可确认的真实 token 或明文私钥；不将其等同于绝对不存在未知凭据。 |
| 既有私人 APK、生成的 BuildConfig 与本地缓存 | 可能保留真实地址；属于忽略的本地材料，本次未删除，不应手动上传到公开仓库。 |
| 远端历史 Releases | 只读 API 核实 `v1.0`、`v1.1`、`v1.2`、`v1.3` 各有 4 个 APK，共 16 个；未删除。 |
| 远端 Actions Artifact | 只读 API 查询总数为 0；未检查不可访问的日志内容，也未清除缓存。 |

扫描覆盖公开受跟踪文件及新增候选文件，并检查全部本地可达 Git 历史的 2,199 个 blob。另检查相关忽略的本地配置及新生成的公开 APK。历史克隆范围、第三方副本、旧日志与未知凭据格式不包含在“无残留”的保证中。公开候选文件最终未发现已知生产域名或高置信凭据模式残留；许可证、普通第三方文档链接、DNS/测速地址与用户配置字段不因关键词匹配而删除。

## 已执行的验证

1. `gradlew.bat -p buildSrc test`：4 个测试通过。
2. 三个 Endpoint 显式为空时，`app:assembleOssDebug app:testOssDebugUnitTest`：构建通过，5 个测试通过，生成 4 个架构的本地 APK。
3. 未传任何 Endpoint 参数时，`app:assemblePreviewDebug app:testPreviewDebugUnitTest`：从本地文件生成私人构建，4 个测试通过，1 个仅适用于公开构建的测试按条件跳过。
4. 实际运行 Gradle 生成全部 8 种组合的 BuildConfig，并校验三个字段；验证 Gradle property > 环境变量 > 本地文件，包含显式空值覆盖。
5. 对三个字段分别输入无效地址：构建正确失败，错误包含字段名且不包含测试地址。
6. 网络测试运行实际客户端请求处理方法，由进程内模拟连接承接；检查请求次数、GET/POST、邀请码及版本字段、响应读取、错误与连接关闭。未访问生产服务。
7. 四个公开 APK 解压内容扫描未发现已知生产域名；私人 Preview APK 含本地配置值，结果符合各自构建目的。
8. 两个工作流 YAML 解析通过，检查只读权限、空属性覆盖、无上传/发布/签名 Secrets；`git diff --check` 通过。
9. 根 LICENSE、AUTHORS、libcore/LICENSE、应用内 LICENSE 内容未变化，代理核心及协议目录没有改动。

本地 Android 编译使用现有 JDK 21 和已有 `libcore.aar`，未重新构建 Linux 原生核心；GitHub 的全新 runner 流程尚未实际执行。构建保留了原有弃用 API 和部分翻译资源格式警告。

### 未完成的运行验证

未进行 Android Studio 图形界面同步、真机/模拟器安装、完整旧数据库覆盖安装、实际 WorkManager 调度、页面布局交互、节点测速或 VPN 连通测试。Android Studio 使用的 Gradle 配置路径已通过命令行验证。进程内请求测试和旧订阅对象保留测试不替代完整设备验证；邀请码替换涉及的数据库及 UI 流程仍需设备回归。

## 需仓库所有者处理

1. **历史 GitHub Releases 中已经存在的 16 个 APK 无法仅靠代码修改删除，需要通过 GitHub 页面或 GitHub API 单独删除。** 同时检查 Release 说明中的旧地址。此次没有删除 Release 或标签。
2. 另行安排 Git 历史、标签和公开副本中的生产配置、私有服务源码和签名材料清理；本次不自动强推或重写历史。
3. 历史签名文件与现用文件一致，需评估暴露影响。按实际情况轮换相关凭据、签名材料；签名更换需考虑已有安装的升级兼容性。
4. 删除不再需要的仓库签名 Secrets，检查旧工作流日志与缓存。代码移除 Secrets 引用不会删除这些远端数据。
5. 在设备上完成上述未验证项目后，再自行管理私人构建分发。此次未推送、未发布任何 APK。
