import Flutter
import ReplayKit
import UIKit

/// iOS 播放音频捕获插件。
///
/// 宿主 App 通过 `RPSystemBroadcastPickerView` 启动 ReplayKit Broadcast Upload
/// Extension。插件不再使用 `RPBroadcastActivityViewController`，避免系统把
/// upload-only extension 当成直播服务首选项查找而报“未找到直播首选服务”。
final class PlaybackAudioCapturePlugin: NSObject, FlutterStreamHandler {
  private enum SharedStore {
    static let appGroupIdentifier = "group.com.pechtak.app.playback-audio"
    static let configFileName = "playback_audio_broadcast_config.json"
    static let payloadFileName = "playback_audio_broadcast_payload.json"
    static let stopFileName = "playback_audio_broadcast_stop.json"
    static let source = "ios_replaykit_broadcast_audio_app"
  }

  private let methodChannel: FlutterMethodChannel
  private let eventChannel: FlutterEventChannel
  private var eventSink: FlutterEventSink?
  private var pickerView: RPSystemBroadcastPickerView?
  private var pendingStartResult: FlutterResult?
  private var startTimeoutTimer: Timer?
  private var pollTimer: Timer?
  private var enableLog = false
  private var sampleRate = 16000
  private var channels = 1
  private var frameMs = 100
  private var isCapturing = false
  private var lastPayloadData: Data?
  private var lastPayloadReceivedAt: Date?
  private var lastWaitingStateAt: Date?

  /// 注册播放音频捕获通道。
  init(registrar: FlutterPluginRegistrar) {
    methodChannel = FlutterMethodChannel(
      name: "pcm_stream_recorder/playback_audio",
      binaryMessenger: registrar.messenger()
    )
    eventChannel = FlutterEventChannel(
      name: "pcm_stream_recorder/playback_audio_power_stream",
      binaryMessenger: registrar.messenger()
    )
    super.init()
    eventChannel.setStreamHandler(self)
    methodChannel.setMethodCallHandler(handle)
  }

  /// 释放通道和轮询资源。
  func dispose() {
    methodChannel.setMethodCallHandler(nil)
    eventChannel.setStreamHandler(nil)
    requestExtensionStop()
    stopPolling()
    invalidateStartTimeout()
    removePickerView()
    clearSharedFiles(includeStopRequest: false)
    pendingStartResult = nil
  }

