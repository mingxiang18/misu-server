# 运维中心 AI CLI 更新

运维中心通过工作节点的 `/usr/local/bin/misu-ai-cli codex` 启动 Codex。
Codex 安装在独立的 npm 全局目录 `/srv/misu-ai-cli/codex`，启动入口是
`/srv/misu-ai-cli/codex/bin/codex`。包装器同时设置 `npm_config_prefix`、
`NPM_CONFIG_PREFIX` 和 PATH，因此在 Codex 中确认更新时，`npm install -g`
会更新同一份安装。更新完成后重新连接即可使用新版本。

初次安装使用固定版本 `0.171.0`。重新运行安装器会保留目录中更高的正式版本；
低于初始版本的安装会升级到初始版本。Claude 保留原有的本地安装目录和固定版本。
安装器会移除旧本地清单中的 Codex 依赖，并重新生成对应锁文件。

## 验证

```bash
/usr/local/bin/misu-ai-cli codex --version
PATH=/opt/misu-ai-cli/node-current/bin:/usr/bin:/bin \
  npm_config_prefix=/srv/misu-ai-cli/codex npm prefix -g
```

第二条命令必须返回 `/srv/misu-ai-cli/codex`。
在运维中心之外手动更新时，显式指定目录：

```bash
PATH=/opt/misu-ai-cli/node-current/bin:/usr/bin:/bin \
  /opt/misu-ai-cli/node-current/bin/npm install -g \
  --prefix /srv/misu-ai-cli/codex --include=optional --ignore-scripts \
  --proxy=http://127.0.0.1:7890 @openai/codex@latest
```

## 迁移与回滚

首次迁移先备份旧 `/usr/local/bin/misu-ai-cli` 与 `/srv/misu-ai-cli/cli`，
在新目录安装并验证 Codex 后再原子替换包装器。保留原工作目录和认证目录。
需要回滚时，恢复备份包装器即可重新使用保留的旧 Codex 安装。

安装器与包装器回归检查：`bash scripts/deploy/ai-cli/test-install-worker.sh`。
