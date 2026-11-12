// DPGrabber iOS app - Main screen.
//
// Sections mirror the PC version: 登录、监听设置、开始/停止、运行日志、
// 保存设置. Advanced settings (店铺 ID、超时、AES 密钥、TLS 开关) live
// in SettingsView. All copy is our own; nothing is taken from the
// reference tool's UI text or brand.
// iOS 15 compatible (NavigationView, no iOS 16+ APIs).

import SwiftUI

struct ContentView: View {
    @StateObject private var viewModel = AppViewModel()
    @State private var showingSettings = false
    @State private var showingClearAccountAlert = false

    var body: some View {
        NavigationView {
            Form {
                loginSection
                listenSettingsSection
                runSection
                logSection
            }
            .navigationTitle("DPGrabber")
            .navigationBarItems(
                leading: Button("高级设置") { showingSettings = true },
                trailing: Button("保存设置") { viewModel.saveSettings() }
            )
            .sheet(isPresented: $showingSettings) {
                NavigationView {
                    SettingsView(viewModel: viewModel)
                }
            }
            .alert(isPresented: viewModel.hasError) {
                Alert(title: Text("提示"),
                      message: Text(viewModel.lastError ?? ""),
                      dismissButton: .default(Text("好")) {
                        viewModel.lastError = nil
                      })
            }
        }
        .navigationViewStyle(StackNavigationViewStyle())
    }

    // MARK: - 登录

    private var loginSection: some View {
        Section(header: Text("登录")) {
            TextField("手机号", text: $viewModel.phone)
                .keyboardType(.numberPad)
                .autocapitalization(.none)
                .disableAutocorrection(true)
            SecureField("密码", text: $viewModel.password)
            HStack {
                Button("保存账号") { viewModel.saveCredentials() }
                Spacer()
                Button("清除已保存账号") { showingClearAccountAlert = true }
                    .foregroundColor(.red)
            }
            .alert(isPresented: $showingClearAccountAlert) {
                Alert(title: Text("清除已保存账号？"),
                      message: Text("将从钥匙串删除手机号和密码。"),
                      primaryButton: .destructive(Text("清除")) {
                        viewModel.clearCredentials()
                      },
                      secondaryButton: .cancel(Text("取消")))
            }
            if !viewModel.phone.isEmpty {
                Text("当前账号：\(maskPhone(viewModel.phone))")
                    .font(.footnote)
                    .foregroundColor(.secondary)
            }
        }
    }

    // MARK: - 监听设置

    private var listenSettingsSection: some View {
        Section(header: Text("监听设置")) {
            HStack {
                Text("最低金额（元）")
                Spacer()
                TextField("10", text: doubleBinding(
                    get: { viewModel.config.minAmount },
                    set: { viewModel.config.minAmount = $0 }
                ))
                .keyboardType(.decimalPad)
                .multilineTextAlignment(.trailing)
                .frame(width: 100)
            }
            HStack {
                Text("最高金额（元，0 不限制）")
                Spacer()
                TextField("0", text: doubleBinding(
                    get: { viewModel.config.maxAmount },
                    set: { viewModel.config.maxAmount = $0 }
                ))
                .keyboardType(.decimalPad)
                .multilineTextAlignment(.trailing)
                .frame(width: 100)
            }
            Picker("备注订单", selection: $viewModel.config.memoMode) {
                Text("忽略备注").tag(0)
                Text("有备注不抢").tag(1)
            }
            .pickerStyle(SegmentedPickerStyle())
            TextField("备注关键词（逗号分隔）", text: commaListBinding(
                get: { viewModel.config.memoKeywords },
                set: { viewModel.config.memoKeywords = $0 }
            ))
            .autocapitalization(.none)
            .disableAutocorrection(true)
            TextField("黑名单微信号（逗号分隔）", text: commaListBinding(
                get: { viewModel.config.blacklist },
                set: { viewModel.config.blacklist = $0 }
            ))
            .autocapitalization(.none)
            .disableAutocorrection(true)
            Stepper("检查间隔：\(String(format: "%.1f", viewModel.config.pollIntervalSec)) 秒",
                    value: $viewModel.config.pollIntervalSec, in: 0.1...10, step: 0.1)
            Toggle("语音播报", isOn: $viewModel.config.voiceEnabled)
        }
    }

    // MARK: - 运行

    private var runSection: some View {
        Section(header: Text("运行")) {
            VStack(alignment: .leading, spacing: 6) {
                Text(viewModel.statusText)
                    .font(.headline)
                Text(viewModel.keepAliveText)
                    .font(.footnote)
                    .foregroundColor(.secondary)
                Text("发现订单 \(viewModel.foundCount) · 已抢到 \(viewModel.grabbedCount)")
                    .font(.footnote)
                    .foregroundColor(.secondary)
            }
            if viewModel.isRunning {
                Button("停止监听") { viewModel.stopListening() }
                    .foregroundColor(.red)
            } else {
                Button("开始监听") { viewModel.startListening() }
            }
        }
    }

    // MARK: - 运行日志

    private var logSection: some View {
        Section(header: Text("运行日志"), footer: Text("日志只保存在内存中，退出 App 即清空。")) {
            if viewModel.logStore.entries.isEmpty {
                Text("暂无日志")
                    .foregroundColor(.secondary)
            } else {
                ForEach(viewModel.logStore.entries.suffix(100)) { entry in
                    HStack(alignment: .top, spacing: 8) {
                        Circle()
                            .fill(color(for: entry.level))
                            .frame(width: 8, height: 8)
                            .padding(.top, 5)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(entry.message)
                                .font(.body)
                            Text(timeString(entry.time))
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                }
                Button("清空日志") { viewModel.logStore.clear() }
                    .foregroundColor(.red)
            }
        }
    }

    // MARK: - Helpers

    private func color(for level: LogLevel) -> Color {
        switch level {
        case .success: return .green
        case .error: return .red
        case .warning: return .orange
        case .debug: return .gray
        case .info: return .blue
        }
    }

    private func timeString(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        return formatter.string(from: date)
    }

    private func doubleBinding(get: @escaping () -> Double, set: @escaping (Double) -> Void) -> Binding<String> {
        Binding<String>(
            get: {
                let value = get()
                return value == value.rounded() && abs(value) < 1_000_000
                    ? String(Int(value)) : String(value)
            },
            set: { set(Double($0) ?? 0) }
        )
    }

    private func commaListBinding(get: @escaping () -> [String], set: @escaping ([String]) -> Void) -> Binding<String> {
        Binding<String>(
            get: { get().joined(separator: "，") },
            set: { text in
                let items = text
                    .replacingOccurrences(of: "，", with: ",")
                    .split(separator: ",")
                    .map { $0.trimmingCharacters(in: .whitespaces) }
                    .filter { !$0.isEmpty }
                set(items)
            }
        )
    }
}

// MARK: - AppViewModel error alert helper

extension AppViewModel {
    var hasError: Binding<Bool> {
        Binding<Bool>(
            get: { self.lastError != nil },
            set: { if !$0 { self.lastError = nil } }
        )
    }
}
