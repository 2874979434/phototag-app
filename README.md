# PhotoTag 手机发图端

ESP32-C3 + 1.54" 152×152 三色（黑/白/红）墨水屏的 Android 发图 App。
手机选一张照片 → 三色抖动 → 通过 BLE 传给板子 → 屏幕刷新。

## 为什么用 GitHub Actions 构建

不需要在本机装 Android SDK / Android Studio。推送代码即出 APK。

1. 打开仓库的 **Actions** 页 → `build-apk` → 最近一次运行
2. 页面底部 **Artifacts** 下载 `phototag-apk.zip`
3. 解压得到 `app-debug.apk` / `app-release.apk`，传到手机直接安装

## App 用法

1. **选择照片** → 从相册挑一张
2. 调三个滑杆（预览会实时更新）
   - **亮度 Gamma**：>1 提亮暗部，脸不糊成一团黑
   - **对比度**：>1 层次更清楚
   - **红色阈值**：调大更挑剔，只有接近纯红的区域才印成红
3. **连接设备** → 授权蓝牙 → 扫到 `PhotoTag-xxxx` 自动连接
4. **发送到屏幕** → 约 30 秒刷完

## 协议（和板子固件对齐）

```
服务   6e400001-b5a3-f393-e0a9-e50e24dcca9e
RX     6e400002-...   （手机 → 板子，Write Request）
TX     6e400003-...   （板子 → 手机，Notify）

每个包 = [1 字节命令][数据...]

0x01 [w_lo][w_hi][flags]   START，复位接收偏移
0x02 [...239 字节...]      DATA，顺序追加
0x03                       COMMIT，收满 5776 字节才刷屏
0x04                       CLEAR
```

关键约束（踩过的坑）：

- **每个包都必须以命令字节开头**。板子只把 `v[1..n]` 当数据，
  只在第一包加 `0x02` 会让后面 24 包全被当成未知命令丢弃。
- 包与包之间**必须留 20ms 间隔**，否则 ESP32 BLE 栈直接丢包。
- START 之后要等 **300ms**，因为板子会先发一条通知。
- 数据量固定 **5776 字节** = 黑白层 2888 + 红色层 2888
  （stride 19 × 152 行）。
- 位定义：黑白层 `1`=白 `0`=黑；红色层 `1`=红。

## 屏幕参数

| 项 | 值 |
|---|---|
| 分辨率 | 152 × 152 |
| 控制器 | SSD1680 |
| 颜色 | 黑 / 白 / 红（三色） |
| 全刷耗时 | 20–30 秒 |
| stride | 19 字节/行 |
| 单层大小 | 2888 字节 |

## 文件

```
app/src/main/java/com/lanone/phototag/MainActivity.java   全部逻辑（无 AndroidX）
app/src/main/AndroidManifest.xml                          蓝牙权限
app/build.gradle                                          compileSdk 34 / minSdk 26
.github/workflows/build.yml                               自动出 APK
```

## 备注

- `minSdk 26`：Android 8.0 以上。
- Android 12+ 走 `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`；Android 11 及以下走
  `ACCESS_FINE_LOCATION`，且系统要求开定位才能扫到 BLE 设备。
- release 包用 debug 签名，方便直接装；上架前要换正式签名。