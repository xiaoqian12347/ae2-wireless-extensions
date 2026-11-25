# AE2 Wireless Extensions

面向 Minecraft 1.12.2 和 AE2 UEL v0.56.7 的范围无线连接设备。

### 无线信号发生器使用说明

- **有线输入：** 仅支持底面 ME 接口。选择 YM 频段后切换为无线输入，清除频段后恢复有线输入。
- **无线输入：** 需安装 [YM Additions](https://github.com/xinyihl/YM-Additions/releases/tag/v0.2.0)。通过 YM 无线连接器建立发送端，再在发生器中选择频段。
- **频道管理：** 可设置无线输出频道上限，不超过输入总频道数。频道不足时优先保留手动绑定和较早连接的设备。设为 0 时断开全部输出，但保留绑定记录。
- **自动模式：** 自动连接范围内符合条件的独立 ME 设备，并在网络变化后重新检测。不支持线缆及其挂载部件。
- **手动模式：** 使用 AE2 内存卡与福鲁伊克斯水晶合成频道绑定卡。潜行右键发生器绑定，右键设备连接或断开。切换模式不会断开现有连接。
- **连接管理：** 可查看、断开和重新连接设备。滚轮翻页，双击坐标可定位并高亮方块。「重扫」用于自动搜索或重试已有连接。
- **范围设置：** 以发生器为中心，按方块半径计算。点击左侧范围图标，可显示范围边框及设备连线，关闭界面后仍可查看，再次点击关闭。

## 开发

使用 JDK 25 和 Cleanroom 0.7.4-alpha。Gradle 会下载并反混淆 AE2 UEL v0.56.7。

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-25.0.2'
.\gradlew.bat runClient
.\gradlew.bat build
```

发布文件为 `build/libs/ae2-wireless-extensions-1.0.0.jar`。
