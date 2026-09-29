# 公开历史清理记录

日期：2026-09-30。

## 已完成

- 在本机建立受限访问的完整工作区备份和 Git bundle，并通过恢复、文件校验和 `git fsck` 验证。备份包含私人材料，不应上传。
- 提交公开客户端与私人服务的配置解耦；三个 Endpoint 独立配置，公共 CI 显式传入空值。详见 [第一阶段审计](COMPLIANCE_AUDIT.md)。
- 重写并更新公开 `main` 和 `v1.0`～`v1.3` 标签，移除历史签名容器及私有服务源码，将保留文件中的生产地址替换为示例占位符。
- 通过 GitHub API 删除四个 Release 的全部 16 个 APK。每个 Release 从 4 个资产变为 0；未发现 AAB。Release 页面保留，相关私人分发说明已清理。删除资产本身不改变标签；标签在历史重写步骤另行更新。
- Release / Preview 工作流仅保留源码构建与测试，不发布或上传二进制，不注入生产签名 Secrets。
- 仓库及环境 Secrets、Actions runs、caches、Artifacts 查询均为 0，因此本轮未删除这些项目。历史工作流中出现的 Secrets 名称引用不等于公开了值。已不存在的日志无法复核。
- 从 GitHub 独立重新克隆，扫描全部可达 refs 并运行 `git fsck`：未发现已知生产地址、签名容器、私有服务源码或已确认真实凭据。

## 签名与验证

保留现用 Android signing key。历史中发现签名容器，未发现可确认的可用签名密码；16 个官方历史 APK 的签名证书均与本机现用证书一致。未观察到本次检查范围内的异常签名，不代表已检查互联网中的所有第三方 APK。

再次通过构建配置 4 项测试和公开应用 5 项测试，覆盖三个 Endpoint 的 8 种组合。公开 OSS Debug 编译通过，生成配置为空；私人 Preview Debug 使用忽略的 `local.properties` 编译通过，应用测试 4 项通过、1 项仅适用于公开构建的测试跳过。未自动请求生产服务。GPL、AUTHORS、上游 attribution、代理核心和协议实现保持不变。

未执行真机、模拟器、完整旧数据库覆盖安装或 VPN 连通测试。命令行验证了 Android Studio 所用的 Gradle 配置路径，未进行 Studio 图形界面同步。Android 编译复用已有 `libcore.aar`，未重新构建 Linux 原生核心，也未触发 GitHub runner 验证。

## 仍需处理：GitHub 服务器端旧对象

**历史重写不能保证已公开数据从 GitHub 服务器或第三方副本完全消失。** 本轮在无认证请求中确认，已知旧 SHA 仍可返回旧提交和旧签名文件对象，尽管这些对象已不在公开分支或标签中，fresh clone 也无法通过正常可达历史取回。

需由仓库所有者向 GitHub Support 请求审查并清除服务器端旧对象及缓存；受理和清除范围由 GitHub 决定。申请证据保存在本机受限备份中，不在公开仓库重新刊登敏感对象标识。参见 [GitHub 官方清理说明](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository)。

签名风险结论为“历史暴露，现实风险较低但非零”，依据是未发现可确认的可用密码。扫描无法证明从未有人下载旧签名文件。未经迁移决策不更换签名密钥，以免影响已有安装覆盖升级。

持有旧克隆的维护者应先在本机备份未提交工作，再重新克隆；不要把旧历史、旧标签或备份 refs 推回仓库。已下载的 APK、旧克隆及其他第三方副本无法通过本次仓库操作收回。
