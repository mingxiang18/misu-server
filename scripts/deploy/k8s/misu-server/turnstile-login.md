# 登录页 Turnstile 配置

仅 `/account/auth/login` 校验 Turnstile。默认关闭，部署代码不会改变现有登录行为。

1. 在 Cloudflare Turnstile 创建 `managed` 组件，允许生产域名 `server.misu.chat`；本地开发使用独立测试组件或官方测试密钥。记录公开的 sitekey 和私密的 secret。
2. 先发布账号服务和前端代码。在 Kubernetes 创建 `misu-server/misu-account-turnstile` Secret，键名为 `secret`。不要把 secret 提交到仓库或放进前端配置。创建后重启 `misu-account` Pod，使 `TURNSTILE_SECRET` 环境变量生效。
3. 在 Nacos `prod / DEFAULT_GROUP / misu-account-prod.yml` 加入以下配置，重启 `misu-account` 后生效：

   ```yaml
   turnstile:
     enabled: true
     site-key: "<Cloudflare sitekey>"
     hostnames: "server.misu.chat"
   ```

4. 检查账号服务能访问 `https://challenges.cloudflare.com/turnstile/v0/siteverify`。在网页进行一次真实登录；缺令牌、错误域名、过期或重复令牌都应被拒绝。确认 `/account/auth/turnstile-config` 只返回 `enabled` 和 `siteKey`。

关闭时将 `turnstile.enabled` 设为 `false` 并重启账号服务。账号服务验证接口故障时会拒绝登录；已有会话的刷新接口不受影响。
