-- 鲸熵汇收银系统本地数据库初始化入口。
-- 必须从仓库根目录使用 MySQL 客户端执行；业务表由应用启动时 Flyway V1—V90 创建。
-- 推荐直接运行 scripts/local/Initialize-LocalDatabase.ps1，避免手工暴露口令。
SET NAMES utf8mb4;
SET time_zone = '+00:00';
CREATE DATABASE IF NOT EXISTS jshpos
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;
USE jshpos;
SOURCE server/script/sql/ry_vue_5.X.sql;
