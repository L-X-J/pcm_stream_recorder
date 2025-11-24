import Flutter
import UIKit
import AVFoundation

/// 轻量级原生录音插件 - iOS 实现
/// 使用 AVAudioEngine 实现 PCM 实时回调与 WAV 文件录制
public class PcmStreamRecorderPlugin: NSObject, FlutterPlugin {
  private var methodChannel: FlutterMethodChannel?
  private var eventChannel: FlutterEventChannel?
  private var eventSink: FlutterEventSink?
  private var routeChangeObserver: NSObjectProtocol?
  
  private var audioEngine: AVAudioEngine?
  private var inputNode: AVAudioInputNode?
  private var isRecording = false
  private var resamplePosition: Double = 0.0
  private var enableLog = false
  
  // 录音配置
  private var sampleRate: Int = 16000
  private var channels: Int = 1
  private var bufferSize: Int = 1600
  
  // 调试计数器
  private var sendCount: Int = 0
  private var zeroFrameStreak: Int = 0
  private var isHealingRoute: Bool = false
  
  private let stopQueue = DispatchQueue(label: "pcm.recorder.stop", qos: .userInitiated)
  private static let deactivateQueue = DispatchQueue(label: "pcm.recorder.deactivate", qos: .userInitiated)
  private static let sessionQueue = DispatchQueue(label: "pcm.recorder.session", qos: .userInitiated)
  private static var savedCategory: AVAudioSession.Category?
  private static var savedMode: AVAudioSession.Mode?
  private static var savedOptions: AVAudioSession.CategoryOptions = []
  private static var savedPreferredSampleRate: Double?

  private func log(_ message: String) {
    if enableLog {
      print("[PcmStreamRecorder] \(message)")
    }
  }


