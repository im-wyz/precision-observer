# 本地密钥目录（不提交 Git）

将 Google Earth Engine 服务账号 JSON 保存为：

```
secrets/gee-service-account.json
```

在 `agent-api/.env` 中设置：

```
GEE_CREDENTIALS_PATH=../secrets/gee-service-account.json
```

**不要把真实 JSON 提交到仓库。** 若密钥曾在聊天或邮件中泄露，请到 Google Cloud Console 轮换私钥。
