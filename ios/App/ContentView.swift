import SwiftUI
import UIKit

private let appAccent = Color(red: 0.02, green: 0.45, blue: 0.67)

/// 仅调试构建可通过启动参数指定初始页，供离线截图验收使用；发布构建恒为工作台。
private func initialTabForLaunch() -> Int {
    #if DEBUG
    let requested = UserDefaults.standard.integer(forKey: "uiReviewTab")
    return (0...3).contains(requested) ? requested : 0
    #else
    return 0
    #endif
}

@MainActor
struct ContentView: View {
    @StateObject private var viewModel = AppViewModel()
    @Environment(\.scenePhase) private var scenePhase
    @State private var selectedTab = initialTabForLaunch()
    @State private var showingSettings = false
    @State private var showingClearAccountAlert = false
    @State private var showingCredentials = false
    @State private var historyFilter = HistoryFilter.all

    private let accent = appAccent

    var body: some View {
        TabView(selection: $selectedTab) {
            NavigationContainer { dashboard }
                .tabItem { Label("工作台", systemImage: "square.grid.2x2.fill") }
                .tag(0)
            NavigationContainer { rulesPage }
                .tabItem { Label("规则", systemImage: "slider.horizontal.3") }
                .tag(1)
            NavigationContainer { historyPage }
                .tabItem { Label("记录", systemImage: "clock.arrow.circlepath") }
                .tag(2)
            NavigationContainer { accountPage }
                .tabItem { Label("我的", systemImage: "person.crop.circle") }
                .tag(3)
        }
        .tint(accent)
        .sheet(isPresented: $showingSettings) {
            NavigationContainer { SettingsView(viewModel: viewModel) }
                .tint(accent)
        }
        .alert(isPresented: Binding(
            get: { !showingSettings && viewModel.lastError != nil },
            set: { if !$0 && !showingSettings { viewModel.lastError = nil } }
        )) {
            Alert(title: Text("提示"), message: Text(viewModel.lastError ?? ""),
                  dismissButton: .default(Text("好")) { viewModel.lastError = nil })
        }
        .onChange(of: scenePhase) { phase in
            if phase == .background { viewModel.enteredBackground() }
            if phase == .active { viewModel.enteredForeground() }
        }
    }

