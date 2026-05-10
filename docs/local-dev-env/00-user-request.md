# 用户需求（原始记录）

> 使用 ai-product-workflow skill，帮我为当前项目搭建一个纯本地的，后端和前端可连通，可联调测试，可一键启动，关闭的测试环境和启动脚本给后续给你做开发和测试使用，中间件可以都在本机 docker 配置容器和启动，项目中的 application-local.yml 里面的配置是本地测试的，没有敏感信息，可以给你读取和配置环境

> 对了，先跟你说下只要能运行 account、gateway、fileserver 模块即可

## 关键信息

- 目标：纯本地（不依赖远程 Nacos / MySQL / VPN）开发联调环境
- 一键启动 / 关闭脚本
- 中间件用本机 Docker 起容器
- 现有 `application-local.yml` 指向 `10.8.0.22:8848`（团队共享 Nacos），用户允许我修改它们以指向本地
- 范围：只需保证 **misu-account / misu-gateway / misu-file-server** 三个 Java 服务 + 前端 `misu-file-server-ui` 跑通联调
- misu-bot / misu-net / misu-web 暂不在范围内
