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

## 打包后注意事项

- 首次运行会在 exe 所在目录生成 `dpgrabber_config.json`（模板，账号
  密码为空）。配置文件不打包进 exe，和 exe 放在同一目录即可。
- 加密密钥在配置文件中，不在 exe 里写死默认值之外的任何账号信息。
- 如需换图标，加 `--icon app.ico`（本仓库未附图标文件）。
- 打包后请在干净的 Windows 环境先做冒烟测试：双击启动、界面打开、
  配置文件生成、登录一次真实账号验证（不要用他人的账号测试）。
- 版本号建议在发版时写进文件名，如 `DPGrabberPC-1.0.0.exe`。
