// DPGrabber iOS app - Advanced settings.
//
// 店铺 ID、请求超时、AES 密钥、TLS 开关。密钥以十六进制文本保存，
// 与 PC 版 / DPGrabber 插件同一套密钥；修改后点「保存设置」生效
// （监听运行时修改密钥需先停止再开始）。

import SwiftUI

@MainActor
struct SettingsView: View {
    @ObservedObject var viewModel: AppViewModel
    @Environment(\.presentationMode) private var presentationMode

    var body: some View {
        Form {
            Section(header: Text("店铺与请求")) {
                HStack {
                    Text("店铺 ID")
                    Spacer()
                    TextField("92", text: intBinding(
                        get: { viewModel.config.shopID },
                        set: { viewModel.config.shopID = $0 }
                    ))
                    .keyboardType(.numberPad)
                    .multilineTextAlignment(.trailing)
                    .frame(width: 100)
                }
                .disabled(viewModel.connectionEditingDisabled)
                HStack {
                    Text("请求超时（秒）")
                    Spacer()
                    TextField("15", text: doubleBinding(
                        get: { viewModel.config.requestTimeoutSec },
                        set: { viewModel.config.requestTimeoutSec = $0 }
                    ))
                    .keyboardType(.decimalPad)
                    .multilineTextAlignment(.trailing)
                    .frame(width: 100)
                }
                Text("服务器证书校验始终开启")
                Text("正式版本不支持绕过 TLS 证书校验。")
                    .font(.footnote)
                    .foregroundColor(.secondary)
            }

            Section(header: Text("AES 密钥（十六进制）"),
                    footer: Text("主密钥为 DPGrabber 插件的 AES-256 密钥，备用密钥为 PC 工具的 AES-128 密钥；服务端用哪套由解密结果自动判断，无需手动选择。")) {
                TextField("主密钥（AES-256，64 位十六进制）", text: $viewModel.config.aesKeyHex)
                    .autocapitalization(.none)
                    .disableAutocorrection(true)
                    .font(.system(.body, design: .monospaced))
                TextField("主 IV（32 位十六进制）", text: $viewModel.config.aesIVHex)
                    .autocapitalization(.none)
                    .disableAutocorrection(true)
                    .font(.system(.body, design: .monospaced))
                TextField("备用密钥（AES-128，32 位十六进制）", text: $viewModel.config.aesKeyHexAlt)
                    .autocapitalization(.none)
                    .disableAutocorrection(true)
                    .font(.system(.body, design: .monospaced))
                TextField("备用 IV（32 位十六进制）", text: $viewModel.config.aesIVHexAlt)
                    .autocapitalization(.none)
                    .disableAutocorrection(true)
                    .font(.system(.body, design: .monospaced))
                Button("恢复默认密钥") { viewModel.resetAESKeys() }
            }
            .disabled(viewModel.connectionEditingDisabled)

            Section(header: Text("店铺等待与定时休息")) {
                Toggle("使用店铺等待表", isOn: $viewModel.config.useShopWait)
                ForEach(viewModel.config.confirmWaitByShop.keys.sorted(), id: \.self) { sid in
                    Stepper("店铺 \(sid)：\(String(format: "%.1f", viewModel.config.confirmWaitByShop[sid] ?? 0)) 秒",
                        value: Binding(get: { viewModel.config.confirmWaitByShop[sid] ?? 0 },
                                       set: { viewModel.setShopWait(sid, seconds: $0) }), in: 0...120, step: 0.5)
                }
                Stepper("其他店铺等待 \(String(format: "%.1f", viewModel.config.globalWaitSec)) 秒", value: $viewModel.config.globalWaitSec, in: 0...120, step: 0.5)
                Toggle("运行后定时休息（与账号轮换独立）", isOn: $viewModel.config.runRestEnabled)
                TextField("运行时长（秒）", text: doubleBinding(get: { viewModel.config.runDurationSec }, set: { viewModel.config.runDurationSec = $0 }))
                    .keyboardType(.decimalPad)
                TextField("休息时长（秒）", text: doubleBinding(get: { viewModel.config.restDurationSec }, set: { viewModel.config.restDurationSec = $0 }))
                    .keyboardType(.decimalPad)
                Text("当前仅支持单账号；多账号轮换尚未实现。进入后台将停止，不使用静音音频保活。")
                    .font(.footnote).foregroundColor(.secondary)
            }

            Section(header: Text("其他")) {
                HStack {
                    Text("订单去重时间（秒）")
                    Spacer()
                    TextField("60", text: doubleBinding(
                        get: { viewModel.config.seenTTLSec },
                        set: { viewModel.config.seenTTLSec = $0 }
                    ))
                    .keyboardType(.decimalPad)
                    .multilineTextAlignment(.trailing)
                    .frame(width: 100)
                }
            }

            Section {
                Button("保存设置") {
                    if viewModel.saveSettings() { presentationMode.wrappedValue.dismiss() }
                }
            }
        }
        .navigationTitle("高级设置")
        .navigationBarItems(trailing: Button("完成") {
            presentationMode.wrappedValue.dismiss()
        })
    }

    private func intBinding(get: @escaping () -> Int, set: @escaping (Int) -> Void) -> Binding<String> {
        Binding<String>(
            get: { String(get()) },
            set: { set(Int($0) ?? get()) }
        )
    }

    private func doubleBinding(get: @escaping () -> Double, set: @escaping (Double) -> Void) -> Binding<String> {
        Binding<String>(
            get: {
                let value = get()
                return value == value.rounded() && abs(value) < 1_000_000
                    ? String(Int(value)) : String(value)
            },
            set: { text in if let value = Double(text), value.isFinite { set(value) } }
        )
    }
}
