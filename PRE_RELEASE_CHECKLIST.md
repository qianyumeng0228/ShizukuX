# ShizukuX 预发布清单（13.7.0.k 系列）

适用范围：本地预发布构建，版本名 `ShizukuX 13.7.0.kXXXX`，`versionCode = kXXXX`。
正式版（`rXXXX`）走 GitHub Actions 出包，这份清单只管 k 系列。

上一版清单停在 13.6.0（2026-05-18），描述的「Merge to master → Actions 出包 → Sentry release」
已不适用，按当前实际流程重写。

---

## 一、改完代码后

- [ ] `git status` 干净，本次改动已全部提交
- [ ] `AI_DEVLOG.md` 补了对应版本一节（结论、真机证据、未覆盖项）
- [ ] `CHANGELOG.md` 的 `[Unreleased]` 加了条目
- [ ] 本地跑一次静态检查：`bash pre-push-guard/scripts/pre_push_check.sh`（24 项应全 PASS，
      第 12 项 printStackTrace 是 WARN，不计数）

## 二、构建三包

版本号每次递增，不要复用。

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.13.21-hotspot'
$env:GRADLE_OPTS='-Xmx4g'
& 'F:\shizukux\ShizukuX\gradlew.bat' -p 'F:\shizukux\ShizukuX' `
  :manager:assembleDropinRelease :manager:assembleShizukuXRelease `
  -PshizukuVersionCode=<K> '-PshizukuVersionName=ShizukuX 13.7.0.k<K>' `
  *> 'I:\shizukux\_build\build_k<K>.log'
Select-String -Path 'I:\shizukux\_build\build_k<K>.log' -Pattern 'BUILD SUCCESSFUL|BUILD FAILED|^e: '
```

- [ ] 日志里是 `BUILD SUCCESSFUL`，没有 `e:` 开头的编译错误
- [ ] 构建约 4–6 分钟；前台等待会被转后台，用 `TaskOutput` 等结果，不要用 `| Select-String` 接在构建命令后

## 三、打 oppo-testkey 包

顺序必须是 **zipalign → apksigner**，反过来签名会失效。

```powershell
$bt='C:\Users\32818\AppData\Local\Android\Sdk\build-tools\<最新>'
& "$bt\zipalign.exe" -p -f 4 <dropin.apk> <aligned.apk>
& "$bt\apksigner.bat" sign --ks 'I:\shizukux\_build\testkey\testkey.jks' `
  --ks-pass pass:android --key-pass pass:android --ks-key-alias testkey `
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true `
  --out <oppo-testkey.apk> <aligned.apk>
```

- [ ] zipalign 在前，apksigner 在后
- [ ] 删掉 apksigner 生成的 `.idsig`
- [ ] `apksigner verify --print-certs` 的证书 SHA-256 为
      `a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc`
- [ ] dropin / standard 的签名是项目自建 `a5451d07…b6767`，不要混签

## 四、产物归档

目录：`I:\shizukux\dist\ShizukuX-13.7.0.k<K>\`

- [ ] `ShizukuX-13.7.0.k<K>-dropin.apk`（包名 `moe.shizuku.privileged.api`）
- [ ] `ShizukuX-13.7.0.k<K>-standard.apk`（包名 `xyz.shizuku.extra.api`）
- [ ] `ShizukuX-13.7.0.k<K>-oppo-testkey.apk`（同 dropin 包名，testkey 单签名）
- [ ] `README.md`：本版改了什么、真机测量、未覆盖项、三包 SHA-256、安装说明
- [ ] 三个包的字节数与 SHA-256 写进 README

## 五、真机验证（`bc757a76`，小米 13 Pro / Android 16）

安装：

```powershell
adb -s bc757a76 install -r <dropin.apk>   # 版本号低于机上版本时加 -d
```

- [ ] 冷启动不崩，首页卡片正常
- [ ] 设备所有者页直连激活：失败对话框同时给出「应用自身身份」与「shell 身份」两段日志
- [ ] 首页 ↔ 授权页横滑：慢速 250px / 600ms 双向都只翻一页
- [ ] 横滑过程中页面随手指 1:1 位移，卡片不保持按下缩小
- [ ] `dumpsys gfxinfo <pkg>` reset 后滑一次：0 jank，90th ≤ 17ms
- [ ] `logcat -b system` 过滤 `ibrat|haptic`：0 条
- [ ] 停服务再启动，状态卡与列表能自己恢复
- [ ] 服务启动路径（Root / ADB / 无线 ADB）至少各跑一次

## 六、推送与远端

```powershell
git -C F:\shizukux\ShizukuX push origin master
git -C F:\shizukux\ShizukuX push gitee master
```

- [ ] 两个远端都指向同一个 commit：`git rev-list --left-right --count origin/master...master` 得 `0 0`
- [ ] GitHub Actions 三项绿：`pre-push-guard` / `lint` / `build`
- [ ] `Build App` 的 artifact 里有 Standard / Drop-in / Compat Stub
- [ ] 若 Actions 报 `pre-push-guard` 失败，先在本地跑同一脚本复现，**不要直接推第二次**

## 七、已知未覆盖（每次发布照抄进 README）

- [ ] `binder_firewall` 开启会拦截 `transactRemote`，激活前需关闭该特性
      （代码层只有 README 提示，没有前置校验）
- [ ] ColorOS 真机未验证：`mService` 字段名与 `IDevicePolicyManager$Stub.asInterface`
      按 AOSP 写，只在 HyperOS 跑通
- [ ] 开机恢复写回未在真实重启里验证；`BootRestoreReceiver` 等 service 最多 90 秒，
      超过广播 `goAsync` 窗口，若 ColorOS 不生效需改 WorkManager 续跑
- [ ] 冷启动后首次横向触摸可能不生效（窗口首帧未 ready），第二次起正常

## 八、发布后

- [ ] 更新 `docs/zh-CN/latest-release-notes.md`
- [ ] 有 OPPO 反馈时，收一份 `setDeviceOwner` 的失败日志再判断是否 ROM 侧限制

---

**当前基线**：13.7.0.k2813，已装 `bc757a76`（APK 构建于 `2f32c618`，本文档补记于其后一次提交）
**维护人**：qianyumeng0228
