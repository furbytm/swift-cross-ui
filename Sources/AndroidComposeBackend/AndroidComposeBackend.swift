import Android
import Foundation
import SwiftCrossUI
import AndroidKit
import AndroidGraphics
import AndroidBackendShim

/// A widget is just its integer node ID.
public typealias ComposeWidget = Int32

public extension ComposeWidget {
  init() {
    self = -1
  }
}

/// Per-widget closures registered when a widget is created.
private struct EventHandlers {
    var onClick: (() -> Void)?
    var onChange: ((String) -> Void)?
    var onToggle: ((Bool) -> Void)?
    var onSlide:  ((Double) -> Void)?
}

/// Decoded event from the JSON queue.
private struct IncomingEvent {
    let id: Int32
    let type: String
    let payload: String

    init?(_ json: String) {
        guard let idRange    = json.range(of: #"(?<="id":)\d+"#,  options: .regularExpression),
              let typeRange  = json.range(of: #"(?<="type":")\w+"#, options: .regularExpression),
              let payRange   = json.range(of: #"(?<="payload":")(.*?)(?=")"#, options: .regularExpression)
        else { return nil }

        guard let idVal = Int32(json[idRange]) else { return nil }
        self.id      = idVal
        self.type    = String(json[typeRange])
        self.payload = String(json[payRange])
    }
}

func log(_ message: String) {
    android_log(Int32(ANDROID_LOG_DEBUG.rawValue), "swift", message)
}

/// A valid AndroidBackend shim must call this to begin execution of the app.
/// Once initial setup and rendering is done, this function returns control
/// back to the JVM (by returning).
@MainActor
@_cdecl("AndroidBackend_entrypoint")
public func entrypoint(_ env: UnsafeMutablePointer<JNIEnv?>, _ object: jobject) {
    AndroidComposeBackend.env = env

    let holder = JavaObjectHolder(object: object, environment: env)
    AndroidComposeBackend.javaHolder = holder
    // AndroidComposeBackend.activity = Activity(javaHolder: holder)

    // Source: https://phatbl.at/2019/01/08/intercepting-stdout-in-swift.html
    func makeMessageHandler(priority: UInt32) -> @Sendable (FileHandle) -> Void {
        @Sendable
        nonisolated func forward(_ fileHandle: FileHandle) {
            let data = fileHandle.availableData
            guard let string = String(data: data, encoding: .utf8) else {
                return
            }

            android_log(
                Int32(priority),
                "Swift",
                string
            )
        }
        return forward
    }

    AndroidComposeBackend.stdoutPipe.fileHandleForReading.readabilityHandler =
        makeMessageHandler(priority: ANDROID_LOG_INFO.rawValue)

    AndroidComposeBackend.stderrPipe.fileHandleForReading.readabilityHandler =
        makeMessageHandler(priority: ANDROID_LOG_ERROR.rawValue)

    dup2(
        AndroidComposeBackend.stdoutPipe.fileHandleForWriting.fileDescriptor,
        FileHandle.standardOutput.fileDescriptor
    )

    dup2(
        AndroidComposeBackend.stderrPipe.fileHandleForWriting.fileDescriptor,
        FileHandle.standardError.fileDescriptor
    )

    // Pass dummy arguments to application main function
    let argv = UnsafeMutableBufferPointer<UnsafeMutablePointer<CChar>?>.allocate(capacity: 1)
    argv[0] = nil

    main(0, argv.baseAddress)
}

extension App {
    public typealias Backend = AndroidComposeBackend

    public var backend: AndroidComposeBackend {
        AndroidComposeBackend()
    }
}

public final class AndroidComposeBackend: BackendFeatures.BaseStubs {
    public final class Window {
        var content: Widget?
    }
  
    public typealias Widget = ComposeWidget

    static let stdoutPipe = Pipe()
    static let stderrPipe = Pipe()
  
    // Shared bridge to the Kotlin host
    private let bridge: AndroidComposeBackendBridge

    // Widget ID allocator — Swift owns this, never Kotlin
    private var nextId: Int32 = 1
    private func allocateId() -> Int32 {
        defer { nextId += 1 }
        return nextId
    }

    // Event handler registry — keyed by widget ID
    private var handlers: [Int32: EventHandlers] = [:]

    // The Task running the event polling loop
    private var eventLoopTask: Task<Void, Never>?

    /// A reference used to keep the tickler alive.
    var tickler: MainRunLoopTickler?
  
    /// The JNI environment pointer. Set by ``entrypoint``.
    static var env: UnsafeMutablePointer<JNIEnv?>!
    /// The underlying java object. Set by ``entrypoint``.
    static var javaHolder: JavaObjectHolder!
    /// The main activity. Set by ``entrypoint``.
    // static var activity: Activity!
  
    public init() {
        self.bridge = AndroidComposeBackendBridge(javaHolder: Self.javaHolder)
        startEventLoop()
    }

    deinit {
        eventLoopTask?.cancel()
        try? bridge.clearAll()
    }

    /// swift-cross-ui calls this to hand control to the backend's run loop.
    /// On Android the Activity already has a Looper, so we just invoke the
    /// callback (which builds the initial widget tree) and return.
    public func runMainLoop(
        _ callback: @escaping @MainActor () -> Void
    ) {
        let tickler = MainRunLoopTickler(environment: Self.env)
        tickler.start()
        self.tickler = tickler
      
        callback()
    }

    public func createContainer() -> Widget {
        let id = allocateId()
        try? bridge.createNode(id: id, type: "Container")
        return id
    }

    public func setPosition(ofChildAt index: Int, in container: Widget, to position: SIMD2<Int>) {
        try? bridge.setPosition(containerId: container, index: Int32(index), x: Int32(position.x), y: Int32(position.y))
    }

    public func setChildren(_ children: [Widget], ofContainer container: Widget) {
        try? bridge.setChildren(parentId: container, childIds: children)
    }

    public func setRootWidget(_ widget: Widget) {
        try? bridge.setRootNode(id: widget)
    }

    public func createTextView(content: String, shouldWrap: Bool) -> Widget {
        let id = allocateId()
        try? bridge.createNode(id: id, type: "Text")
        try? bridge.setProperty(id: id, key: "text", value: content)
        return id
    }

    public func updateTextView(_ widget: Widget, content: String, shouldWrap: Bool) {
        try? bridge.setProperty(id: widget, key: "text", value: content)
    }

    public func createButton(label: String, action: @escaping () -> Void) -> Widget {
        let id = allocateId()
        try? bridge.createNode(id: id, type: "Button")
        try? bridge.setProperty(id: id, key: "label", value: label)
        handlers[id, default: EventHandlers()].onClick = action
        return id
    }

    public func updateButton(_ widget: Widget, label: String, action: @escaping () -> Void) {
        try? bridge.setProperty(id: widget, key: "label", value: label)
        handlers[widget, default: EventHandlers()].onClick = action
    }

    public func createTextField(
        placeholder: String,
        value: String,
        onChange: @escaping (String) -> Void
    ) -> Widget {
        let id = allocateId()
        try? bridge.createNode(id: id, type: "TextField")
        try? bridge.setProperty(id: id, key: "placeholder", value: placeholder)
        try? bridge.setProperty(id: id, key: "value", value: value)
        handlers[id, default: EventHandlers()].onChange = onChange
        return id
    }

    public func updateTextField(
        _ widget: Widget,
        placeholder: String,
        value: String,
        onChange: @escaping (String) -> Void
    ) {
        try? bridge.setProperty(id: widget, key: "placeholder", value: placeholder)
        try? bridge.setProperty(id: widget, key: "value", value: value)
        handlers[widget, default: EventHandlers()].onChange = onChange
    }

    public func createToggle(
        label: String,
        value: Bool,
        onChange: @escaping (Bool) -> Void
    ) -> Widget {
        let id = allocateId()
        try? bridge.createNode(id: id, type: "Toggle")
        try? bridge.setProperty(id: id, key: "label", value: label)
        try? bridge.setProperty(id: id, key: "value", value: value ? "true" : "false")
        handlers[id, default: EventHandlers()].onToggle = onChange
        return id
    }

    public func updateToggle(_ widget: Widget, label: String, value: Bool) {
        try? bridge.setProperty(id: widget, key: "label", value: label)
        try? bridge.setProperty(id: widget, key: "value", value: value ? "true" : "false")
    }

    public func createSlider(
        value: Double,
        min: Double,
        max: Double,
        onChange: @escaping (Double) -> Void
    ) -> Widget {
        let id = allocateId()
        try? bridge.createNode(id: id, type: "Slider")
        try? bridge.setProperty(id: id, key: "value", value: "\(value)")
        try? bridge.setProperty(id: id, key: "min", value: "\(min)")
        try? bridge.setProperty(id: id, key: "max", value: "\(max)")
        handlers[id, default: EventHandlers()].onSlide = onChange
        return id
    }

    public func createSpacer(flexing: Bool, size: Int?) -> Widget {
        let id = allocateId()
        try? bridge.createNode(id: id, type: "Spacer")
        try? bridge.setProperty(id: id, key: "flex", value: flexing ? "true" : "false")
        if let s = size {
            try? bridge.setProperty(id: id, key: "size", value: "\(s)")
        }
        return id
    }

    public func createDivider() -> Widget {
        let id = allocateId()
        try? bridge.createNode(id: id, type: "Divider")
        return id
    }

    public func destroyWidget(_ widget: Widget) {
        handlers.removeValue(forKey: widget)
        try? bridge.removeNode(id: widget)
    }

    /// Runs on a background Swift concurrency Task. Drains the Kotlin event queue
    /// and dispatches to the registered handler closures.
    ///
    /// The loop yields after each poll so it doesn't pin a thread when idle.
    /// When there are pending events it spins without yielding for low latency.
    private func startEventLoop() {
        eventLoopTask = Task.detached(priority: .userInitiated) { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }

                var dispatched = 0

                // Drain up to 64 events per iteration to bound latency
                for _ in 0..<64 {
                    let json = try? await MainActor.run {
                        try self.bridge.pollEvent()
                    }
                    guard let json, !json.isEmpty else { break }
                  
                    guard let event = IncomingEvent(json) else { continue }
                    await self.dispatch(event)
                    dispatched += 1
                }

                // If we got nothing, yield for ~4 ms before polling again
                if dispatched == 0 {
                    try? await Task.sleep(nanoseconds: 4_000_000)
                }
            }
        }
    }

    @MainActor
    private func dispatch(_ event: IncomingEvent) {
        guard let h = handlers[event.id] else { return }
        switch event.type {
        case "click":
            h.onClick?()
        case "change":
            h.onChange?(event.payload)
        case "toggle":
            h.onToggle?(event.payload == "true")
        case "slide", "slideEnd":
            if let v = Double(event.payload) { h.onSlide?(v) }
        default:
            break
        }
    }
}
