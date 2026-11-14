# 打包 DPGrabber PC（Windows，PyInstaller）

在 Windows 机器上执行（Python 3.11+，建议 3.12/3.13；参考工具用
Python 3.14 打包，3.11+ 均可）：

```bat
pip install -r requirements.txt
pip install pyinstaller
pyinstaller --noconfirm --clean --onefile --windowed ^
    --name DPGrabberPC ^
    dpgrabber_pc.py
```

产物在 `dist\DPGrabberPC.exe`。`--windowed` 表示不弹出控制台窗口；
如需看日志排错，可去掉 `--windowed` 改用控制台版。

## Qt（PySide6）打包说明

- PyInstaller 对 PySide6 有专用 hook（`hook-PySide6.py`），一般无需
  手动加 `--hidden-import`：`dpgrabber_pc.py` 里 `import` 的 Qt 模块
  （QtWidgets / QtCore / QtGui）会被自动收录；`win32mica` 是纯
  ctypes 封装，也会被正常打包。
- `--onefile` 每次启动都要把 Qt 解包到临时目录，冷启动比 `--onedir`
  慢；如需更快启动，可改用 `--onedir` + 安装器分发。
- 体积：PySide6-Essentials 的 GUI 层约 60–80 MB，完整 PySide6 约
  150–250 MB；实测一个 `--onefile` 的 PySide6 应用约 100 MB 左右，
  明显大于旧 tkinter 版（约 20–30 MB）。可用 `--exclude-module`
  剔除不用的 Qt 模块（如 `PySide6.QtQml`、`PySide6.QtNetwork` 若未用）
  再压小，但不要排除 `QtWidgets`/`QtCore`/`QtGui`。
- 也可以用 `pyside6-deploy`（Nuitka 封装）打包；6.12 起它默认生成
  standalone 文件夹，`--mode onefile` 才出单文件。
- 真实 Mica 玻璃只在 Windows 11 上生效（DWM
  `DWMWA_SYSTEMBACKDROP_TYPE`）；Windows 10 或 Mica 调用失败时应用
  仍正常运行，只是没有毛玻璃背景——`ui.py` 里的 `_apply_mica` 已做
  try/except 保护。

## 打包后注意事项

- 首次运行会在 exe 所在目录生成 `dpgrabber_config.json`（模板，账号
  密码为空）。配置文件不打包进 exe，和 exe 放在同一目录即可。
- 加密密钥在配置文件中，不在 exe 里写死默认值之外的任何账号信息。
- 如需换图标，加 `--icon app.ico`（本仓库未附图标文件）。
- 打包后请在干净的 Windows 环境先做冒烟测试：双击启动、界面打开、
  配置文件生成、登录一次真实账号验证（不要用他人的账号测试）。
- 版本号建议在发版时写进文件名，如 `DPGrabberPC-1.0.0.exe`。