    private var dashboard: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                statusCard
                todayCard
                shopCard
                weeklyCard
                recentCard
            }
            .padding(20)
            .padding(.bottom, 8)
        }
        .background(Color(UIColor.systemGroupedBackground))
        .navigationTitle("工作台")
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button { selectedTab = 3 } label: {
                    AccountAvatar(phone: viewModel.phone, size: 30)
                }
                .accessibilityLabel("管理账号")
            }
        }
    }

    private var statusTitle: String {
        if viewModel.isStopping { return "正在停止" }
        if !viewModel.storageReady { return "本地记录需要检查" }
        if viewModel.listenIssue != nil {
            return viewModel.isRunning && viewModel.listenRetryAt != nil ? "连接待恢复" : "监听已暂停"
        }
        if viewModel.isRunning { return viewModel.lastPollAt == nil ? "正在连接订单" : "正在监听" }
        if viewModel.isLoggingIn { return "正在验证账号" }
        return viewModel.isLoggedIn ? "准备就绪" : "欢迎使用 DPGrabber"
    }

    private var statusColor: Color {
        if viewModel.listenIssue != nil || !viewModel.storageReady { return .orange }
        return accent
    }

    private var statusCard: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: viewModel.listenIssue != nil ? "wifi.exclamationmark" : "waveform.path")
                    .font(.title2)
                    .foregroundColor(statusColor)
                    .padding(12)
                    .background(statusColor.opacity(0.10))
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                VStack(alignment: .leading, spacing: 6) {
                    Text("DPGRABBER · 前台工作台")
                        .font(.caption).foregroundColor(.secondary)
                    Text(statusTitle).font(.title2.bold()).foregroundColor(statusColor)
                    Text(viewModel.listenIssue ?? viewModel.statusText)
                        .font(.subheadline).foregroundColor(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
            }
            if let retryAt = viewModel.listenRetryAt, viewModel.listenIssue != nil {
                HStack(spacing: 6) {
                    Image(systemName: "arrow.clockwise")
                    Text("下次重试")
                    Text(retryAt, style: .relative)
                }
                .font(.footnote).foregroundColor(.orange)
            }
            if let lastPollAt = viewModel.lastPollAt {
                HStack(spacing: 6) {
                    Text("最近检查")
                    Text(lastPollAt, style: .time)
                }
                .font(.caption).foregroundColor(.secondary)
            }
            Button {
                if viewModel.isRunning { viewModel.stopListening() }
                else { viewModel.startListening() }
            } label: {
                Label(viewModel.isRunning ? "停止监听" : (viewModel.isStopping ? "正在停止…" : "开始监听"),
                      systemImage: viewModel.isRunning ? "stop.fill" : "play.fill")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)
                    .foregroundColor(.white)
                    .background(viewModel.isRunning ? Color(UIColor.systemRed) : accent)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
            }
            .buttonStyle(PlainButtonStyle())
            .disabled(!viewModel.isRunning && (viewModel.isBusy || !viewModel.isLoggedIn || !viewModel.storageReady))
            .opacity(!viewModel.isRunning && (viewModel.isBusy || !viewModel.isLoggedIn || !viewModel.storageReady) ? 0.5 : 1)
            if !viewModel.isLoggedIn {
                Button { selectedTab = 3 } label: {
                    Label("先登录并验证店铺", systemImage: "arrow.right.circle")
                        .font(.subheadline)
                }
            } else {
                Text("请保持 App 在前台，切到后台会安全停止。")
                    .font(.footnote).foregroundColor(.secondary)
            }
            if viewModel.isRunning {
                Text("本次发现 \(viewModel.foundCount) 单 · 今日已成功 \(viewModel.grabbedCount) 单")
                    .font(.footnote).foregroundColor(.secondary)
            }
        }
        .padding(20)
        .background(Color(UIColor.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(statusColor.opacity(0.18), lineWidth: 1))
    }

    private var todayCard: some View {
        ClientCard(title: "今日概览", symbol: "chart.bar.xaxis") {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 86), alignment: .leading)], alignment: .leading, spacing: 18) {
                countMetric("成功", count: viewModel.todayCounts[.success] ?? 0, color: .green)
                countMetric("待核验", count: viewModel.todayCounts[.unknown] ?? 0, color: .orange)
                countMetric("风控", count: viewModel.todayCounts[.risk] ?? 0, color: .red)
            }
            Button { selectedTab = 2 } label: {
                HStack {
                    Text("查看全部记录")
                    Spacer()
                    Image(systemName: "arrow.right")
                }.font(.subheadline)
            }
        }
    }

    private func countMetric(_ title: String, count: Int, color: Color) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(String(count)).font(.system(.largeTitle, design: .rounded).bold()).foregroundColor(color)
            Text(title).font(.subheadline).foregroundColor(.secondary)
        }
        .accessibilityElement(children: .combine)
    }

    private var shopCard: some View {
        ClientCard(title: "店铺与接单", symbol: "building.2.fill") {
            Text(viewModel.accountSummary).font(.subheadline).foregroundColor(.secondary)
            Divider()
            Toggle(isOn: Binding(get: { viewModel.isOnline }, set: { viewModel.setOnline($0) })) {
                Label("店铺上线", systemImage: "power")
            }.disabled(viewModel.serverControlsDisabled)
            Toggle(isOn: Binding(get: { viewModel.randomOrderEnabled }, set: { viewModel.setRandomOrder($0) })) {
                Label("接随机单", systemImage: "shuffle")
            }.disabled(viewModel.serverControlsDisabled)
            if !viewModel.itemOptions.isEmpty {
                DisclosureGroup("接单类型") {
                    VStack(spacing: 16) {
                        ForEach(viewModel.itemOptions) { item in
                            Toggle(item.name, isOn: Binding(
                                get: { viewModel.selectedItemIDs.contains(item.id) },
                                set: { viewModel.setItem(item.id, enabled: $0) }))
                                .disabled(viewModel.serverControlsDisabled)
                        }
                    }.padding(.top, 12)
                }
            } else {
                Text(viewModel.isLoggedIn ? "接单类型暂不可用，请刷新后查看。" : "登录后读取店铺与接单类型。")
                    .font(.footnote).foregroundColor(.secondary)
            }
            Button { viewModel.refreshAccount() } label: {
                Label(viewModel.isActing ? "正在刷新…" : "刷新平台信息", systemImage: "arrow.clockwise")
                    .font(.subheadline)
            }.disabled(viewModel.serverControlsDisabled)
            if viewModel.isRunning {
                Text("修改平台开关前，请先停止监听。")
                    .font(.footnote).foregroundColor(.secondary)
            }
        }
    }

    private var weeklyCard: some View {
        ClientCard(title: "本周表现", symbol: "calendar") {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 120), alignment: .leading)], alignment: .leading, spacing: 20) {
                weekMetric("接单数", value: viewModel.weeklyStats.orderCount.map { "\($0) 单" } ?? "—")
                weekMetric("续费率", value: percentage(viewModel.weeklyStats.feeRate))
                weekMetric("续单率", value: percentage(viewModel.weeklyStats.orderRate))
                weekMetric("续客数", value: viewModel.weeklyStats.continuedUserCount.map { String($0) } ?? "—")
            }
            DisclosureGroup("统计说明") {
                Text("按北京时间本周一至今日统计。续费率为金额比，续单率为单数比；续客率口径待确认，暂不展示。\(viewModel.weeklyStats.isCountFallback ? "当前接单数来自订单总数。" : "")")
                    .font(.footnote).foregroundColor(.secondary).padding(.top, 8)
            }.font(.footnote)
        }
    }

    private func weekMetric(_ title: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.caption).foregroundColor(.secondary)
            Text(value).font(.system(.title3, design: .rounded).bold())
        }.accessibilityElement(children: .combine)
    }

    private var recentCard: some View {
        ClientCard(title: "最近活动", symbol: "clock") {
            if viewModel.historyRows.isEmpty {
                Text("还没有订单记录，开始监听后可在这里查看处理结果。")
                    .font(.subheadline).foregroundColor(.secondary)
            } else {
                ForEach(Array(viewModel.historyRows.prefix(3))) { row in
                    OrderRecordRow(row: row)
                    if row.id != viewModel.historyRows.prefix(3).last?.id { Divider() }
                }
                Button("查看全部订单") { selectedTab = 2 }.font(.subheadline)
            }
            NavigationLink(destination: RunLogView(store: viewModel.logStore)) {
                Label("查看运行日志", systemImage: "text.alignleft")
                    .font(.subheadline)
            }
        }
    }

    private var rulesPage: some View {
        Form {
            Section(footer: Text("更改会自动保存；监听期间也可调整筛选规则。")) {
                Label("按你的偏好接单", systemImage: "line.3.horizontal.decrease.circle.fill")
                    .font(.headline).foregroundColor(accent)
                Text("只处理符合金额、备注和黑名单规则的订单。")
                    .font(.subheadline).foregroundColor(.secondary)
            }
            Section(header: Text("金额范围"), footer: Text("最高金额设为 0 表示不限制。")) {
                amountField("最低金额", placeholder: "10", get: { viewModel.config.minAmount }, set: { viewModel.config.minAmount = $0 })
                amountField("最高金额", placeholder: "0", get: { viewModel.config.maxAmount }, set: { viewModel.config.maxAmount = $0 })
            }
            Section(header: Text("备注筛选"), footer: Text("多个关键词用逗号分隔；命中关键词的备注订单会被跳过。")) {
                Picker("备注订单", selection: $viewModel.config.memoMode) {
                    Text("按关键词筛选").tag(0)
                    Text("有备注就跳过").tag(1)
                }
                TextField("屏蔽的备注关键词", text: commaListBinding(
                    get: { viewModel.config.memoKeywords }, set: { viewModel.config.memoKeywords = $0 }))
                    .autocapitalization(.none).disableAutocorrection(true)
            }
            Section(header: Text("客户黑名单"), footer: Text("填写微信号，多个账号用逗号分隔。也可在记录中长按订单添加。")) {
                TextField("不接单的微信号", text: commaListBinding(
                    get: { viewModel.config.blacklist }, set: { viewModel.config.blacklist = $0 }))
                    .autocapitalization(.none).disableAutocorrection(true)
            }
            Section(header: Text("监听偏好")) {
                Stepper("检查间隔：\(String(format: "%.1f", viewModel.config.pollIntervalSec)) 秒",
                        value: $viewModel.config.pollIntervalSec, in: 0.1...10, step: 0.1)
                Toggle("语音播报", isOn: $viewModel.config.voiceEnabled)
                Toggle("成功后自动停止", isOn: $viewModel.config.autoStopOnSuccess)
            }
            Section {
                Button { showingSettings = true } label: {
                    Label("等待时间与更多设置", systemImage: "gearshape")
                }
            }
        }
        .navigationTitle("接单规则")
        .navigationBarTitleDisplayMode(.large)
    }

    private func amountField(_ title: String, placeholder: String, get: @escaping () -> Double, set: @escaping (Double) -> Void) -> some View {
        HStack {
            Text(title)
            Spacer()
            TextField(placeholder, text: doubleBinding(get: get, set: set))
                .keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(minWidth: 60, maxWidth: 110)
                .accessibilityLabel(title)
            Text("元").foregroundColor(.secondary)
        }
    }

    private var filteredHistory: [OrderHistoryRecord] {
        guard let outcome = historyFilter.outcome else { return viewModel.historyRows }
        return viewModel.historyRows.filter { $0.outcome == outcome }
    }

    private var historyPage: some View {
        List {
            Section {
                Picker("筛选处理结果", selection: $historyFilter) {
                    ForEach(HistoryFilter.allCases, id: \.self) { filter in
                        Text(filter.rawValue).tag(filter)
                    }
                }
                Text("今日成功 \(viewModel.todayCounts[.success] ?? 0) · 失败 \(viewModel.todayCounts[.failed] ?? 0) · 跳过 \(viewModel.todayCounts[.skipped] ?? 0)")
                    .font(.footnote).foregroundColor(.secondary)
                if (viewModel.todayCounts[.unknown] ?? 0) > 0 {
                    Label("存在待核验订单，请到平台确认结果。", systemImage: "exclamationmark.circle")
                        .font(.footnote).foregroundColor(.orange)
                }
            }
            Section(header: Text("\(historyFilter.rawValue) · \(filteredHistory.count) 条"),
                    footer: Text("仅显示当前账号的本地记录，今日按北京时间统计。长按订单可添加黑名单、屏蔽备注或复制订单号。未知结果不会自动重下单。")) {
                if filteredHistory.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "tray").font(.largeTitle).foregroundColor(accent)
                        Text(historyFilter == .all ? "暂无订单记录" : "暂无\(historyFilter.rawValue)记录").font(.headline)
                        Text("处理过的订单会保存在这里，切换筛选可查看其他结果。")
                            .font(.subheadline).foregroundColor(.secondary).multilineTextAlignment(.center)
                    }.frame(maxWidth: .infinity).padding(.vertical, 32)
                } else {
                    ForEach(filteredHistory) { row in
                        OrderRecordRow(row: row)
                            .padding(.vertical, 5)
                            .contextMenu {
                                Button { viewModel.blacklist(row) } label: { Label("拉黑客户", systemImage: "person.crop.circle.badge.xmark") }
                                    .disabled(row.wechatID.isEmpty)
                                Button { viewModel.blockMemo(row) } label: { Label("屏蔽此备注关键词", systemImage: "text.badge.minus") }
                                    .disabled(row.memo.isEmpty)
                                Button { UIPasteboard.general.string = row.orderID } label: { Label("复制订单号", systemImage: "doc.on.doc") }
                                if row.outcome == .unknown {
                                    Button { viewModel.verifyUnknown(row) } label: { Label("只读核验（不重新提交）", systemImage: "checkmark.shield") }
                                        .disabled(viewModel.serverControlsDisabled)
                                }
                            }
                    }
                }
            }
        }
        .listStyle(InsetGroupedListStyle())
        .navigationTitle("订单记录")
        .navigationBarTitleDisplayMode(.large)
        .navigationBarItems(trailing: NavigationLink(destination: RunLogView(store: viewModel.logStore)) {
            Image(systemName: "text.alignleft").accessibilityLabel("运行日志")
        })
    }

    private var accountPage: some View {
        Form {
            Section {
                ProfileHeader(phone: viewModel.phone,
                              isLoggedIn: viewModel.isLoggedIn,
                              summary: viewModel.accountSummary)
            }
            accountCredentialsSection
            Section(header: Text("应用设置")) {
                Button { showingSettings = true } label: { Label("高级设置", systemImage: "gearshape") }
                Button { viewModel.saveSettings() } label: { Label("保存当前设置", systemImage: "square.and.arrow.down") }
                NavigationLink(destination: RunLogView(store: viewModel.logStore)) {
                    Label("运行日志", systemImage: "text.alignleft")
                }
            }
            Section(header: Text("运行须知")) {
                Label("仅限前台运行", systemImage: "iphone")
                Text(viewModel.keepAliveText).font(.footnote).foregroundColor(.secondary)
                Text("未知订单请先核验，不会自动再次提交。请以平台最终结果为准。")
                    .font(.footnote).foregroundColor(.secondary)
            }
            Section {
                HStack {
                    Text("DPGrabber")
                    Spacer()
                    Text(versionText).foregroundColor(.secondary)
                }
            }
        }
        .navigationTitle("我的")
        .navigationBarTitleDisplayMode(.large)
        .confirmationDialog("清除已保存账号？", isPresented: $showingClearAccountAlert, titleVisibility: .visible) {
            Button("清除账号", role: .destructive) { viewModel.clearCredentials() }
            Button("取消", role: .cancel) { }
        } message: {
            Text("将停止当前任务，并从本机钥匙串删除手机号和密码。订单记录不会被删除。")
        }
    }

    @ViewBuilder
    private var accountCredentialsSection: some View {
        if viewModel.isLoggedIn {
            Section(footer: Text("账号仅保存在本机钥匙串；修改前请先停止监听。当前支持单账号。")) {
                DisclosureGroup("账号与登录", isExpanded: $showingCredentials) {
                    accountCredentialControls
                }
            }
        } else {
            Section(header: Text("账号与登录"),
                    footer: Text("账号仅保存在本机钥匙串；修改前请先停止监听。当前支持单账号。")) {
                accountCredentialControls
            }
        }
    }

    @ViewBuilder
    private var accountCredentialControls: some View {
        TextField("手机号", text: $viewModel.phone)
            .keyboardType(.numberPad).textContentType(.username)
            .autocapitalization(.none).disableAutocorrection(true)
            .disabled(viewModel.accountEditingDisabled)
        SecureField("密码", text: $viewModel.password)
            .textContentType(.password).disabled(viewModel.accountEditingDisabled)
        Button { viewModel.login() } label: {
            HStack {
                Label(viewModel.isLoggingIn ? "正在验证…" : "登录并验证店铺", systemImage: "checkmark.shield")
                Spacer()
                if viewModel.isLoggingIn { ProgressView() }
            }
        }.disabled(viewModel.accountEditingDisabled)
        Toggle("登录后自动上线", isOn: $viewModel.config.onlineAfterLogin)
        Button { viewModel.saveCredentials() } label: {
            Label("保存账号到本机", systemImage: "lock.shield")
        }.disabled(viewModel.accountEditingDisabled)
        Button(role: .destructive) { showingClearAccountAlert = true } label: {
            Label("清除已保存账号", systemImage: "trash")
        }.disabled(viewModel.isStopping)
    }

    private var versionText: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "—"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "—"
        return "版本 \(version) (\(build))"
    }

    private func percentage(_ value: Double?) -> String {
        guard let value else { return "—" }
        return String(format: "%.1f%%", value * 100)
    }

    private func doubleBinding(get: @escaping () -> Double, set: @escaping (Double) -> Void) -> Binding<String> {
        Binding<String>(get: {
            let value = get()
            return value == value.rounded() && abs(value) < 1_000_000 ? String(Int(value)) : String(value)
        }, set: { text in if let value = Double(text), value.isFinite { set(value) } })
    }

    private func commaListBinding(get: @escaping () -> [String], set: @escaping ([String]) -> Void) -> Binding<String> {
        Binding<String>(get: { get().joined(separator: "，") }, set: { text in
            set(text.replacingOccurrences(of: "，", with: ",").split(separator: ",")
                .map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty })
        })
    }
}

