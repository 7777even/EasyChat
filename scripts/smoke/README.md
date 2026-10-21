# scripts/smoke/ — 跨实例通话冒烟（手动，非 CI 门禁）

> **为什么在这里而不是 `scripts/verify/`**：`verify_*` 是**机控门禁**，进 CI、进 pre-push，
> 每次推送都必须通过。而本目录的脚本需要**两个后端实例 + 一个外部 LB + 一份活库**才能跑 ——
> CI 环境里没有这个形态，硬做成门禁只会得到一个恒红或恒绿的假信号。
> 故按「手动冒烟工具」单列，用法见下。

## 背景

`openspec/archive/2026-10-14-call-multi-instance` 交付时，**多实例能力未经实跑验证**
（交付环境无 LB、无双实例）。README §4.7 当时写的是「上线前请在真实 LB 环境做一次
『A、B 分属不同实例』的双实例通话冒烟」—— 但**没有给工具**。

本目录补上这个工具：四个脚本覆盖当时登记为「未运行项」的三项（多实例实跑 / LB 分流 /
Redis 不可用路径），并追加一项并发隔离冒烟。

## 前置条件

1. **活库 + Redis**：本机 MySQL 5.7（`easychat` 库）与 Redis 均已启动。
2. **两个后端实例**（同一 MySQL、同一 Redis）：

   ```bash
   cd easychat-java
   mvn -DskipTests package
   # 实例 A
   java -jar target/easychat-1.0.jar --server.port=5050 --ws.port=5051
   # 实例 B
   java -jar target/easychat-1.0.jar --server.port=5055 --ws.port=5056
   ```

3. **测试账号**：

   ```bash
   mysql -uroot -proot -D easychat --default-character-set=utf8mb4 < scripts/smoke/seed-test-users.sql
   ```

4. **依赖**：`ws` 客户端库复用 `easychat-front` 已装的依赖（**不新增任何生产依赖**），
   故需先在 `easychat-front` 执行过 `npm install`。

## 四个脚本

| 脚本 | 验证什么 | 关键判据 |
|---|---|---|
| `multi-instance-smoke.mjs` | 直连双实例的完整通话生命周期 | INVITE / JOIN / SIGNAL×2 / HANGUP 五步**全部跨实例送达** |
| `multi-instance-smoke.mjs --control` | **反例对照**：证明上面那条断言**有判别力** | 广播关闭时 INVITE **必须送不到**，且房间**必须已建在 Redis** |
| `multi-instance-lb.mjs` | LB 分流形态（**内置 TCP 代理**或**真实 nginx**） | **前提断言**：两个客户端**确实落在不同实例**；再跑五步信令 |
| `multi-instance-redis-down.mjs` | Redis 不可用时的失败语义 | 发起方收到 `code=2803`（**不静默**），且到期**自动恢复** |
| `multi-instance-concurrency.mjs` | 4 路并发下的**信令隔离** | 不串房、不丢帧、不乱序（**隔离性**，非容量测试） |
| `multi-instance-hangup-race.mjs` | 双方**同时挂断**的竞态 | 同一通话**只能落一条 `call_log`**（2026-10-21 修「重复落库」缺陷；需本机有 mysql CLI 核对行数） |

## 用法

```bash
node scripts/smoke/multi-instance-smoke.mjs
node scripts/smoke/multi-instance-lb.mjs
node scripts/smoke/multi-instance-concurrency.mjs
node scripts/smoke/multi-instance-redis-down.mjs
node scripts/smoke/multi-instance-hangup-race.mjs
```

### 真实 nginx 形态（`MI_LB_MODE=external`）

默认的 LB 是脚本自带的**连接级 round-robin TCP 代理** —— 它只做 TCP 转发，
**不处理 HTTP 层**（Host 头、upgrade 头、读超时…）。要验真实 LB，用容器化 nginx：

```bash
# 1) 起 nginx（配置见 deploy/nginx/nginx-lb.conf.template）
docker run -d --name easychat-lb -p 8088:8088 -p 8099:8099 \
  -v "$PWD/deploy/nginx/nginx-lb.conf.template:/etc/nginx/nginx.conf:ro" \
  -v "$PWD/deploy/nginx/logs:/var/log/nginx" \
  nginx:alpine

# 2) 跑冒烟（分流证据取自 nginx 自己的 access log —— $upstream_addr）
MI_LB_MODE=external MI_LB_HTTP=8088 MI_LB_WS=8099 \
MI_NGINX_LOG="$PWD/deploy/nginx/logs/lb_access.log" \
  node scripts/smoke/multi-instance-lb.mjs
```

