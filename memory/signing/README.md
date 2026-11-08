# 签名密钥（加密压缩包）/ Signing key (encrypted archive)

> ⚠️ **这是一个加密 zip（AES-256）**。压缩包密码**不保存在本仓库的任何文件里**，
> 由项目维护者单独保管。只有拿到密码的人才能解开它。
> **This is an encrypted ZIP (AES-256).** The archive password is **not stored anywhere
> in this repository** — the maintainer holds it separately.

## 文件

| 文件 | 内容 |
| --- | --- |
| [`BiliTamer-signing-key-encrypted.zip`](BiliTamer-signing-key-encrypted.zip) | 加密压缩包：`bilitamer.jks`（发布签名密钥库）+ `signing.local`（构建配置模板）+ `README.txt`（包内说明） |

* 压缩包内的 `bilitamer.jks` 是 PKCS12 密钥库（别名 `bilitamer`，RSA 2048），
  当前所有 Release APK 都由它签名——**必须用它签后续版本**，否则已安装用户无法原地升级，
  只能卸载重装。
* 压缩包内 `README.txt` 写了恢复步骤（解压 → 复制 `signing.local` 到 `tools/signing.local` →
  改 `KS_PATH` → `python3 tools/build_module.py`）。
* 压缩包密码由维护者单独保管，**不写进任何提交、Issue、PR 或文档**。

## 安全提示（务必读）

* 这是**公开仓库**。加密 zip 只是防止「随手克隆就能拿到私钥」，**不是强保护**：
  压缩密码强度有限，离线暴力破解是可行的。若你打算长期维护，建议：
  * 把仓库改为 private，或
  * 轮换一套新密钥（`keytool -genkeypair`）并把本文件从历史中移除，或
  * 至少换成高熵密码重新打包。
* 私钥泄露 = 任何人都能发布可被现有用户「原地升级」的伪造 APK。发现泄露请立刻轮换。
* 本目录只放**加密后的**产物；仓库的 `.gitignore` 已忽略 `*.jks` / `signing.local` /
  `*.keystore` / `*.pem`，不要把这些明文提交进来。

## 为什么放在 `memory/`

它属于「交接基础设施」：新环境没有同签名密钥就没法发布可升级的版本。
放在记忆目录旁边，接手的人读 `memory/HANDOVER.md` 时能一并找到——
但**是否解开、何时解开，由维护者决定**。
