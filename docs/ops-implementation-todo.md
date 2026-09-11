# 运维中心收尾任务

## 执行约定

- 后续子代理统一使用 `gpt-5.6-luna`、`high`；禁止创建嵌套子代理。
- 主代理负责明确方案、分配文件范围和最终验收；执行过程由子代理自主完成，只上报阻塞、契约变化及最终结果。
- 复用现有 ADMIN，不增加 SUPER_ADMIN。保持独立 misu-ops 服务，代码及部署清晰简洁。
- 不推送 master、不操作生产发布。现有改动保留在 `codex/feat-ops-console`。

## 已验证

- 前端生产构建通过；存在项目原有的大体积 chunk 提示。
- 浏览器本地模拟验证：1280×800、414×800 终端布局、输入、缩放、节点切换、控制台切换后重新连接正常。
- 模拟普通用户访问 /ops 返回首页，菜单隐藏；ADMIN 退出时撤销接口返回 HTTP 401 仍可正常退出，无错误提示。普通用户退出没有调用撤销接口。
- 上述浏览器检查使用模拟 API/SSH，不代表真实 Nacos、Headlamp 和 SSH 端到端验收。

## 后端子任务

文件范围：misu-ops，以及必要的 account/security 窄范围改动。

- [x] 完成并测试控制台 WebSocket 桥接：上游子协议协商、二进制消息、原始路径和查询参数、有限缓冲和发送串行化。
- [x] 处理连接超时、迟到连接、提前关闭和并发撤销，资源与连接计数只释放一次。
- [x] 主站退出、会话超时及账号停用/撤销 ADMIN 后关闭上下游连接；真实 Spring MVC/Tomcat 握手、ADMIN 会话撤销和会话超时双端关闭已覆盖。
- [x] 统一 HTTP 与 WebSocket 的凭据过滤：清除所有主站及运维 Cookie（含重复及自定义名称），保留上游自身登录凭据。
- [x] 确认票据返回 ticket、entryUrl、exchangeUrl，交换校验目标 Host，单次消费；检查缺失会话返回 401，以及交换与退出的并发竞态。
- [x] SSH 在 channel.connect 前取得流；页面在建连时关闭要释放资源；校验 UTF-8/emoji 跨读取边界输出。
- [x] 完成有意义的测试和 Maven 打包，报告实际测试数及限制；不要只提供 mock 验证结论。
- [x] 向部署子任务提供最终桥接路径、内部鉴权请求头及过滤后的响应头契约。
- [x] Nacos 2.5.0 使用服务端只读 Secret 凭据按 ops session 获取/缓存短时 token；当前生产认证关闭时保持原生 state/API，不做浏览器 token 或响应体替换，未来启用认证仍保留服务端注入路径。
- [x] 将两个控制台接入主站 `server.misu.chat` 同源路径：Nacos 原生 `/nacos/`、Headlamp `/ops/headlamp/`；交换入口按目标路径绑定，`MISU_OPS_SESSION` Cookie 按目标 Path 并存，主站 Nginx/sidecar 保留 HTTP 长轮询、重定向和 WS Upgrade，并清除主站凭据。

后端实现契约：Java context 为 `/ops`；控制台 WS 为 `/ops/ws/console/{nacos|headlamp}`，由运维 Nginx 内部 `/_ops/ws` 转发。HTTP/WS auth_request 使用 loopback + `X-Ops-Proxy-Key`，目标使用 `X-Ops-Target`/`X-Ops-Console-Target`；校验后的上游凭据只通过 `X-Ops-Upstream-Cookie` 与 `X-Ops-Upstream-Authorization` 响应/请求头传递，主站 JWT、运维 Cookie 和重复 Cookie 均会过滤。

后端真实证据（2026-09-10）：`ConsoleWebSocketTomcatIntegrationTest` 通过 2 项真实嵌入式 Tomcat TCP WebSocket 测试，握手只携带运维 Cookie 和 `X-Ops-*` 内部头（不携带主站 JWT），确认 `/ops/ws/console/nacos` 返回 `101` 和 `console.v1` 协商结果，并在账号 verifier 拒绝 ADMIN、会话超时两条路径分别观察下游客户端与本地上游同时关闭、桥接计数归零；完整 `misu-ops-biz` 测试共 17 项通过，Maven package 通过。读循环将超时和其他异常标记为测试失败，不再误报关闭成功。Spring 6.1.6 本地源码确认握手协议判断会 unwrap `WebSocketHandlerDecorator`，实现已改为直接握手处理器协商，避免协议响应丢失。

## 部署子任务

文件范围：scripts/deploy、release workflow、docs/ops-deployment.md。

- [x] 对齐 Java /ops context 与桥接路径，加入固定 Nacos/Headlamp 上游 URL 环境变量。
- [x] 对齐 X-Ops-Upstream-Cookie / X-Ops-Upstream-Authorization 契约，清除重复和多余代理头。
- [x] 检查正常发布及 config-only 发布的 OPS_CONFIG_TAG 替换、版本化 ConfigMap、旧 Deployment 与配置共同回滚。
- [x] 检查 JWT 签名 Secret、代理 Secret、SSH Secret、loopback 服务健康探针及资源配置。
- [x] 增加 `misu-ops-nacos-auth` Secret 模板引用及 Nacos 2.5.0 免二次登录说明；Headlamp 使用 `-in-cluster` + 官方 `-proxy-auth=true`，由受信 sidecar 注入已校验用户身份并保留其自身 ServiceAccount/RBAC。
- [x] 验证 nginx 配置语法及路由行为、YAML 渲染、shell 语法；尽可能用本地模拟请求证明鉴权、HTTP/WS 分流和未知 Host 拒绝。
- [x] 文档明确 DNS/TLS、现有边缘入口接入、生产密钥准备及真实服务验收步骤；不将共享父域 Cookie 描述为完整安全隔离。

部署验证范围：`scripts/deploy/tests/test-ops-release.sh` 使用 fake SSH/scp 覆盖正常发布、config-only 的小写 ConfigMap 名称、live Deployment 导出失败注入，以及 ops ConfigMap/Deployment apply、patch、rollout 失败时的回滚命令调用；该 harness 不证明真实集群已恢复。`scripts/deploy/tests/test-ops-nginx-container.sh` 使用 Nginx 1.27 Alpine 容器和本地 mock upstream 验证同域 Nacos/Headlamp 路径、目标交换、未授权 401、重定向、未知路径/Host 及固定上游 URI；未覆盖真实 DNS/TLS 边缘入口、生产 Kubernetes、Nacos、Headlamp、SSH 或真实密钥验收。当前 sidecar 不做响应体替换。

## 主代理最终验收

- [x] 一次集中审阅最终 diff、权限边界、会话回收、部署和回滚；有具体缺陷再分配修正。
- [x] 核对测试证据，按最终改动执行必要构建，不重复无变化的验证。
- [x] 检查无临时密钥或测试配置进入仓库，记录剩余真实环境验收项。
- [x] 最终说明完成范围、实际验证范围及尚未部署状态。

2026-09-10 已恢复并完成代码收尾与本地验收，详见 `docs/ops-review.md`；真实生产接入与联调尚未执行。