> **为什么要读 nginx 的日志而不是让脚本自己记账**：内置代理模式下「A 落在哪个副本」
> 是脚本自己记的账（可能自说自话）；外部模式下证据来自**被测系统本身**。
> 日志样例（两个客户端 → 两个不同副本，`101` = Switching Protocols）：
> ```
> 2026-10-21T07:37:07+00:00|GET|/ws|101|192.168.65.254:5051
> 2026-10-21T07:37:07+00:00|GET|/ws|101|192.168.65.254:5056
> ```

反例对照需要**先把实例 A 的广播关掉**（这是前提，不是脚本能自己改的）：

```bash
java -jar target/easychat-1.0.jar --server.port=5050 --ws.port=5051 \
     --easychat.call.broadcast-signaling=false
node scripts/smoke/multi-instance-smoke.mjs --control
```

## 退出码

| 码 | 含义 |
|---|---|
| `0` | 全部符合预期 |
| `1` | 有断言失败 |
| `2` | **环境前提不成立**（实例没起、Redis 没被真正阻塞、LB 没把连接分开…） |

> `2` **不得**被当成通过 —— 它表示「这次根本没测到东西」，与「测了且通过」是两回事
> （`AGENTS.md` §2.1 第 3 条：静态/动态断言都有固有上限，超出部分要主动登记而非假装覆盖）。

## 环境变量

| 变量 | 默认 | 用途 |
|---|---|---|
| `MI_HTTP_A` / `MI_HTTP_B` | `5050` / `5055` | 两实例的 HTTP 端口 |
| `MI_WS_A` / `MI_WS_B` | `5051` / `5056` | 两实例的 WS 端口 |
| `MI_LB_HTTP` / `MI_LB_WS` | `8088` / `8099` | 脚本自带 round-robin LB 的入口端口 |
| `MI_LB_MODE` | （内置） | 设为 `external` 则**不**起内置代理，改用外部 LB（真实 nginx） |
| `MI_NGINX_LOG` | （空） | 外部 LB 模式下指定 nginx access log 路径 —— 分流证据从**它**读出 |
| `MI_RACE_ROUNDS` | `20` | `hangup-race` 脚本的竞态轮数 |
| `MI_DB` / `MI_DB_USER` / `MI_DB_PASS` / `MI_DB_HOST` / `MI_DB_PORT` | `easychat`/`root`/`root`/`127.0.0.1`/`3306` | `hangup-race` 核对 `call_log` 用的活库连接参数 |
| `MI_REDIS_PORT` | `6379` | Redis 端口 |
| `MI_TEST_PASSWORD` | `Test12345` | 测试账号口令（须与种子 SQL 一致） |
| `MI_REDIS_BLOCK` | `45` | `redis-down` 脚本阻塞 Redis 的秒数 |
| `MI_REDIS_WAIT_MS` | `40000` | `redis-down` 脚本等待错误帧的毫秒数（**须 > 15s**，见下） |
| `MI_BURST` | `50` | `concurrency` 脚本每路的突发帧数 |

## 踩过的坑（别改回去）

1. **5060/5061 不能用** —— 这两个端口在 WHATWG fetch 的**禁用端口表**里
   （SIP），`fetch` 直接报 `bad port`，浏览器同样会拒。默认端口特意避开了它们。
2. **不要用 `spawnSync`/`execFileSync` 后立刻 `process.exit()`** —— Windows 上会触发
   libuv `UV_HANDLE_CLOSING` 断言（实测 exit=`-1073740791`），汇总结果也不可靠。
   本目录全部走原生 RESP，不 spawn 子进程。
3. **Redis 不可用要用 `DEBUG SLEEP` 模拟，不要停服务** —— 本机 Redis 是 Windows 服务
   且会话通常非管理员：停得起来却起不回来，会把开发机留在残局。`DEBUG SLEEP` 无需权限
   且到期自动恢复。
4. **验证码要取真值** —— 走产品自己写入/校验的那条路径（从 Redis 读
   `easychat:checkcode:<key>` 再喂回去），不是绕过校验。
5. **`redis-down` 的等待窗口必须 > 15s** —— Redisson 单次命令超时默认 3s、
   自动重试已关闭（`RedisConfig` 显式 `setRetryAttempts(0)`），故 2803 约在**阻塞后 7s**
   到达（建房两步：`HSETNX` + `EXPIRE`，各 3s）。窗口太短会误判成「没收到」。
   ⚠ 修复**前**是 3s × (1+3 次重试) + 3×1.5s 间隔 ≈ **28s**，且同一错误帧会被投递
   **5 份**（1 份本地 + 4 份 Redisson 重试排队后一起执行的 `PUBLISH`）。
