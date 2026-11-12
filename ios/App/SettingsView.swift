// DPGrabber iOS app - Advanced settings.
//
// 店铺 ID、请求超时、AES 密钥、TLS 开关。密钥以十六进制文本保存，
// 与 PC 版 / DPGrabber 插件同一套密钥；修改后点「保存设置」生效
// （监听运行时修改密钥需先停止再开始）。

import SwiftUI

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
                Toggle("校验服务器证书（TLS）", isOn: $viewModel.config.verifyTLS)
                Text("关闭后将接受任意证书，仅用于排障；正常使用请保持开启。")
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
                    viewModel.saveSettings()
                    presentationMode.wrappedValue.dismiss()
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
            set: { set(Double($0) ?? get()) }
        )
    }
}