  func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
    eventSink = events
    return nil
  }

  func onCancel(withArguments arguments: Any?) -> FlutterError? {
    eventSink = nil
    return nil
  }

  /// 处理 Flutter 方法调用。
  private func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    switch call.method {
    case "isSupported":
      result(isReplayKitBroadcastSupported())
    case "startPowerCapture":
      startPowerCapture(arguments: call.arguments, result: result)
    case "stop":
      stopBroadcast(result: result)
    default:
      result(FlutterMethodNotImplemented)
    }
  }

  /// 判断当前系统是否支持 ReplayKit Broadcast picker。
  private func isReplayKitBroadcastSupported() -> Bool {
    if #available(iOS 12.0, *) {
      return NSClassFromString("RPSystemBroadcastPickerView") != nil
    }
    return false
  }

  /// 启动系统 Broadcast picker 并等待 extension 写入首个 payload。
  private func startPowerCapture(arguments: Any?, result: @escaping FlutterResult) {
    guard isReplayKitBroadcastSupported() else {
      result(FlutterError(
        code: "UNSUPPORTED",
        message: "当前系统或设备不支持 ReplayKit Broadcast",
        details: nil
      ))
      return
    }
    guard pendingStartResult == nil else {
      result(FlutterError(
        code: "REPLAYKIT_BUSY",
        message: "ReplayKit Broadcast 正在启动中",
        details: nil
      ))
      return
    }

    let args = arguments as? [String: Any] ?? [:]
    sampleRate = max(args["sampleRate"] as? Int ?? 16000, 1)
    channels = (args["channels"] as? Int ?? 1) == 2 ? 2 : 1
    frameMs = min(max(args["frameMs"] as? Int ?? 100, 10), 1000)
    enableLog = args["enableLog"] as? Bool ?? false

    pendingStartResult = result
    isCapturing = false
    stopPolling()
    invalidateStartTimeout()
    clearSharedFiles(includeStopRequest: true)
    writeCaptureConfig()

    DispatchQueue.main.async { [weak self] in
      self?.presentSystemBroadcastPicker()
      self?.startPolling()
      self?.startTimeoutTimer = Timer.scheduledTimer(withTimeInterval: 20.0, repeats: false) { [weak self] _ in
        self?.handleStartTimeout()
      }
    }
  }

  /// 使用系统 Broadcast picker 打开指定 upload extension。
  @available(iOS 12.0, *)
  private func presentSystemBroadcastPicker() {
    guard let presenter = topViewController() else {
      finishPendingStart(
        with: FlutterError(
          code: "UNSUPPORTED",
          message: "当前页面无法展示 ReplayKit Broadcast 选择器",
          details: nil
        )
      )
      return
    }

    removePickerView()

    let picker = RPSystemBroadcastPickerView(frame: CGRect(x: -100, y: -100, width: 44, height: 44))
    picker.preferredExtension = preferredExtensionBundleIdentifier()
    picker.showsMicrophoneButton = false
    picker.alpha = 0.01
    presenter.view.addSubview(picker)
    pickerView = picker

    guard let button = picker.subviews.compactMap({ $0 as? UIButton }).first else {
      finishPendingStart(
        with: FlutterError(
          code: "UNSUPPORTED",
          message: "未能触发 ReplayKit Broadcast 选择器",
          details: nil
        )
      )
      return
    }

    button.sendActions(for: .touchUpInside)
    log("ReplayKit Broadcast picker 已触发: \(preferredExtensionBundleIdentifier())")
  }

  /// 停止 Broadcast。
  ///
  /// `RPSystemBroadcastPickerView` 不返回 `RPBroadcastController`，宿主无法直接
  /// 调用 `finishBroadcast`。这里通过 App Group 写入 stop 请求，由 extension 在
  /// 下一帧 sample buffer 到达时主动结束。
  private func stopBroadcast(result: FlutterResult?) {
    pendingStartResult = nil
    invalidateStartTimeout()
    isCapturing = false
    requestExtensionStop()
    stopPolling()
    removePickerView()
    emitState(capturing: false, message: "stopped")
    result?(true)
  }

  /// 启动 App Group payload 轮询。
  private func startPolling() {
    stopPolling()
    lastPayloadData = nil
    lastPayloadReceivedAt = nil
    lastWaitingStateAt = nil
    pollTimer = Timer.scheduledTimer(withTimeInterval: 0.2, repeats: true) { [weak self] _ in
      self?.pollSharedPayload()
    }
    if let pollTimer = pollTimer {
      RunLoop.main.add(pollTimer, forMode: .common)
    }
  }

  /// 停止轮询。
  private func stopPolling() {
    pollTimer?.invalidate()
    pollTimer = nil
  }

  /// 从 App Group 读取 extension 写入的功率 payload。
  private func pollSharedPayload() {
    guard let payloadURL = sharedFileURL(fileName: SharedStore.payloadFileName) else {
      emitWaitingStateIfNeeded()
      return
    }
    guard let data = try? Data(contentsOf: payloadURL), !data.isEmpty else {
      emitWaitingStateIfNeeded()
      return
    }
    guard data != lastPayloadData else {
      lastPayloadReceivedAt = Date()
      return
    }
    lastPayloadData = data
    lastPayloadReceivedAt = Date()
    lastWaitingStateAt = nil

    do {
      let object = try JSONSerialization.jsonObject(with: data)
      guard var payload = object as? [String: Any] else {
        return
      }
      payload["source"] = SharedStore.source
      payload["sampleRate"] = payload["sampleRate"] ?? sampleRate
      payload["channels"] = payload["channels"] ?? channels
      payload["capturing"] = payload["capturing"] ?? true
      payload["message"] = payload["message"] ?? "capturing"

      if pendingStartResult != nil {
        isCapturing = true
        invalidateStartTimeout()
        finishPendingStart(with: true)
      }
      eventSink?(payload)
    } catch {
      log("解析 Broadcast payload 失败: \(error.localizedDescription)")
    }
  }

  /// 长时间没有收到 extension payload 时输出等待状态。
  private func emitWaitingStateIfNeeded() {
    let now = Date()
    let shouldEmit: Bool
    if let lastWaitingStateAt = lastWaitingStateAt {
      shouldEmit = now.timeIntervalSince(lastWaitingStateAt) >= 1.0
    } else {
      shouldEmit = true
    }
    guard shouldEmit else { return }
    lastWaitingStateAt = now
    emitState(capturing: pendingStartResult == nil ? isCapturing : true, message: "waiting_for_broadcast_audio")
  }

  /// 系统 picker 没有返回回调，因此用首个 payload 判断用户是否真正启动。
  private func handleStartTimeout() {
    guard pendingStartResult != nil else { return }
    removePickerView()
    stopPolling()
    clearSharedFiles(includeStopRequest: true)
    isCapturing = false
    finishPendingStart(
      with: FlutterError(
        code: "PERMISSION_DENIED",
        message: "用户未启动 ReplayKit Broadcast",
        details: nil
      )
    )
  }

  /// 取消 start 超时计时器。
  private func invalidateStartTimeout() {
    startTimeoutTimer?.invalidate()
    startTimeoutTimer = nil
  }

  /// 写入 extension 读取的捕获配置。
  private func writeCaptureConfig() {
    let payload: [String: Any] = [
      "sampleRate": sampleRate,
      "channels": channels,
      "frameMs": frameMs,
    ]
    writeSharedJSON(payload, fileName: SharedStore.configFileName)
  }

  /// 写入 extension 停止请求。
  private func requestExtensionStop() {
    writeSharedJSON(["requestedAt": Date().timeIntervalSince1970], fileName: SharedStore.stopFileName)
  }

  /// 删除共享文件，避免下一次启动读到脏状态。
  private func clearSharedFiles(includeStopRequest: Bool) {
    var fileNames = [SharedStore.configFileName, SharedStore.payloadFileName]
    if includeStopRequest {
      fileNames.append(SharedStore.stopFileName)
    }
    for fileName in fileNames {
      guard let fileURL = sharedFileURL(fileName: fileName) else { continue }
      try? FileManager.default.removeItem(at: fileURL)
    }
    lastPayloadData = nil
    lastPayloadReceivedAt = nil
    lastWaitingStateAt = nil
  }

  /// 移除临时 picker view。
  private func removePickerView() {
    pickerView?.removeFromSuperview()
    pickerView = nil
  }

  /// 获取 App Group 文件路径。
  private func sharedFileURL(fileName: String) -> URL? {
    FileManager.default
      .containerURL(
        forSecurityApplicationGroupIdentifier: SharedStore.appGroupIdentifier
      )?
      .appendingPathComponent(fileName)
  }

  /// 将 JSON 内容写入 App Group 文件。
  private func writeSharedJSON(_ payload: [String: Any], fileName: String) {
    guard let url = sharedFileURL(fileName: fileName) else { return }
    do {
      let data = try JSONSerialization.data(withJSONObject: payload, options: [])
      try data.write(to: url, options: .atomic)
    } catch {
      log("写入共享文件失败: \(fileName), \(error.localizedDescription)")
    }
  }

  /// 完成待返回的 start result。
  private func finishPendingStart(with value: Any) {
    let result = pendingStartResult
    pendingStartResult = nil
    result?(value)
  }

  /// 构建稳定结构的状态帧。
  private func emitState(capturing: Bool, message: String) {
    eventSink?([
      "source": SharedStore.source,
      "rms": 0.0,
      "db": -160.0,
      "linearPower": 0.0,
      "duty": 0.0,
      "sampleRate": sampleRate,
      "channels": channels,
      "capturing": capturing,
      "message": message,
    ])
  }

  /// 目标 Broadcast Upload Extension bundle id。
  private func preferredExtensionBundleIdentifier() -> String {
    let hostBundleIdentifier = Bundle.main.bundleIdentifier ?? "com.pechtak.app"
    return "\(hostBundleIdentifier).PlaybackAudioBroadcastExtension"
  }

  /// 查找当前可用于挂载系统 picker 的顶层控制器。
  private func topViewController(
    from controller: UIViewController? = UIApplication.shared.connectedScenes
      .compactMap { $0 as? UIWindowScene }
      .flatMap { $0.windows }
      .first(where: { $0.isKeyWindow })?
      .rootViewController
  ) -> UIViewController? {
    if let navigationController = controller as? UINavigationController {
      return topViewController(from: navigationController.visibleViewController)
    }
    if let tabBarController = controller as? UITabBarController {
      return topViewController(from: tabBarController.selectedViewController)
    }
    if let presentedController = controller?.presentedViewController {
      return topViewController(from: presentedController)
    }
    return controller
  }

  private func log(_ message: String) {
    if enableLog {
      print("[PlaybackAudioCapture] \(message)")
    }
  }
}
