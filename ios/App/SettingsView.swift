import SwiftUI

@MainActor
struct SettingsView: View {
    @ObservedObject var viewModel: AppViewModel
    @Environment(\.presentationMode) private var presentationMode
    @State private var showingProtocolSettings = false

    var body: some View {
        ScrollViewReader { proxy in
        Form {
            Section(footer: Text("设置会自动保存。店铺与协议参数只能在任务停止后修改，修改后需重新登录。")) {
                Label("按需调整，默认即可使用", systemImage: "gearshape.2.fill")
                    .font(.headline).foregroundColor(.accentColor)
            }
            Section(header: Text("店铺与连接")) {
                HStack {
                    Text("店铺 ID")
                    Spacer()
                    TextField("92", text: intBinding(
                        get: { viewModel.config.shopID }, set: { viewModel.config.shopID = $0 }))
                        .keyboardType(.numberPad).multilineTextAlignment(.trailing).frame(width: 100)
                        .accessibilityLabel("店铺 ID")
                }.disabled(viewModel.connectionEditingDisabled)
                numberRow("请求超时", unit: "秒", get: { viewModel.config.requestTimeoutSec }, set: { viewModel.config.requestTimeoutSec = $0 })
                Label("服务器证书校验已开启", systemImage: "lock.shield")
                    .font(.subheadline).foregroundColor(.secondary)
            }
            Section(header: Text("接单等待"), footer: Text("开启店铺等待表时使用对应店铺的等待时间；其他店铺使用通用等待时间。")) {
                Toggle("使用店铺等待表", isOn: $viewModel.config.useShopWait)
                if viewModel.config.useShopWait {
                    ForEach(viewModel.config.confirmWaitByShop.keys.sorted(), id: \.self) { sid in
                        Stepper("店铺 \(sid)：\(String(format: "%.1f", viewModel.config.confirmWaitByShop[sid] ?? 0)) 秒",
                                value: Binding(get: { viewModel.config.confirmWaitByShop[sid] ?? 0 },
                                               set: { viewModel.setShopWait(sid, seconds: $0) }),
                                in: 0...120, step: 0.5)
                    }
                }
                Stepper("通用等待：\(String(format: "%.1f", viewModel.config.globalWaitSec)) 秒",
                        value: $viewModel.config.globalWaitSec, in: 0...120, step: 0.5)
                VStack(alignment: .leading, spacing: 6) {
                    Stepper("等待微调：\(String(format: "%.3f", viewModel.config.autoWaitOffset)) 秒",
                            value: Binding(get: { viewModel.config.autoWaitOffset },
                                           set: { viewModel.config.autoWaitOffset = ($0 * 1000).rounded() / 1000 }),
                            in: -0.5...0.5, step: 0.05)
                    Text("仅店铺 50/105/1391 叠加；92 及其他店铺不应用")
                        .font(.footnote).foregroundColor(.secondary)
                }.id("wait-offset")
                VStack(alignment: .leading, spacing: 6) {
                    Stepper("满额冷却：\(viewModel.config.busyCooldownSec) 秒",
                            value: $viewModel.config.busyCooldownSec, in: 1...600, step: 5)
                    Text("同时接单名额满后暂停该时长再继续监听")
                        .font(.footnote).foregroundColor(.secondary)
                }
            }
            Section(header: Text("定时休息"), footer: Text("仅对前台运行生效。此功能不切换账号；切到后台仍会停止任务。")) {
                Toggle("运行一段时间后休息", isOn: $viewModel.config.runRestEnabled)
                if viewModel.config.runRestEnabled {
                    numberRow("运行时长", unit: "秒", get: { viewModel.config.runDurationSec }, set: { viewModel.config.runDurationSec = $0 })
                    numberRow("休息时长", unit: "秒", get: { viewModel.config.restDurationSec }, set: { viewModel.config.restDurationSec = $0 })
                }
            }
            Section(header: Text("重复订单"), footer: Text("短时间内不重复处理同一订单。结果未知的订单不会自动再次提交。")) {
                numberRow("去重时间", unit: "秒", get: { viewModel.config.seenTTLSec }, set: { viewModel.config.seenTTLSec = $0 })
            }
            Section(header: Text("协议兼容"), footer: Text("无需手动选择加密方案。除非排查连接问题，否则请保留默认值；正式版本不支持绕过 TLS 证书校验。")) {
                DisclosureGroup(isExpanded: $showingProtocolSettings) {
                    VStack(alignment: .leading, spacing: 16) {
                        Text("修改密钥前请停止任务。参数必须与服务端一致，错误值会导致连接失败。")
                            .font(.footnote).foregroundColor(.secondary)
                        protocolField("主密钥 · AES-256", hint: "64 位十六进制", text: $viewModel.config.aesKeyHex)
                        protocolField("主 IV", hint: "32 位十六进制", text: $viewModel.config.aesIVHex)
                        protocolField("备用密钥 · AES-128", hint: "32 位十六进制", text: $viewModel.config.aesKeyHexAlt)
                        protocolField("备用 IV", hint: "32 位十六进制", text: $viewModel.config.aesIVHexAlt)
                        Button("恢复默认密钥") { viewModel.resetAESKeys() }
                    }.padding(.vertical, 12).disabled(viewModel.connectionEditingDisabled)
                } label: {
                    Label("加密参数", systemImage: "key.fill")
                }
            }
            Section {
                Button {
                    if viewModel.saveSettings() { presentationMode.wrappedValue.dismiss() }
                } label: {
                    Label("保存并返回", systemImage: "checkmark.circle")
                        .font(.headline)
                }
            }
        }
        .onAppear {
            #if DEBUG
            if UserDefaults.standard.bool(forKey: "uiReviewWaitSection") {
                DispatchQueue.main.async { proxy.scrollTo("wait-offset", anchor: .top) }
            }
            #endif
        }
        .navigationTitle("高级设置")
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarItems(trailing: Button("完成") { presentationMode.wrappedValue.dismiss() })
        .alert(isPresented: viewModel.hasError) {
            Alert(title: Text("设置提示"), message: Text(viewModel.lastError ?? ""),
                  dismissButton: .default(Text("好")) { viewModel.lastError = nil })
        }
        }
    }

    private func numberRow(_ title: String, unit: String, get: @escaping () -> Double, set: @escaping (Double) -> Void) -> some View {
        HStack {
            Text(title)
            Spacer()
            TextField("0", text: doubleBinding(get: get, set: set))
                .keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(width: 100)
                .accessibilityLabel(title)
            Text(unit).foregroundColor(.secondary)
        }
    }

    private func protocolField(_ title: String, hint: String, text: Binding<String>) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.subheadline)
            TextField(hint, text: text)
                .autocapitalization(.none).disableAutocorrection(true)
                .font(.system(.footnote, design: .monospaced))
                .textFieldStyle(RoundedBorderTextFieldStyle())
                .accessibilityLabel(title)
        }
    }

    private func intBinding(get: @escaping () -> Int, set: @escaping (Int) -> Void) -> Binding<String> {
        Binding<String>(get: { String(get()) }, set: { set(Int($0) ?? get()) })
    }

    private func doubleBinding(get: @escaping () -> Double, set: @escaping (Double) -> Void) -> Binding<String> {
        Binding<String>(get: {
            let value = get()
            return value == value.rounded() && abs(value) < 1_000_000 ? String(Int(value)) : String(value)
        }, set: { text in if let value = Double(text), value.isFinite { set(value) } })
    }
}
