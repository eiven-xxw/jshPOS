# 本地基础设施

T0 只提供 MySQL 8.4 LTS 与 Redis 7.4。支付、对象存储、消息队列和可观测平台在对应需求准入后加入。

```powershell
Copy-Item .env.example .env
# 编辑 .env，替换所有示例口令
docker compose --env-file .env up -d
docker compose ps
```

镜像使用版本与多架构摘要双重固定。Redis仅用于缓存/协调，MySQL才是T0后续交易事实的候选持久层。
