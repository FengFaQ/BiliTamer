# memory/ — 项目记忆与交接

这个目录不属于上游项目，是 **FengFaQ/BiliTamer 独立维护线**自己的「记忆文件」，
用来回答三个问题：

1. **现在是什么状态？**（`PROJECT-STATE.md`）
2. **为什么做成这样？接手要怎么做？**（`DECISIONS.md` / `HANDOVER.md`）
3. **上次干到哪、环境怎么搭、锚点在哪？**（`SESSION-LOG.md` / `ENVIRONMENT.md` / `ANCHORS-6.7.1.md`）

## 阅读顺序（新会话/新接手者）

1. [`PROJECT-STATE.md`](PROJECT-STATE.md) —— 一页纸看懂：版本、分支、产物、已知问题、待办。
2. [`HANDOVER.md`](HANDOVER.md) —— 动手前必读：构建、发版、验证口径、禁区。
3. [`DECISIONS.md`](DECISIONS.md) —— 关键取舍与曾被否掉的方案（避免重复踩坑）。
4. [`ANCHORS-6.7.1.md`](ANCHORS-6.7.1.md) —— 宿主锚点速查，换版本时从这里开始校。
5. [`ENVIRONMENT.md`](ENVIRONMENT.md) —— WSL 里怎么重建逆向/构建环境。
6. [`SESSION-LOG.md`](SESSION-LOG.md) —— 按时间记录每次实质进展。
7. [`ROADMAP.md`](ROADMAP.md) —— 接下来值得做的事。
8. [`signing/`](signing/README.md) —— 发布签名密钥的**加密 zip**（密码不在仓库，由维护者保管）。

## 维护纪律

* **只写事实与可复核的结论**：写「已验证」必须附证据（日志行、dex 类名、命令），
  没上机验证就明说「未上真机」。
* **不要把密钥/令牌/口令写进本目录**：SSH 私钥、PAT 记录只留在本机
  `~/.ssh/`（Windows 侧 `C:\Users\Administrator\.ssh\`），本仓库只写「去哪找」。
* **每次实质改动后**：更新 `PROJECT-STATE.md` 的版本/提交/产物三项，并在
  `SESSION-LOG.md` 追加一条（日期 + 做了什么 + 验证到哪一步 + 下一步）。
* 更细的技术坑在仓库根部 [`PITFALLS.md`](../PITFALLS.md)；本目录只做索引与交接，
  不复制技术细节（避免两处各写一份、最后对不上）。
