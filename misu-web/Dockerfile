# 设置本镜像需要使用的基础镜像
FROM amazoncorretto:17-alpine3.20-jdk

# 设定工作目录
WORKDIR /app/

# 把jar包添加到镜像中
ADD target/*.jar /app/application.jar
ADD src/main/resources/static /app/static

# 安装alpine镜像缺少的字体
RUN sed -i 's/dl-cdn.alpinelinux.org/mirrors.aliyun.com/g' /etc/apk/repositories
RUN set -xe && apk --no-cache add ttf-dejavu fontconfig

# 容器启动命令
ENTRYPOINT ["java", "-jar","/app/application.jar", "--spring.profiles.active=prod"]

# 设置时区
RUN echo "Asia/Shanghai" > /etc/timezone