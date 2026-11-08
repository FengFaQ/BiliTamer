# 路线图 / Roadmap

> 优先级从高到低。做完一项就移到 `SESSION-LOG.md` 并在 `PROJECT-STATE.md` 更新状态。

## P0 · 真机验证（本版最大缺口）

目标：把「静态实证」升级成「真机 A/B」。有设备时按 [`HANDOVER.md`](HANDOVER.md) §5 执行。

- [ ] 装 `apk/BiliTamer-v1.10.2.apk`，LSPosed 启用 + 作用域勾选 B 站国际版，强停重开；
- [ ] 设置页开「每次起播套用默认倍速」= 1.5，播一个视频，日志出现
      `speed: armed …(stage=L2)` + `speed: applied 1.50 at start`；
- [ ] 播放中手动选 2.0x → 立即生效（不被改回）；暂停继续 → 回到 1.5x；
- [ ] 设置「更多倍速档位」= `0.5,0.75,1.0,1.25,1.5,1.75,2.0,2.5,3.0` → 重新打开倍速面板，
      日志 `speedmenu: applied 9 entries`，屏幕上确实是这 9 档且可点；
- [ ] 特别确认 2.0 档的选中态（宿主 `|1.99-2.0| < 0.01` 严格小于的既有问题）；
- [ ] 离线下载的视频里点 3.0x 的实际观感（自定义表绕过了宿主 gate）。

## P1 · 版本适配与健壮性

- [ ] **6.6.0 / 6.5.0 校准**：用同样方法（`docs/speed-menu-anchor-6.7.1.md` §7.3）定位
      这两版的 `setPlaySpeed` 类名与档位表构造类，补进候选表；补不了就在设置页明确写「仅 6.7.1」。
- [ ] `onPrepared` 帧缺失兜底：目前是「30 次非起播调用且从未 applied → 一条 warn」。
      可考虑再挂 `onPrepared` 的宿主类作第二信号（用形状找实现
      `IMediaPlayer.OnPreparedListener` 的类），但要先证明不会引入新的改写面。
- [ ] 「更多倍速」面板容量：9–12 档在真机上是否撑破布局/需要滚动（`docs/speed-menu-anchor-6.7.1.md` §8 未验）。

## P2 · 工程化

- [ ] 把「dex 锚点复算」固化成脚本：`tools/anchor_check.py <apk|dexdir>`，
      输出当前版本是否还命中全部锚点（现在靠文档里的临时脚本）。
- [ ] CI 增加单测 job（目前 `.github/workflows/release.yml` 只负责发 Release，
      单测只在本地跑）；顺手加「module_conf 版本 vs RELEASE_NOTES 小节」一致性检查。
- [ ] `module_conf.py` 的 `xposed_description` 越来越长：考虑按功能段落拆分或从 README 生成。

## P3 · 功能候选（未承诺）

- [ ] 「更多倍速」支持「追加/替换」两种模式（当前是替换；有人可能想保留原生 0.5x 而只加高档）。
- [ ] 默认倍速支持「按分区/按稿件」规则（需要可靠信号，先别做——`PITFALLS.md` §60/§63 的教训）。
- [ ] 把「导出日志」扩展成可导出到用户指定目录（当前固定下载目录/应用目录两档）。
