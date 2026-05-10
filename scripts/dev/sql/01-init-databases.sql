-- 本地开发：建库（不建表）
-- JPA hibernate.ddl-auto=update 在 Java 服务首次启动时自动建表
-- 如需补充 docs/*.sql 中的迁移，启动后执行：./dev.sh seed-sql

CREATE DATABASE IF NOT EXISTS `misu_account`
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS `misu_file_server`
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

-- 本地 Nacos 自身需要的库（NACOS 持久化用，可选；本套环境用嵌入式 derby，所以注释掉）
-- CREATE DATABASE IF NOT EXISTS `nacos_config` DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_unicode_ci;

GRANT ALL PRIVILEGES ON `misu_account`.* TO 'root'@'%';
GRANT ALL PRIVILEGES ON `misu_file_server`.* TO 'root'@'%';
FLUSH PRIVILEGES;
