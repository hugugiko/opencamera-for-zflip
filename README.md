# opencamera-for-zflip

这是一个基于开源项目 Open Camera 的衍生项目，专门为三星 Z Flip 魔改小方块构建的特殊版本。

对画面进行了部分调整，并对视频进行自动处理一确保画面没有被系统镜像影响。

## 项目说明

本项目保留 Open Camera 的核心拍照视频功能，并针对 Z Flip 小方块做了定制化处理：

- 对拍摄画面以及录制视频镜像重新画面处理
- 对预览画面镜像显示
- 删除前后摄像头切换功能
- 将原“切换前后置功能”位置替换为“解锁自由旋转”功能

## 已知问题

- 当前旋转处理仅在“正方向显示”时才能正确处理自由旋转方向，请在运行时确保“wm user-rotation -d 1 lock 0“
- 在内屏模式、双屏模式运行可能存在问题，请使用折叠或者帐篷模式运行本app

如遇到其他问题请联系我，我会尝试更正。

## 构建与运行

项目基于 Android Gradle 构建。

1. 克隆仓库
```bash
git clone https://github.com/hugugiko/opencamera-for-zflip.git
cd opencamera-for-zflip
```
2. 使用 Gradle 构建
```bash
./gradlew assembleDebug
```
3. 安装 APK
```bash
./gradlew installDebug
```

## 声明与引用

本项目部分内容由Google Gemini 3 Flash完成协助开发

本项目基于开源项目 Open Camera 进行改造。Open Camera 由 Mark Harman 维护，原项目地址：

https://sourceforge.net/projects/opencamera/

感谢 Open Camera 提供的开源基础与稳定相机框架。