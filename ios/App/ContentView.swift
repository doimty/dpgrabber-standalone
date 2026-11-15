// DPGrabber iOS app - Main screen.
//
// Sections mirror the PC version: 登录、监听设置、开始/停止、运行日志、
// 保存设置. Advanced settings (店铺 ID、超时、AES 密钥、TLS 开关) live
// in SettingsView. All copy is our own; nothing is taken from the
// reference tool's UI text or brand.
// iOS 15 compatible (NavigationView, no iOS 16+ APIs).

import SwiftUI
import UIKit

@MainActor
struct ContentView: View {
    @StateObject private var viewModel = AppViewModel()
    @Environment(\.scenePhase) private var scenePhase
    @State private var showingSettings = false
    @State private var showingClearAccountAlert = false

    var body: some View {
        NavigationView {
            Form {
                loginSection
                platformSection
                listenSettingsSection
                runSection
                historySection
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
        .onChange(of: scenePhase) { phase in
            if phase == .background { viewModel.enteredBackground() }
            if phase == .active { viewModel.enteredForeground() }
        }
    }

    // MARK: - 登录

    private var loginSection: some View {
        Section(header: Text("登录")) {
            TextField("手机号", text: $viewModel.phone)
                .keyboardType(.numberPad)
                .autocapitalization(.none)
                .disableAutocorrection(true)
                .disabled(viewModel.accountEditingDisabled)
            SecureField("密码", text: $viewModel.password)
                .disabled(viewModel.accountEditingDisabled)
            Text(viewModel.accountSummary)
            Button(viewModel.isLoggingIn ? "正在校验…" : "登录并验证店铺权限") { viewModel.login() }
                .disabled(viewModel.accountEditingDisabled)
            Toggle("登录后自动上线", isOn: $viewModel.config.onlineAfterLogin)
            HStack {
                Button("保存账号") { viewModel.saveCredentials() }
                    .disabled(viewModel.accountEditingDisabled)
                Spacer()
                Button("清除已保存账号") { showingClearAccountAlert = true }
                    .foregroundColor(.red)
                    .disabled(viewModel.isStopping)
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

    private var platformSection: some View {
        Section(header: Text("店铺与本周统计"), footer: Text("本周按 Asia/Shanghai 周一至今日统计。续客率分母待确认，不推算；服务器操作成功后刷新状态。")) {
            Toggle("店铺上线", isOn: Binding(get: { viewModel.isOnline }, set: { viewModel.setOnline($0) }))
                .disabled(viewModel.serverControlsDisabled)
            Toggle("接随机单", isOn: Binding(get: { viewModel.randomOrderEnabled }, set: { viewModel.setRandomOrder($0) }))
                .disabled(viewModel.serverControlsDisabled)
            Button(viewModel.isActing ? "正在刷新…" : "刷新平台信息") { viewModel.refreshAccount() }
                .disabled(viewModel.serverControlsDisabled)
            Text("本周接单：\(viewModel.weeklyStats.orderCount.map { String($0) } ?? "—") 单\(viewModel.weeklyStats.isCountFallback ? "（订单总数兜底）" : "")")
            Text("续费率（金额比）：\(percentage(viewModel.weeklyStats.feeRate))")
            Text("续单率（单数比）：\(percentage(viewModel.weeklyStats.orderRate))")
            Text("续客率：—（口径待确认）；续客数：\(viewModel.weeklyStats.continuedUserCount.map { String($0) } ?? "—")")
                .font(.footnote).foregroundColor(.secondary)
            ForEach(viewModel.itemOptions) { item in
                Toggle(item.name, isOn: Binding(
                    get: { viewModel.selectedItemIDs.contains(item.id) },
                    set: { viewModel.setItem(item.id, enabled: $0) }))
                    .disabled(viewModel.serverControlsDisabled)
            }
            if viewModel.itemOptions.isEmpty { Text("接单类型尚未读取或当前无操作权限").font(.footnote).foregroundColor(.secondary) }
        }
    }

    private func percentage(_ value: Double?) -> String {
        guard let value else { return "—" }
        return String(format: "%.1f%%", value * 100)
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
            Toggle("成功后自动停止", isOn: $viewModel.config.autoStopOnSuccess)
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
                Button(viewModel.isStopping ? "正在停止…" : "开始监听") { viewModel.startListening() }
                    .disabled(viewModel.isBusy || !viewModel.isLoggedIn)
            }
        }
    }

    private var historySection: some View {
        Section(header: Text("今日与历史"), footer: Text("今日按 Asia/Shanghai 汇总。未知结果不会自动重下单；请核验平台。续客率口径待确认，不显示推算值。")) {
            Text("今日成功 \(viewModel.todayCounts[.success] ?? 0) · 失败 \(viewModel.todayCounts[.failed] ?? 0) · 跳过 \(viewModel.todayCounts[.skipped] ?? 0)")
            Text("未知 \(viewModel.todayCounts[.unknown] ?? 0) · 风控 \(viewModel.todayCounts[.risk] ?? 0)（仅当前账号）")
                .font(.footnote).foregroundColor(.secondary)
            ForEach(viewModel.historyRows.prefix(100)) { row in
                VStack(alignment: .leading) {
                    Text("\(row.orderID) · \(row.outcome.rawValue) · ￥\(String(format: "%.2f", row.amount))")
                    Text("店铺 \(row.shopID) · \(row.reason)").font(.caption).foregroundColor(.secondary)
                }
                .contextMenu {
                    Button("拉黑客户") { viewModel.blacklist(row) }
                    Button("屏蔽此备注关键词") { viewModel.blockMemo(row) }
                    Button("复制订单号") { UIPasteboard.general.string = row.orderID }
                    if row.outcome == .unknown {
                        Button("只读核验未知结果（不重新提交）") { viewModel.verifyUnknown(row) }
                            .disabled(viewModel.serverControlsDisabled)
                    }
                }
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
            set: { text in if let value = Double(text), value.isFinite { set(value) } }
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