private enum HistoryFilter: String, CaseIterable {
    case all = "全部", success = "成功", unknown = "待核验", failed = "失败", risk = "风控", skipped = "跳过"

    var outcome: OrderOutcome? {
        switch self {
        case .all: return nil
        case .success: return .success
        case .unknown: return .unknown
        case .failed: return .failed
        case .risk: return .risk
        case .skipped: return .skipped
        }
    }
}

private struct ClientCard<Content: View>: View {
    let title: String
    let symbol: String
    let content: Content

    init(title: String, symbol: String, @ViewBuilder content: () -> Content) {
        self.title = title
        self.symbol = symbol
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Label(title, systemImage: symbol).font(.headline)
            content
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(UIColor.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }
}

private struct AccountAvatar: View {
    let phone: String
    let size: CGFloat

    private var avatarText: String {
        phone.isEmpty ? "未" : String(phone.suffix(2))
    }

    var body: some View {
        Text(avatarText)
            .font(.system(size: size * 0.36, weight: .semibold, design: .rounded))
            .lineLimit(1)
            .minimumScaleFactor(0.6)
            .foregroundColor(appAccent)
            .frame(width: size, height: size)
            .background(appAccent.opacity(0.12))
            .clipShape(Circle())
            .accessibilityLabel(phone.isEmpty ? "未登录账号" : "账号头像")
    }
}

private struct ProfileHeader: View {
    let phone: String
    let isLoggedIn: Bool
    let summary: String

