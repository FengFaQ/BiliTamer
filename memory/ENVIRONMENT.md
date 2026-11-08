# 环境与工具 / Environment

> 记录「在哪台机器/什么环境里做出来的」，方便下次重建。
> 注意：这些是**开发机环境**，不属于仓库依赖；仓库构建只要求 JDK + Python3。

## 1. 本机环境现状（2026-11-08）

| 项 | 值 |
| --- | --- |
| 宿主机 | Windows（Administrator），WSL2 + Ubuntu **24.04.3 LTS** |
| WSL JDK | OpenJDK **17.0.20.1**（`/usr/lib/jvm/java-17-openjdk-amd64`） |
| Python | 3.12.3（系统）；逆向分析用 venv：`/root/work/venv`（androguard **4.1.4**） |
| 反编译 | `apktool` 2.7.0（apt）；jadx 1.5.0 曾尝试下载，但本机直连 GitHub Release 资产失败（curl exit 56），未装成 |
| 宿主 APK 源 | `C:\Users\Administrator\Documents\dsh\biliibli\bilibili - Anime · Video HD_6.7.1_APKPure.xapk`（478 MB，**不入库**，GitHub 单文件 100 MB 上限） |
| 解包工作区（临时） | `/root/work/apk/base/classes*.dex`（base.apk 的 34 个 dex） |
| 签名密钥 | 开发机本地 `~/bilitamer-build/bilitamer.jks`（**不入库**，`.gitignore` 忽略 `*.jks` / `signing.local`） |

## 2. 重建逆向环境（新机器 / 新 WSL）

```bash
# 1) 基础
sudo apt-get update && sudo apt-get install -y python3 python3-venv unzip zip default-jdk
# 2) DEX 分析（够用：解析、xref、指令级证据）
python3 -m venv ~/work/venv && ~/work/venv/bin/pip install androguard
# 3) 解包 xapk → base apk → dex
mkdir -p ~/work/apk && cd ~/work/apk
unzip -o "<path>/bilibili ... 6.7.1_APKPure.xapk" -d xapk
unzip -o xapk/com.bilibili.app.in.apk 'classes*.dex' -d base
```
需要「可读的反编译代码」时优先 try：`apktool d`（smali）或 `jadx`；
jadx 装上不动的备用方案是直接用 androguard 打印指令（本仓库的锚点证据就是这么取的）。

## 3. 网络限制（本机实测，会影响工具安装/发版验证）

* `git clone/push`（`ssh.github.com:443`）✅ 可用；
* `api.github.com` ✅ 可用（`gh` CLI 已登录 FengFaQ，fine-grained PAT 有 push/admin 权限）；
* GitHub Release **资产直链**（`objects.githubusercontent.com`）❌ 本机被墙，
  所以「下载 Release 里的 APK」要走别的网络或从仓库 `apk/` 目录取；
  不过 **CI 在 GitHub 侧创建 Release 并上传附件不受此影响**（云端执行）。
* npm/pip 镜像可用（pip 配了清华源时）。

## 4. 凭据位置（**不进仓库**）

* SSH 私钥/公钥、口令、PAT 记录：Windows `C:\Users\Administrator\.ssh\`
  （`id_rsa` / `id_rsa.pub` / `GITHUB_CREDENTIALS.md` / `config` 里 `github.com → ssh.github.com:443`）。
* 非交互 git push（本机实测）：
  ```powershell
  $ask = "$env:TEMP\bt_auth"; New-Item -ItemType Directory -Force -Path $ask | Out-Null
  Set-Content "$ask\askpass.cmd" '@echo <口令>' -Encoding Ascii
  $env:SSH_ASKPASS="$ask\askpass.cmd"; $env:SSH_ASKPASS_REQUIRE='force'; $env:DISPLAY='dsh'
  $env:PATH="$ask;$env:PATH"
  git push origin main
  ```
* 发 Release（云端）：推 `v*` tag 触发 `.github/workflows/release.yml`，不需要本机 PAT。

## 5. 构建的隐性依赖

* `tools/signing.local`（gitignored）：三行 `KS_PATH=` / `KS_PASS=` / `KS_ALIAS=`。
* `tools/engine/` 里 vendored 了 dx 与 apksig（Apache-2.0），无需联网。
* 单测脚本会自己找 `JAVA_HOME` 或 `/usr/lib/jvm/java-17-openjdk-amd64`。
