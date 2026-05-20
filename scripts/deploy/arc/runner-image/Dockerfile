# 自建 GitHub Actions runner 镜像：在官方 actions-runner 上预装
# jdk17 + maven + node20 + kubectl + kaniko + rsync + jq + git
#
# Build & push 命令见 ./README.md。每当 actions-runner 升级或想换底层工具版本
# 时重建一次即可，平时不动。
#
# ⚠️ runner 版本必须保持较新：GitHub 会定期 deprecate 旧版本，被 deprecate 的
# runner agent 一连上 broker 就拒绝（Forbidden... is deprecated and cannot
# receive messages），pod 立即 exit。用 :latest 让每次 rebuild 都拿最新版。
FROM ghcr.io/actions/actions-runner:latest

USER root

ENV DEBIAN_FRONTEND=noninteractive

# 仅 build 时用代理（apt-get / curl 下载外网包）。运行时由 pod env 覆盖，不固化进镜像。
# 用法：docker buildx build --build-arg http_proxy=http://host.docker.internal:7897 ...
ARG http_proxy
ARG https_proxy
ARG no_proxy=localhost,127.0.0.1

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates curl wget gnupg git rsync jq tar xz-utils \
      gettext-base \
      openjdk-17-jdk-headless \
 && rm -rf /var/lib/apt/lists/*

# Maven
ARG MAVEN_VERSION=3.9.9
RUN curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
      | tar -xz -C /opt \
 && ln -sfn "/opt/apache-maven-${MAVEN_VERSION}" /opt/maven \
 && ln -sfn /opt/maven/bin/mvn /usr/local/bin/mvn

# Node 20
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
 && apt-get install -y --no-install-recommends nodejs \
 && rm -rf /var/lib/apt/lists/*

# kubectl (matches server v1.34.1)
ARG KUBECTL_VERSION=v1.34.1
RUN curl -fsSL "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/amd64/kubectl" -o /usr/local/bin/kubectl \
 && chmod +x /usr/local/bin/kubectl

# Kaniko：从官方镜像把 /kaniko 整个目录搬过来（包含 executor + 依赖 lib）
COPY --from=gcr.io/kaniko-project/executor:v1.23.2 /kaniko /kaniko

# 用 wrapper 替换 /kaniko/executor —— Kaniko 1.23.2 跟 Alpine 3.20 base 不兼容，
# wrapper 会改写 Dockerfile 让它 FROM 预装字体的 jdk-fonts，并去掉 apk add 步。
# 详见 kaniko-wrapper.sh 头注释。
COPY kaniko-wrapper.sh /tmp/kaniko-wrapper.sh
RUN mv /kaniko/executor /kaniko/executor-real \
 && install -m 0755 /tmp/kaniko-wrapper.sh /kaniko/executor \
 && rm -f /tmp/kaniko-wrapper.sh
ENV PATH="/kaniko:${PATH}"
# Kaniko 需要的环境
ENV DOCKER_CONFIG=/kaniko/.docker

# 默认 runner 用户的 .m2 / npm 缓存目录提前创建好，方便 hostPath 挂载
RUN mkdir -p /home/runner/.m2 /home/runner/.npm \
 && chown -R runner:runner /home/runner/.m2 /home/runner/.npm /kaniko

USER runner
WORKDIR /home/runner

# 入口沿用上游 run.sh（ARC 会覆盖 command，但保留以便本地调试）
ENTRYPOINT ["/home/runner/run.sh"]
