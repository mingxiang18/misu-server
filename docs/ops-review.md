# 运维中心验收记录

代码位于 `codex/feat-ops-console`，未提交、推送或部署生产。实现复用 ADMIN，提供独立运维服务、Nacos/Headlamp 代理入口和两个固定节点的 SSH 页面。

## 已通过的验证

- 后端完整测试 17 项通过，Maven 打包成功。真实嵌入式 Tomcat 使用运维 Cookie 和内部代理头（不携带主站 JWT）完成 101 握手并协商 `console.v1`；账号验证器拒绝 ADMIN、会话超时时，上下游均关闭且桥接计数归零。读超时和异常会令测试失败。另有本地真实上游测试覆盖原始路径/query、文本及二进制转发。
- 前端生产构建通过。已有大体积 chunk 提示，不阻止构建。
- 本地模拟 API/SSH 浏览器检查覆盖 1280×800 和 414×800：终端输入、缩放、节点切换、切换控制台后重连；普通用户不显示入口且访问 `/ops` 返回首页。
- ADMIN 退出时，模拟撤销接口返回 HTTP 401，页面仍正常退出且无错误提示；普通用户退出未发起运维撤销请求。
- 官方 Nginx 1.27.5 在本机临时目录构建并通过 `nginx -t`；真实代理与模拟上游验证了 GET/POST 查询参数、POST 请求体、鉴权子请求方法、凭据过滤、WebSocket 分流、未知 Host 421、未授权 401 不到达控制台，以及上游失败日志不含模拟票据。
- 发布脚本测试覆盖 ConfigMap 名称渲染、config-only 小写名称、实际 Deployment 导出失败，以及配置/Deployment apply、patch、rollout 失败时调用恢复命令。伪 SSH 测试只证明脚本执行路径，不代表真实集群恢复成功。

部署测试入口：`bash scripts/deploy/tests/test-ops-release.sh`。本次临时证据在 `/tmp/misu-ops-release-test-evidence.txt` 和 `/tmp/misu-nginx-test/`；本地模拟进程已停止。

后端测试入口：使用本机 Maven，运行 `mvn -pl misu-ops/misu-ops-biz -am test -Dmaven.repo.local=/Users/renyuming/Documents/develop/maven/repository`。测试报告位于 `misu-ops/misu-ops-biz/target/surefire-reports/`。

主代理已集中复核权限、凭据过滤、握手、会话回收和部署回滚相关代码；审核发现的问题均已交由子代理修正并补充验证。本轮执行子代理采用 GPT-5.6-Luna + high，未创建嵌套子代理。

## 发布前仍需完成

- 按 `docs/ops-deployment.md` 配置两个新域名的 DNS/TLS 及现有入口路由。
- 准备生产 SSH 专用密钥、可信 known_hosts、代理共享 Secret 和与账号服务一致的 JWT 签名 Secret；临时调查密钥未写入模块或部署文件。
- 真实 Nacos、Headlamp 及节点 SSH 的完整联调；验证控制台自己的登录、集群日志/exec、节点终端交互以及退出和账号权限撤销。
- 实际 Kubernetes 发布与失败恢复验收。

两个控制台现在通过 `server.misu.chat` 同源路径和目标 Path Cookie 隔离；Headlamp 的身份感知代理由 sidecar 受保护地注入已校验 ADMIN 用户名，Nacos 使用服务端认证策略。Headlamp Service 通过仓库 patch 收回旧 NodePort，避免绕过 sidecar 信任边界。