  private func startObservingRouteChanges() {
    stopObservingRouteChanges()
    routeChangeObserver = NotificationCenter.default.addObserver(
      forName: AVAudioSession.routeChangeNotification,
      object: nil,
      queue: .main
    ) { [weak self] _ in
      DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
        guard let self = self, self.isRecording else { return }
        self.reconfigureInputTap()
      }
    }
  }

  private func stopObservingRouteChanges() {
    if let observer = routeChangeObserver {
      NotificationCenter.default.removeObserver(observer)
      routeChangeObserver = nil
    }
  }
  
  public static func register(with registrar: FlutterPluginRegistrar) {
    let instance = PcmStreamRecorderPlugin()
    
    // MethodChannel 用于方法调用
    let methodChannel = FlutterMethodChannel(
      name: "pcm_stream_recorder",
      binaryMessenger: registrar.messenger()
    )
    instance.methodChannel = methodChannel
    
    // EventChannel 用于流式传输 PCM 数据
    let eventChannel = FlutterEventChannel(
      name: "pcm_stream_recorder/audio_stream",
      binaryMessenger: registrar.messenger()
    )
    eventChannel.setStreamHandler(instance)
    instance.eventChannel = eventChannel
    
    // 注册 MethodChannel 处理器
    registrar.addMethodCallDelegate(instance, channel: methodChannel)
  }
  
  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    switch call.method {
    case "checkPermission":
      checkPermission(result: result)
      
    case "requestPermission":
      requestPermission(result: result)
      
    case "start":
      guard let args = call.arguments as? [String: Any] else {
        result(FlutterError(
          code: "INVALID_ARGUMENT",
          message: "参数格式错误",
          details: nil
        ))
        return
      }
      startRecording(
        sampleRate: args["sampleRate"] as? Int ?? 16000,
        channels: args["channels"] as? Int ?? 1,
        bufferSize: args["bufferSize"] as? Int ?? 1600,
        enableLog: args["enableLog"] as? Bool ?? false,
        result: result
      )
      
    case "stop":
      stopRecording(result: result)
      
    case "deactivateSession":
      PcmStreamRecorderPlugin.deactivateSession(result: result)
    
    case "prepareAudioSession":
      PcmStreamRecorderPlugin.prepareAudioSession(result: result)
    
    case "restoreAudioSession":
      PcmStreamRecorderPlugin.restoreAudioSession(result: result)
      
    case "pause":
      pauseRecording(result: result)
      
    case "resume":
      resumeRecording(result: result)
      
    case "isRecording":
      result(isRecording)
      
    default:
      result(FlutterMethodNotImplemented)
    }
  }
  
  /// 检查录音权限
  private func checkPermission(result: @escaping FlutterResult) {
    let status = AVAudioSession.sharedInstance().recordPermission
    switch status {
    case .granted:
      result(true)
    case .denied:
      result(false)
    case .undetermined:
      result(false)
    @unknown default:
      result(false)
    }
  }
  
  /// 请求录音权限
  private func requestPermission(result: @escaping FlutterResult) {
    AVAudioSession.sharedInstance().requestRecordPermission { granted in
      DispatchQueue.main.async {
        result(granted)
      }
    }
  }
  
  /// 开始录音
  private func startRecording(
    sampleRate: Int,
    channels: Int,
    bufferSize: Int,
    enableLog: Bool,
    result: @escaping FlutterResult
  ) {
    guard !isRecording else {
      result(FlutterError(
        code: "ALREADY_RECORDING",
        message: "录音已在进行中",
        details: nil
      ))
      return
    }
    
    // 验证参数有效性
    guard sampleRate > 0 else {
      result(FlutterError(
        code: "INVALID_ARGUMENT",
        message: "采样率必须大于 0，当前值: \(sampleRate)",
        details: nil
      ))
      return
    }
    
    guard channels == 1 || channels == 2 else {
      result(FlutterError(
        code: "INVALID_ARGUMENT",
        message: "声道数必须为 1（单声道）或 2（立体声），当前值: \(channels)",
        details: nil
      ))
      return
    }
    
    guard bufferSize > 0 else {
      result(FlutterError(
        code: "INVALID_ARGUMENT",
        message: "缓冲区大小必须大于 0，当前值: \(bufferSize)",
        details: nil
      ))
      return
    }
    
    // 检查权限
    let permissionStatus = AVAudioSession.sharedInstance().recordPermission
    guard permissionStatus == .granted else {
      result(FlutterError(
        code: "PERMISSION_DENIED",
        message: "录音权限未授予",
        details: nil
      ))
      return
    }
    
    self.sampleRate = sampleRate
    self.channels = channels
    self.bufferSize = bufferSize
    self.enableLog = enableLog
    self.resamplePosition = 0.0
    
    do {
      // 创建音频引擎
      let engine = AVAudioEngine()
      let inputNode = engine.inputNode
      
      // 获取输入格式
      let inputFormat = inputNode.inputFormat(forBus: 0)
      
      // 创建目标格式（16-bit PCM，指定采样率和声道数）
      // 注意：即使参数已验证，AVAudioFormat 仍可能因系统限制而创建失败
      guard let targetFormat = AVAudioFormat(
        commonFormat: .pcmFormatInt16,
        sampleRate: Double(sampleRate),
        channels: AVAudioChannelCount(channels),
        interleaved: false
      ) else {
        result(FlutterError(
          code: "FORMAT_ERROR",
          message: "无法创建目标音频格式：采样率=\(sampleRate)Hz, 声道数=\(channels)。请检查参数是否在系统支持的范围内。",
          details: "sampleRate: \(sampleRate), channels: \(channels)"
        ))
        return
      }
      
      // 如果采样率或声道数不匹配，需要转换
      let needsConversion = inputFormat.sampleRate != Double(sampleRate) || 
                           inputFormat.channelCount != AVAudioChannelCount(channels)
      
      // 确保在安装新 tap 之前移除可能存在的旧 tap
      // 这可以防止在重新启动录音时出现 "Tap already exists" 错误
      inputNode.removeTap(onBus: 0)
      
      if needsConversion {
        // 安装 tap 来接收原始 PCM 数据，手动重采样为 Int16 PCM
        inputNode.installTap(
          onBus: 0,
          bufferSize: AVAudioFrameCount(bufferSize * 4), // 更大的 buffer 用于转换
          format: inputFormat
        ) { [weak self] buffer, time in
          guard let self = self, self.isRecording else { return }
          self.processBufferWithResample(
            buffer,
            inputFormat: inputFormat,
            targetSampleRate: targetFormat.sampleRate,
            targetChannels: Int(targetFormat.channelCount)
          )
        }
      } else {
        // 格式匹配，直接使用
        inputNode.installTap(
          onBus: 0,
          bufferSize: AVAudioFrameCount(bufferSize),
          format: inputFormat
        ) { [weak self] buffer, time in
          guard let self = self, self.isRecording else { return }
          self.sendPCMData(buffer)
        }
      }
      
      // 启动引擎
      try engine.start()
      
      self.audioEngine = engine
      self.inputNode = inputNode
      self.isRecording = true
      
      log("录音已启动")
      log("目标采样率: \(sampleRate)Hz, 声道: \(channels), 缓冲区: \(bufferSize)")
      log("输入格式: \(inputFormat.sampleRate)Hz, \(inputFormat.channelCount)ch, \(inputFormat.commonFormat.rawValue)")
      log("目标格式: \(targetFormat.sampleRate)Hz, \(targetFormat.channelCount)ch, \(targetFormat.commonFormat.rawValue)")
      log("需要转换: \(needsConversion)")
      
      // 检查 eventSink 是否已连接（应该在 Dart 端调用 listen() 时已设置）
      if eventSink == nil {
        log("⚠️ 警告: eventSink 未连接，PCM 数据可能无法发送到 Flutter")
        log("请确保在调用 start() 之前已调用 receiveBroadcastStream().listen()")
      } else {
        log("✅ eventSink 已连接，可以正常发送 PCM 数据")
      }
      
      startObservingRouteChanges()
      result(true)
    } catch {
      result(FlutterError(
        code: "START_FAILED",
        message: "启动录音失败: \(error.localizedDescription)",
        details: error.localizedDescription
      ))
    }
  }
  
  /// 发送 PCM 数据到 Flutter
  private func sendPCMData(_ buffer: AVAudioPCMBuffer) {
    let frameLength = Int(buffer.frameLength)
    let channelCount = Int(buffer.format.channelCount)
    
    // 检查 buffer 格式并转换
    var pcmData: Data
    var maxAmplitude: Int16 = 0
    var sampleCount: Int = 0
    
    if let int16Data = buffer.int16ChannelData {
      // 已经是 int16 格式，直接转换为字节数组（小端序）
      let dataSize = frameLength * channelCount * 2 // 16-bit = 2 bytes
      pcmData = Data(count: dataSize)
      
      pcmData.withUnsafeMutableBytes { bytes in
        let int16Pointer = bytes.bindMemory(to: Int16.self)
        var index = 0
        for frame in 0..<frameLength {
          for channel in 0..<channelCount {
            // 直接使用小端序（iOS 本身就是小端序）
            let sample = int16Data[channel][frame]
            int16Pointer[index] = sample
            maxAmplitude = max(maxAmplitude, abs(sample))
            sampleCount += 1
            index += 1
          }
        }
      }
    } else if let float32Data = buffer.floatChannelData {
      // 需要从 float32 转换为 int16
      let dataSize = frameLength * channelCount * 2 // 16-bit = 2 bytes
      pcmData = Data(count: dataSize)
      
      pcmData.withUnsafeMutableBytes { bytes in
        let int16Pointer = bytes.bindMemory(to: Int16.self)
        var index = 0
        for frame in 0..<frameLength {
          for channel in 0..<channelCount {
            let floatSample = float32Data[channel][frame]
            // 限制范围 [-1.0, 1.0] 并转换为 int16
            let clampedSample = max(-1.0, min(1.0, floatSample))
            let int16Sample = Int16(clampedSample * 32767.0)
            int16Pointer[index] = int16Sample
            maxAmplitude = max(maxAmplitude, abs(int16Sample))
            sampleCount += 1
            index += 1
          }
        }
      }
    } else {
      // 不支持的格式
      log("不支持的音频格式: \(buffer.format)")
      return
    }
    
    // 检查数据是否为空
    guard pcmData.count > 0 else {
      log("⚠️ 警告: PCM 数据为空，跳过发送")
      return
    }
    
    // EventChannel 必须在主线程调用
    emitPCMData(pcmData)
    if enableLog {
      log("发送 PCM 数据，帧数: \(frameLength)，通道: \(channelCount)，字节: \(pcmData.count)，最大幅度: \(maxAmplitude)，采样数: \(sampleCount)")
    }
    // 检测连续静音帧，尝试自愈输入管线（蓝牙路由切换后常见）
    if maxAmplitude == 0 {
      zeroFrameStreak += 1
    } else {
      zeroFrameStreak = 0
    }
    if zeroFrameStreak >= 8, !isHealingRoute, isRecording {
      isHealingRoute = true
      DispatchQueue.main.async { [weak self] in
        guard let self = self else { return }
        self.reconfigureInputTap()
        self.zeroFrameStreak = 0
        self.isHealingRoute = false
        if self.enableLog { self.log("已触发路由自愈：已重启音频引擎并重装 tap") }
      }
    }
  }
  
  /// 发送 PCM 数据 (Data)
  private func emitPCMData(_ pcmData: Data) {
    guard !pcmData.isEmpty else { return }
    DispatchQueue.main.async { [weak self] in
      guard let self = self else { return }
      guard let eventSink = self.eventSink else {
        log("⚠️ eventSink 为 nil，数据已丢弃 (大小: \(pcmData.count) bytes)")
        return
      }
      eventSink(FlutterStandardTypedData(bytes: pcmData))
      self.sendCount += 1
      if self.sendCount <= 5 {
        log("✅ 已发送 PCM 数据 #\(self.sendCount)，大小: \(pcmData.count) bytes")
      }
    }
  }
  
  /// 处理需要重采样的音频缓冲
  private func processBufferWithResample(
    _ buffer: AVAudioPCMBuffer,
    inputFormat: AVAudioFormat,
    targetSampleRate: Double,
    targetChannels: Int
  ) {
    let inputFrames = Int(buffer.frameLength)
    guard inputFrames > 0 else { return }
    
    let inputSampleRate = inputFormat.sampleRate
    guard inputSampleRate > 0, targetSampleRate > 0 else { return }
    
    let ratio = inputSampleRate / targetSampleRate
    guard ratio > 0 else { return }
    
    let channelCount = Int(inputFormat.channelCount)
    guard channelCount > 0 else { return }
    
    log("🎙️ 输入帧数: \(inputFrames) (需要重采样 ratio=\(ratio))")
    
    var monoBuffer = [Float](repeating: 0.0, count: inputFrames)
    if let floatChannels = buffer.floatChannelData {
      let stride = buffer.stride
      for ch in 0..<channelCount {
        let channelData = floatChannels[ch]
        for i in 0..<inputFrames {
          monoBuffer[i] += channelData[i * stride]
        }
      }
      if channelCount > 1 {
        let inv = 1.0 / Float(channelCount)
        for i in 0..<inputFrames {
          monoBuffer[i] *= inv
        }
      }
    } else if let int16Channels = buffer.int16ChannelData {
      let stride = buffer.stride
      for ch in 0..<channelCount {
        let channelData = int16Channels[ch]
        for i in 0..<inputFrames {
          monoBuffer[i] += Float(channelData[i * stride]) / 32768.0
        }
      }
      if channelCount > 1 {
        let inv = 1.0 / Float(channelCount)
        for i in 0..<inputFrames {
          monoBuffer[i] *= inv
        }
      }
    } else {
      log("❌ 不支持的音频格式: \(inputFormat.commonFormat.rawValue)")
      return
    }
    
    var position = resamplePosition
    var outputSamples = [Int16]()
    outputSamples.reserveCapacity(Int(Double(inputFrames) / ratio) + 1)
    let lastIndex = max(inputFrames - 1, 0)
    
    while position < Double(inputFrames) {
      let idx = Int(position)
      let frac = Float(position - Double(idx))
      let nextIdx = min(idx + 1, lastIndex)
      let current = monoBuffer[idx]
      let next = monoBuffer[nextIdx]
      let interpolated = current + frac * (next - current)
      let clamped = max(-1.0, min(1.0, interpolated))
      let sample = Int16(clamped * 32767.0)
      outputSamples.append(sample)
      position += ratio
    }
    
    resamplePosition = position - Double(inputFrames)
    if resamplePosition >= ratio {
      resamplePosition.formTruncatingRemainder(dividingBy: ratio)
    }
    
    if outputSamples.isEmpty {
      log("⚠️ 重采样后无可用数据")
      return
    }
    
    let outputFrameCount = outputSamples.count
    let outputChannels = max(targetChannels, 1)
    let bytesPerSample = 2 // Int16
    var maxAmplitude: Int16 = 0
    var pcmData = Data(count: outputFrameCount * outputChannels * bytesPerSample)
    pcmData.withUnsafeMutableBytes { rawBuffer in
      let buffer = rawBuffer.bindMemory(to: Int16.self)
      guard let baseAddress = buffer.baseAddress else { return }
      for frame in 0..<outputFrameCount {
        let sample = outputSamples[frame]
        maxAmplitude = max(maxAmplitude, abs(sample))
        for ch in 0..<outputChannels {
          baseAddress[frame * outputChannels + ch] = sample
        }
      }
    }
    
    log("✅ 重采样成功，输出帧数: \(outputFrameCount)，字节: \(pcmData.count)，最大幅度: \(maxAmplitude)")
    emitPCMData(pcmData)
  }
  
  /// 停止录音
  private func stopRecording(result: @escaping FlutterResult) {
    guard isRecording else {
      result(false)
      return
    }
    
    isRecording = false
    sendCount = 0
    stopObservingRouteChanges()
    let engine = audioEngine
    let node = inputNode
    stopQueue.async { [weak self] in
      guard let self = self else { return }
      node?.removeTap(onBus: 0)
      engine?.stop()
      self.audioEngine = nil
      self.inputNode = nil
      self.resamplePosition = 0.0
      DispatchQueue.main.async {
        result(true)
      }
    }
  }

  public static func deactivateSession(result: @escaping FlutterResult) {
    deactivateQueue.async {
      let audioSession = AVAudioSession.sharedInstance()
      do {
        try audioSession.setActive(false, options: .notifyOthersOnDeactivation)
        DispatchQueue.main.async { result(true) }
      } catch {
        DispatchQueue.main.async {
          result(FlutterError(code: "DEACTIVATE_FAILED", message: error.localizedDescription, details: error.localizedDescription))
        }
      }
    }
  }

  public static func prepareAudioSession(result: @escaping FlutterResult) {
    sessionQueue.async {
      let audioSession = AVAudioSession.sharedInstance()
      do {
        if savedCategory == nil {
          savedCategory = audioSession.category
          savedMode = audioSession.mode
          savedOptions = audioSession.categoryOptions
          savedPreferredSampleRate = audioSession.preferredSampleRate
        }
        var options: AVAudioSession.CategoryOptions = [.allowBluetooth]
        if savedOptions.contains(.mixWithOthers) {
          options.insert(.mixWithOthers)
        }
        try audioSession.setCategory(.playAndRecord, mode: .voiceChat, options: options)
        try audioSession.setPreferredSampleRate(16000)
        try audioSession.setActive(true, options: [])
        DispatchQueue.main.async { result(true) }
      } catch {
        DispatchQueue.main.async {
          result(FlutterError(code: "PREPARE_SESSION_FAILED", message: error.localizedDescription, details: error.localizedDescription))
        }
      }
    }
  }

  public static func restoreAudioSession(result: @escaping FlutterResult) {
    sessionQueue.async {
      let audioSession = AVAudioSession.sharedInstance()
      do {
        try audioSession.setActive(false, options: .notifyOthersOnDeactivation)
        if let category = savedCategory {
          let mode = savedMode ?? .default
          let options = savedOptions
          try audioSession.setCategory(category, mode: mode, options: options)
          if let preferredRate = savedPreferredSampleRate {
            try? audioSession.setPreferredSampleRate(preferredRate)
          }
          savedCategory = nil
          savedMode = nil
          savedOptions = []
          savedPreferredSampleRate = nil
        } else {
          try audioSession.setCategory(.playback, mode: .default, options: [])
        }
        DispatchQueue.main.async { result(true) }
      } catch {
        DispatchQueue.main.async {
          result(FlutterError(code: "RESTORE_SESSION_FAILED", message: error.localizedDescription, details: error.localizedDescription))
        }
      }
    }
  }
  
  
  /// 暂停录音（iOS 不支持真正的暂停，这里停止 tap）
  private func pauseRecording(result: @escaping FlutterResult) {
    guard isRecording else {
      result(false)
      return
    }
    
    inputNode?.removeTap(onBus: 0)
    result(true)
  }
  
  /// 恢复录音
  private func resumeRecording(result: @escaping FlutterResult) {
    guard isRecording, let engine = audioEngine, let inputNode = inputNode else {
      result(false)
      return
    }
    
    // 验证参数有效性（防止在恢复时使用无效参数）
    guard sampleRate > 0 && (channels == 1 || channels == 2) else {
      result(FlutterError(
        code: "INVALID_STATE",
        message: "录音状态异常：采样率=\(sampleRate)Hz, 声道数=\(channels)。请重新启动录音。",
        details: nil
      ))
      return
    }
    
    let inputFormat = inputNode.inputFormat(forBus: 0)
    let targetSampleRate = Double(sampleRate)
    let targetChannels = channels
    let needsConversion = inputFormat.sampleRate != targetSampleRate ||
      inputFormat.channelCount != AVAudioChannelCount(targetChannels)

    let tapBufferSize = needsConversion ? AVAudioFrameCount(bufferSize * 4) : AVAudioFrameCount(bufferSize)

    // 确保在安装新 tap 之前移除可能存在的旧 tap
    // 这可以防止在恢复录音时出现 "Tap already exists" 错误
    inputNode.removeTap(onBus: 0)

    inputNode.installTap(
      onBus: 0,
      bufferSize: tapBufferSize,
      format: inputFormat
    ) { [weak self] buffer, time in
      guard let self = self, self.isRecording else { return }
      if needsConversion {
        self.processBufferWithResample(
          buffer,
          inputFormat: inputFormat,
          targetSampleRate: targetSampleRate,
          targetChannels: targetChannels
        )
      } else {
        self.sendPCMData(buffer)
      }
    }
    
    result(true)
  }

  private func reconfigureInputTap() {
    guard isRecording, let inputNode = inputNode else { return }
    let inputFormat = inputNode.inputFormat(forBus: 0)
    let targetSampleRate = Double(sampleRate)
    let targetChannels = channels
    let needsConversion = inputFormat.sampleRate != targetSampleRate ||
      inputFormat.channelCount != AVAudioChannelCount(targetChannels)

    let tapBufferSize = needsConversion ? AVAudioFrameCount(bufferSize * 4) : AVAudioFrameCount(bufferSize)
    inputNode.removeTap(onBus: 0)
    // 为适配蓝牙路由切换，重启并重置引擎
    do {
      try audioEngine?.start()
    } catch {
      audioEngine?.stop()
      audioEngine?.reset()
      do {
        try audioEngine?.start()
        log("已在路由变更后重启音频引擎")
      } catch {
        log("重启音频引擎失败: \(error.localizedDescription)")
      }
    }
    inputNode.installTap(
      onBus: 0,
      bufferSize: tapBufferSize,
      format: inputFormat
    ) { [weak self] buffer, time in
      guard let self = self, self.isRecording else { return }
      if needsConversion {
        self.processBufferWithResample(
          buffer,
          inputFormat: inputFormat,
          targetSampleRate: targetSampleRate,
          targetChannels: targetChannels
        )
      } else {
        self.sendPCMData(buffer)
      }
    }
  }
}

// MARK: - FlutterStreamHandler
extension PcmStreamRecorderPlugin: FlutterStreamHandler {
  public func onListen(
    withArguments arguments: Any?,
    eventSink events: @escaping FlutterEventSink
  ) -> FlutterError? {
    log("EventChannel onListen called")
    eventSink = events
    return nil
  }
  
  public func onCancel(withArguments arguments: Any?) -> FlutterError? {
    log("EventChannel onCancel called")
    eventSink = nil
    return nil
  }
}