    var body: some View {
        HStack(spacing: 14) {
            AccountAvatar(phone: phone, size: 56)
            VStack(alignment: .leading, spacing: 6) {
                Text(phone.isEmpty ? "未登录" : maskPhone(phone))
                    .font(.title3.weight(.semibold))
                statusBadge
                Text(summary)
                    .font(.footnote)
                    .foregroundColor(.secondary)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 12)
    }

    @ViewBuilder
    private var statusBadge: some View {
        if isLoggedIn {
            Label("店铺已验证", systemImage: "checkmark.seal.fill")
                .font(.caption.weight(.semibold))
                .foregroundColor(appAccent)
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .background(appAccent.opacity(0.12))
                .clipShape(Capsule())
        } else {
            Text("登录后验证店铺")
                .font(.caption)
                .foregroundColor(.secondary)
        }
    }
}

private struct NavigationContainer<Content: View>: View {
    private let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        Group {
            if #available(iOS 16.0, *) {
                NavigationStack { content }
            } else {
                NavigationView { content }
                    .navigationViewStyle(StackNavigationViewStyle())
            }
        }
    }
}

private struct OrderRecordRow: View {
    let row: OrderHistoryRecord

    private var outcomeTitle: String {
        switch row.outcome {
        case .success: return "接单成功"
        case .failed: return "接单失败"
        case .risk: return "风控拦截"
        case .unknown: return "结果待核验"
        case .skipped: return "已跳过"
        }
    }

