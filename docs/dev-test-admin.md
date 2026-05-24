# Dev 测试管理员账号

本地 dev 环境专用的 **ADMIN** 账号，用于自验收需要管理员权限的功能（如转码管理 `/videoTranscodeAdmin`、文件映射回填、用户管理等）。
与非管理员测试账号 `verifybot`（见 `CLAUDE.md`）配套：`verifybot` 验「真 403 路径」，本账号验「ADMIN 放行路径」。

## 凭据

| 字段 | 值 |
|---|---|
| userName | `verifyadmin` |
| password | `Verify@1234` |
| phone | `13900000001` |
| 角色 | `USER` + `ADMIN` + `FILE_ADMIN` |
| user_id | 6（misu_account.sys_user） |

## 登录

```bash
curl -s -X POST http://127.0.0.1:30260/account/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"userName":"verifyadmin","password":"Verify@1234","captchaCode":"dummy"}'
```

- captcha 后端只校验非空，任意字符串即可。
- 返回 `data.token`，后续请求带 `Authorization: Bearer <token>`。
- 浏览器自验收：前端 `http://localhost:5173` 用上面账号登录，左侧/底部进「视频转码管理」即可（该页 `isAdmin` 门控，普通账号看不到）。

## 重建方式（库被 nuke 后）

密码哈希用 `BCryptPasswordEncoder`（默认 strength 10，Spring 也接受 `$2y` 前缀）。
如需重新生成哈希：`htpasswd -bnBC 10 "" "Verify@1234"`。

```sql
USE misu_account;
INSERT INTO sys_user (user_name, phone_number, password, status, del_flag, nick_name)
VALUES ('verifyadmin', '13900000001',
        '$2y$10$xunEuD8So/L8rVEBTGqz7ebdcdxYVbHqPnS4FgdOOi3D0GF6sgR4C',
        '0', '0', '验收管理员');
SET @uid = LAST_INSERT_ID();
INSERT INTO sys_user_role (role_id, user_id) VALUES ('USER', @uid), ('ADMIN', @uid), ('FILE_ADMIN', @uid);
```

> 仅限本地 dev。生产环境没有该账号。