    private var outcomeColor: Color {
        switch row.outcome {
        case .success: return .green
        case .failed, .risk: return .red
        case .unknown: return .orange
        case .skipped: return .secondary
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text(outcomeTitle).font(.subheadline.bold()).foregroundColor(outcomeColor)
                Spacer()
                Text("¥\(String(format: "%.2f", row.amount))").font(.system(.headline, design: .rounded))
            }
            Text("订单 \(row.orderID)").font(.subheadline).lineLimit(1).truncationMode(.middle)
            if !row.reason.isEmpty {
                Text(row.reason).font(.caption).foregroundColor(.secondary).fixedSize(horizontal: false, vertical: true)
            }
            HStack(alignment: .firstTextBaseline) {
                Text("店铺 \(row.shopID)")
                Spacer()
                Text(row.timestamp, style: .date)
                Text(row.timestamp, style: .time)
            }.font(.caption2).foregroundColor(.secondary)
        }
        .accessibilityElement(children: .combine)
    }
}

@MainActor
private struct RunLogView: View {
    @ObservedObject var store: LogStore

    var body: some View {
        List {
            Section(footer: Text("仅显示最近 100 条。日志只保存在内存中，退出 App 后清空；订单记录不受影响。")) {
                if store.entries.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "text.alignleft").font(.largeTitle).foregroundColor(.secondary)
                        Text("暂无运行日志").font(.headline)
                        Text("登录和监听产生的活动会显示在这里。")
                            .font(.subheadline).foregroundColor(.secondary)
                    }.frame(maxWidth: .infinity).padding(.vertical, 32)
                } else {
                    ForEach(Array(store.entries.suffix(100).reversed())) { entry in
                        HStack(alignment: .top, spacing: 12) {
                            Image(systemName: symbol(entry.level))
                                .foregroundColor(color(entry.level)).frame(width: 20)
                            VStack(alignment: .leading, spacing: 6) {
                                Text(entry.message).font(.subheadline).textSelection(.enabled)
                                Text(entry.time, style: .time).font(.caption).foregroundColor(.secondary)
                            }
                        }.padding(.vertical, 5)
                    }
                }
            }
        }
        .listStyle(InsetGroupedListStyle())
        .navigationTitle("运行日志")
        .navigationBarItems(trailing: Button("清空") { store.clear() }.disabled(store.entries.isEmpty))
    }

    private func color(_ level: LogLevel) -> Color {
        switch level {
        case .success: return .green
        case .error: return .red
        case .warning: return .orange
        case .debug: return .gray
        case .info: return .blue
        }
    }

    private func symbol(_ level: LogLevel) -> String {
        switch level {
        case .success: return "checkmark.circle.fill"
        case .error: return "xmark.circle.fill"
        case .warning: return "exclamationmark.triangle.fill"
        case .debug: return "ellipsis.circle"
        case .info: return "info.circle"
        }
    }
}

extension AppViewModel {
    var hasError: Binding<Bool> {
        Binding<Bool>(get: { self.lastError != nil }, set: { if !$0 { self.lastError = nil } })
    }
}
