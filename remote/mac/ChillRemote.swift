// Chill TV Remote: a floating dock for the Chill TV Showcase launcher.
// A 48 px circle that opens into a remote laid out like the Mi Box remote. It talks to the Chill TV Showcase launcher's
// remote server (port 8765) on your network; works with both Chill TV Home and Showcase. Collapsed it does nothing at all
// (no timers, no network) unless Voice is on.
// Voice: the Mac's microphone and Apple's speech recognizer, on the Mac itself where the Mac supports it. Say "TV" then a
// command ("TV, open Netflix", "TV, pause", "TV, volume up"); only the command goes to the TV.
// The TV address is optional: when the TV cannot be reached (another Wi-Fi, a new address from the router) the remote
// finds it again on the local network (Finder below) and remembers the new address.
// Arrows, OK and Back press real keys over the TV's network debugging when the TV allows this Mac, so they work in
// every app (Netflix too); "Type on the TV" sends text the same way (AdbKeys below).
// Build on the Mac: ./build-mac.sh   (needs only the Xcode Command Line Tools: xcode-select --install)
import SwiftUI
import AppKit
import Speech
import AVFoundation
import CryptoKit
import Darwin

// MARK: - Settings (TV address and key), kept in UserDefaults

final class Settings: ObservableObject {
    @Published var host: String { didSet { UserDefaults.standard.set(host, forKey: "host") } }
    @Published var key: String { didSet { UserDefaults.standard.set(key, forKey: "key") } }
    init() {
        host = UserDefaults.standard.string(forKey: "host") ?? ""
        key = UserDefaults.standard.string(forKey: "key") ?? ""
    }
    var configured: Bool { !key.isEmpty }   // no address: Finder looks for the TV
}

/// The name on the remote ("brand" setting, for a renamed install); "Chill TV" by default.
let brand = UserDefaults.standard.string(forKey: "brand") ?? "Chill TV"

// MARK: - Finding the TV on the local network

/// Broadcasts "CHILLTV-FIND <id>" to UDP 8766; only the TV with this pairing code answers. <id> is a one-way digest of
/// the code (the TV's TvFinder.findId), so the code itself never goes over the network.
enum Finder {
    static func findId(_ code: String) -> String {
        let norm = code.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "-", with: "")
            .replacingOccurrences(of: " ", with: "").uppercased()
        let digest = SHA256.hash(data: Data(("chilltv-find:" + norm).utf8))
        return digest.prefix(8).map { String(format: "%02x", $0) }.joined()
    }

    /// The TV's address, or nil when no TV answered within about two seconds. Call it off the main thread.
    static func find(_ code: String) -> String? {
        let id = findId(code)
        let fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard fd >= 0 else { return nil }
        defer { close(fd) }
        var yes: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_BROADCAST, &yes, socklen_t(MemoryLayout<Int32>.size))
        var wait = timeval(tv_sec: 0, tv_usec: 300_000)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &wait, socklen_t(MemoryLayout<timeval>.size))
        let ask = Array("CHILLTV-FIND \(id)".utf8)
        for _ in 0..<3 {
            for b in broadcasts() {
                var to = sockaddr_in()
                to.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
                to.sin_family = sa_family_t(AF_INET)
                to.sin_port = in_port_t(8766).bigEndian
                to.sin_addr = b
                _ = withUnsafePointer(to: &to) {
                    $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { sendto(fd, ask, ask.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
                }
            }
            let until = Date().addingTimeInterval(0.7)
            while Date() < until {
                var buf = [UInt8](repeating: 0, count: 128)
                var from = sockaddr_in()
                var len = socklen_t(MemoryLayout<sockaddr_in>.size)
                let n = withUnsafeMutablePointer(to: &from) {
                    $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { recvfrom(fd, &buf, buf.count, 0, $0, &len) }
                }
                if n <= 0 { continue }
                let msg = String(decoding: buf[0..<n], as: UTF8.self)
                if msg.hasPrefix("CHILLTV-HERE \(id)") { return String(cString: inet_ntoa(from.sin_addr)) }
            }
        }
        return nil
    }

    /// 255.255.255.255 plus each network's own broadcast address (some routers only pass the second).
    private static func broadcasts() -> [in_addr] {
        var list = [in_addr(s_addr: INADDR_BROADCAST)]
        var ifs: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifs) == 0, let first = ifs else { return list }
        defer { freeifaddrs(ifs) }
        for p in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let i = p.pointee
            guard (i.ifa_flags & UInt32(IFF_UP)) != 0, (i.ifa_flags & UInt32(IFF_BROADCAST)) != 0,
                  let a = i.ifa_dstaddr, a.pointee.sa_family == sa_family_t(AF_INET) else { continue }
            let b = a.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr }
            if !list.contains(where: { $0.s_addr == b.s_addr }) { list.append(b) }
        }
        return list
    }
}

// MARK: - Real key presses over adb

/// The launcher can only press keys inside its own screen. Over the TV's network debugging (the connection Computer
/// mode uses) the remote presses real keys in whichever app is in front, Netflix included. One `adb shell` stays open
/// while the remote is open, so a key takes about 0.1 s; it closes when the remote folds up. Without adb, or before the
/// TV has allowed this Mac, keys go to the launcher as before.
final class AdbKeys {
    static let keycodes = ["up": 19, "down": 20, "left": 21, "right": 22, "ok": 23, "back": 4]
    private let queue = DispatchQueue(label: "chilltv.adb")
    private var shell: Process?
    private var pipe: FileHandle?
    private var target = ""
    private var retryAfter = Date.distantPast   // after a failed connect, use the launcher a while instead of waiting on every key

    /// adb from the "adbPath" setting, else Homebrew's. "adbHome" (optional) is the HOME that holds adb's key, so
    /// several Mac accounts can share one key the TV has already allowed.
    private static var adb: String? {
        [UserDefaults.standard.string(forKey: "adbPath"), "/opt/homebrew/bin/adb", "/usr/local/bin/adb"]
            .compactMap { $0 }.first { FileManager.default.isExecutableFile(atPath: $0) }
    }

    private static func process(_ args: [String]) -> Process? {
        guard let adb = adb else { return nil }
        let p = Process()
        p.executableURL = URL(fileURLWithPath: adb)
        p.arguments = args
        if let home = UserDefaults.standard.string(forKey: "adbHome") {
            var env = ProcessInfo.processInfo.environment; env["HOME"] = home; p.environment = env
        }
        p.standardOutput = FileHandle.nullDevice; p.standardError = FileHandle.nullDevice
        return p
    }

    /// Runs adb and returns what it printed, or nil when it can't run or takes longer than `timeout` seconds.
    private static func run(_ args: [String], output: Bool = true, timeout: TimeInterval = 6) -> String? {
        guard let p = process(args) else { return nil }
        let out = Pipe()
        if output { p.standardOutput = out; p.standardError = out }
        let done = DispatchSemaphore(value: 0)
        p.terminationHandler = { _ in done.signal() }
        do { try p.run() } catch { return nil }
        if done.wait(timeout: .now() + timeout) == .timedOut { p.terminate(); return nil }
        return output ? String(decoding: out.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self) : ""
    }

    /// Opens the shell to `host` unless it is already open. Runs on `queue`.
    private func open(_ host: String) -> Bool {
        if let s = shell, s.isRunning, target == host { return true }
        close()
        guard Date() >= retryAfter else { return false }
        let serial = "\(host):5555"
        // the adb server first, without capturing output: it keeps running and would hold a pipe open
        guard AdbKeys.run(["start-server"], output: false) != nil else { retryAfter = Date().addingTimeInterval(20); return false }
        _ = AdbKeys.run(["connect", serial])
        guard AdbKeys.run(["-s", serial, "get-state"])?.trimmingCharacters(in: .whitespacesAndNewlines) == "device",
              let p = AdbKeys.process(["-s", serial, "shell"]) else { retryAfter = Date().addingTimeInterval(20); return false }
        let input = Pipe()
        p.standardInput = input
        do { try p.run() } catch { retryAfter = Date().addingTimeInterval(20); return false }
        shell = p; pipe = input.fileHandleForWriting; target = host
        return true
    }

    private func close() {
        try? pipe?.close(); pipe = nil
        if let s = shell, s.isRunning { s.terminate() }
        shell = nil
    }

    /// Sends one shell command; a dropped connection gets one fresh shell.
    private func write(_ line: String, host: String) -> Bool {
        for _ in 0..<2 {
            guard open(host), let pipe = pipe else { return false }
            do { try pipe.write(contentsOf: Data((line + "\n").utf8)); return true } catch { close() }
        }
        return false
    }

    /// Presses `name` (up, down, left, right, ok, back); `done(false)` means adb couldn't, so the caller uses the launcher.
    func press(_ name: String, host: String, done: @escaping (Bool) -> Void) {
        guard let code = AdbKeys.keycodes[name] else { done(false); return }
        queue.async {
            let ok = self.write("input keyevent \(code)", host: host)
            DispatchQueue.main.async { done(ok) }
        }
    }

    /// Types `text` into the TV's focused text field, then presses Enter if asked. Plain ASCII only (`input text`).
    func type(_ text: String, enter: Bool, host: String, done: @escaping (Bool) -> Void) {
        let arg = text.replacingOccurrences(of: "'", with: "'\\''").replacingOccurrences(of: " ", with: "%s")
        queue.async {
            var ok = self.write("input text '\(arg)'", host: host)
            if ok && enter { ok = self.write("input keyevent 66", host: host) }
            DispatchQueue.main.async { done(ok) }
        }
    }

    /// Closes the shell (the remote folded up), so nothing stays connected in the background.
    func stop() { queue.async { self.close(); self.retryAfter = .distantPast } }
}

// MARK: - Talking to the TV

struct TVStatus: Decodable { let ok: Bool; let volume: Int?; let max: Int?; let muted: Bool?; let music: Bool?; let note: String?; let kind: String? }

final class TV: ObservableObject {
    @Published var online: Bool? = nil          // nil = not checked yet (collapsed: never checked)
    @Published var volume = 0
    @Published var maxVolume = 15
    @Published var muted = false
    @Published var music = true
    @Published var note = ""
    @Published var isHome = false            // Chill TV Home: no reel, idle or music
    let settings: Settings
    let adb = AdbKeys()
    init(settings: Settings) { self.settings = settings }

    func send(_ path: String, searched: Bool = false, launcher: Bool = false) {
        guard settings.configured else { note = "Right-click the circle → Set TV address"; return }
        if settings.host.isEmpty { find(then: path); return }
        // Arrows, OK and Back: a real key press in whichever app is in front, when the TV allows this Mac (AdbKeys).
        let keyPrefix = "/api/key?code="
        if !launcher, path.hasPrefix(keyPrefix) {
            adb.press(String(path.dropFirst(keyPrefix.count)), host: settings.host) { ok in
                if ok { self.online = true } else { self.send(path, searched: searched, launcher: true) }
            }
            return
        }
        let sep = path.contains("?") ? "&" : "?"
        let k = settings.key.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
        guard let url = URL(string: "http://\(settings.host):8765\(path)\(sep)k=\(k)") else { return }
        var req = URLRequest(url: url); req.timeoutInterval = 6; req.cachePolicy = .reloadIgnoringLocalCacheData
        URLSession.shared.dataTask(with: req) { data, resp, err in
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
            let st = data.flatMap { try? JSONDecoder().decode(TVStatus.self, from: $0) }
            DispatchQueue.main.async {
                if err != nil || code == 0 {
                    // Not reachable at the saved address: the TV may have a new one. Look for it once.
                    if !searched { self.find(then: path); return }
                    self.online = false; self.note = "TV not reachable (is it on?)"; return
                }
                if code == 403 { self.online = false; self.note = "Wrong pairing code (see Chill TV on the TV)"; return }
                self.online = true
                self.note = st?.note ?? ""
                if let v = st?.volume { self.volume = v }
                if let m = st?.max { self.maxVolume = Swift.max(1, m) }
                if let m = st?.muted { self.muted = m }
                if let m = st?.music { self.music = m }
                if st?.volume != nil { self.isHome = st?.kind == "home" }
                if st?.volume == nil && !path.hasPrefix("/api/status") { self.refresh() }
            }
        }.resume()
    }
    func refresh() { send("/api/status") }

    /// Types into the TV's focused text field (search, sign-in). Needs the same adb access as the keys.
    func type(_ text: String, enter: Bool) {
        guard settings.configured, !settings.host.isEmpty else { note = "Press any remote button first so the remote finds the TV."; return }
        guard !text.isEmpty else { return }
        guard text.unicodeScalars.allSatisfy({ $0.isASCII && $0.value >= 32 }) else {
            note = "Typing works with plain letters, numbers and symbols only."; return
        }
        adb.type(text, enter: enter, host: settings.host) { ok in
            self.note = ok ? "Typed on the TV."
                : "Typing needs adb on this Mac and network debugging on the TV (choose \"Always allow\" on the TV)."
        }
    }

    /// Looks for the TV on the local network, saves its new address and repeats the request once.
    private func find(then path: String) {
        let key = settings.key, old = settings.host
        DispatchQueue.global(qos: .userInitiated).async {
            let found = Finder.find(key)
            DispatchQueue.main.async {
                guard let found = found, found != old else {
                    self.online = false
                    self.note = found == nil ? "TV not found. Is it on, and is this Mac on the same Wi-Fi?" : "TV not reachable (is it on?)"
                    return
                }
                self.settings.host = found
                self.send(path, searched: true)
            }
        }
    }

    /// Computer mode: the TV in a window on the Mac (scrcpy), controlled by the Mac keyboard and mouse.
    /// Closing that window ends it; nothing keeps running afterwards.
    @Published var computerOn = false
    private var computerProc: Process?
    /// Stops Computer mode from the remote (the TV window closes; the remote stays).
    func stopComputerMode() { computerProc?.terminate(); computerProc = nil }
    func computerMode() {
        if computerOn { stopComputerMode(); return }
        guard settings.configured else { return }
        guard !settings.host.isEmpty else { note = "Press any remote button first so the remote finds the TV."; return }
        let script = Bundle.main.path(forResource: "tv-computer-mode", ofType: "sh") ?? ""
        guard FileManager.default.fileExists(atPath: script) else { note = "Computer mode script missing from the app"; return }
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/bin/sh")
        p.arguments = [script, settings.host]
        p.terminationHandler = { proc in
            DispatchQueue.main.async {
                self.computerOn = false
                switch proc.terminationStatus {
                case 2: self.note = "On the TV, choose \"Always allow\" with the TV remote, then press Computer mode again."
                case 3: self.note = "TV not reachable for Computer mode (is it on?)"
                default: self.note = ""
                }
            }
        }
        do { try p.run(); computerProc = p; computerOn = true; note = "Computer mode on. Stop it here, or close the TV window. Netflix and other video apps show black: use their Sign in with code." }
        catch { note = "Could not start Computer mode" }
    }
}

// MARK: - Voice: "TV" + a command, mapped to the same calls the keys make (the Windows remote uses the same table)

final class Voice: ObservableObject {
    @Published var on = UserDefaults.standard.bool(forKey: "voice") { didSet { UserDefaults.standard.set(on, forKey: "voice") } }
    let tv: TV
    private let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "en-US"))
    private let engine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var pending: DispatchWorkItem?
    init(tv: TV) { self.tv = tv }

    static let wake = ["hey tv", "chill tv", "tv"]
    static let commands: [String: String] = {
        var c: [String: String] = [:]
        func add(_ path: String, _ phrases: [String]) { for p in phrases { c[p] = path } }
        add("/api/open?app=nf", ["open netflix", "netflix"])
        add("/api/open?app=prime", ["open prime video", "open prime", "prime video"])
        add("/api/open?app=hbo", ["open hbo max", "open hbo", "open max", "hbo max"])
        add("/api/open?app=dis", ["open disney plus", "open disney", "disney plus"])
        add("/api/open?app=play", ["open google play", "open play store", "open the play store"])
        add("/api/home", ["home", "go home"])
        add("/api/key?code=back", ["back", "go back"])
        add("/api/apps", ["apps", "all apps", "show apps"])
        add("/api/media?c=playpause", ["pause", "play", "resume"])
        add("/api/media?c=ff", ["fast forward", "forward", "skip ahead"])
        add("/api/media?c=rew", ["rewind", "go back ten seconds"])
        add("/api/media?c=next", ["next", "next episode"])
        add("/api/media?c=prev", ["previous"])
        add("/api/vol?d=up", ["volume up", "louder", "turn it up"])
        add("/api/vol?d=down", ["volume down", "quieter", "softer", "turn it down"])
        add("/api/vol?d=mute", ["mute", "unmute"])
        add("/api/key?code=up", ["up"]); add("/api/key?code=down", ["down"])
        add("/api/key?code=left", ["left"]); add("/api/key?code=right", ["right"])
        add("/api/key?code=ok", ["ok", "okay", "select", "enter"])
        add("/api/reel", ["reel", "show reel"])
        add("/api/showcase", ["idle", "showcase"])
        add("/api/music?on=1", ["music on"]); add("/api/music?on=0", ["music off"])
        add("voice-off", ["stop listening"])
        return c
    }()

    /// "TV, open Disney+." → "tv open disney plus"
    static func normalize(_ s: String) -> String {
        var t = s.lowercased().replacingOccurrences(of: "+", with: " plus ").replacingOccurrences(of: "t.v.", with: "tv")
        t = String(t.unicodeScalars.map { CharacterSet.alphanumerics.contains($0) ? Character($0) : " " })
        return t.split(separator: " ").joined(separator: " ")
    }

    /// The command after the last wake word, or nil.
    static func path(for heard: String) -> String? {
        let t = " " + normalize(heard)
        for w in wake {
            if let r = t.range(of: " " + w + " ", options: .backwards) {
                return commands[String(t[r.upperBound...]).trimmingCharacters(in: .whitespaces)]
            }
        }
        return nil
    }

    func toggle() {
        if on { stop(); on = false; tv.note = "Voice off."; return }
        guard tv.settings.configured else { tv.note = "Right-click the circle → Set TV address"; return }
        start()
    }

    func start() {
        SFSpeechRecognizer.requestAuthorization { status in
            DispatchQueue.main.async {
                guard status == .authorized else {
                    self.on = false
                    self.tv.note = "Allow Speech Recognition for Chill TV Remote in System Settings > Privacy & Security."
                    return
                }
                AVCaptureDevice.requestAccess(for: .audio) { granted in
                    DispatchQueue.main.async {
                        guard granted else {
                            self.on = false
                            self.tv.note = "Allow the Microphone for Chill TV Remote in System Settings > Privacy & Security."
                            return
                        }
                        self.listen()
                    }
                }
            }
        }
    }

    private func listen() {
        guard let recognizer = recognizer, recognizer.isAvailable else {
            on = false; tv.note = "Speech recognition is not available on this Mac right now."; return
        }
        let input = engine.inputNode
        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 1024, format: input.outputFormat(forBus: 0)) { [weak self] buffer, _ in
            self?.request?.append(buffer)
        }
        engine.prepare()
        do { try engine.start() } catch { on = false; tv.note = "The microphone could not start."; return }
        on = true
        tv.note = "Listening. Say \"TV\" and a command: \"TV, open Netflix\", \"TV, pause\", \"TV, volume up\"."
        newTask()
    }

    /// One recognition task at a time; a new one after each command and whenever Apple ends one (about a minute).
    private func newTask() {
        task?.cancel(); request?.endAudio()
        guard on, let recognizer = recognizer else { return }
        let r = SFSpeechAudioBufferRecognitionRequest()
        r.shouldReportPartialResults = true
        r.taskHint = .search
        r.contextualStrings = Voice.wake + Array(Voice.commands.keys)
        if recognizer.supportsOnDeviceRecognition { r.requiresOnDeviceRecognition = true }
        request = r
        task = recognizer.recognitionTask(with: r) { [weak self] result, error in
            DispatchQueue.main.async {
                // a cancelled task still reports once; only the current request counts
                guard let self = self, self.on, self.request === r else { return }
                if let text = result?.bestTranscription.formattedString { self.heard(text) }
                if error != nil || result?.isFinal == true { self.newTask() }
            }
        }
    }

    /// Acts after a short pause, so "TV, go back ten seconds" is not taken as "TV, go back" halfway through.
    private func heard(_ text: String) {
        pending?.cancel()
        guard let path = Voice.path(for: text) else { return }
        let w = DispatchWorkItem { [weak self] in
            guard let self = self, self.on else { return }
            if path == "voice-off" { self.toggle(); return }
            self.tv.note = "Heard: \"\(text)\""
            self.tv.send(path)
            self.newTask()
        }
        pending = w
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.7, execute: w)
    }

    func stop() {
        pending?.cancel(); pending = nil
        task?.cancel(); task = nil
        request?.endAudio(); request = nil
        if engine.isRunning { engine.stop() }
        engine.inputNode.removeTap(onBus: 0)
    }
}

// MARK: - Look

let teal = Color(red: 0.25, green: 0.76, blue: 0.71)
let gold = Color(red: 0.91, green: 0.79, blue: 0.47)
let panelBG = Color(red: 0.08, green: 0.13, blue: 0.14).opacity(0.94)

struct Key: View {
    let label: String; var wide = false; var tint: Color? = nil; let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(label).font(.system(size: 13, weight: .semibold)).frame(maxWidth: .infinity, minHeight: 32)
                .foregroundColor(tint == nil ? .white : .black)
                .background(RoundedRectangle(cornerRadius: 10).fill(tint ?? Color.white.opacity(0.08)))
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color.white.opacity(0.12)))
        }.buttonStyle(.plain)
    }
}

struct Pad: View {
    @ObservedObject var tv: TV
    var body: some View {
        ZStack {
            Circle().fill(Color.white.opacity(0.07)).overlay(Circle().stroke(Color.white.opacity(0.14)))
            VStack(spacing: 0) {
                arrow("chevron.up", "up"); Spacer()
                HStack { arrow("chevron.left", "left"); Spacer()
                    Button { tv.send("/api/key?code=ok") } label: {
                        Text("OK").font(.system(size: 13, weight: .bold)).frame(width: 46, height: 46)
                            .background(Circle().fill(Color.white.opacity(0.14)))
                    }.buttonStyle(.plain)
                    Spacer(); arrow("chevron.right", "right") }
                Spacer(); arrow("chevron.down", "down")
            }.padding(8)
        }.frame(width: 150, height: 150).foregroundColor(.white)
    }
    func arrow(_ icon: String, _ code: String) -> some View {
        Button { tv.send("/api/key?code=\(code)") } label: {
            Image(systemName: icon).font(.system(size: 15, weight: .bold)).frame(width: 34, height: 30).contentShape(Rectangle())
        }.buttonStyle(.plain)
    }
}

struct RemoteView: View {
    @ObservedObject var tv: TV
    @ObservedObject var voice: Voice
    let typeText: () -> Void
    var body: some View {
        VStack(spacing: 10) {
            HStack(spacing: 8) {
                Circle().fill(tv.online == true ? teal : tv.online == false ? .red : .gray).frame(width: 9, height: 9)
                Text(brand.uppercased()).font(.system(size: 12, weight: .bold)).kerning(1.5)
                Spacer()
            }.foregroundColor(.white)
            Pad(tv: tv)
            HStack(spacing: 6) {
                Key(label: "Apps") { tv.send("/api/apps") }
                Key(label: "◀ Back") { tv.send("/api/key?code=back") }
                Key(label: "⌂ Home") { tv.send("/api/home") }
            }
            HStack(spacing: 6) {
                Key(label: "⏪") { tv.send("/api/media?c=rew") }
                Key(label: "⏯") { tv.send("/api/media?c=playpause") }
                Key(label: "⏩") { tv.send("/api/media?c=ff") }
            }
            HStack(spacing: 6) {
                Key(label: "NETFLIX", tint: Color(red: 0.9, green: 0.1, blue: 0.14)) { tv.send("/api/open?app=nf") }
                Key(label: "prime video", tint: Color(red: 0.0, green: 0.66, blue: 0.88)) { tv.send("/api/open?app=prime") }
            }
            HStack(spacing: 6) {
                Key(label: "HBO Max") { tv.send("/api/open?app=hbo") }
                Key(label: "Disney+") { tv.send("/api/open?app=dis") }
                Key(label: "Play") { tv.send("/api/open?app=play") }
            }
            Group {   // one view: Swift 5.7 (macOS 12 tools) takes at most 10 per stack
                Key(label: "⌨ Type on the TV", action: typeText)
                Key(label: tv.computerOn ? "⏹ Stop computer mode" : "🖥 Computer mode (keyboard & mouse)", tint: tv.computerOn ? gold : nil) { tv.computerMode() }
                Key(label: voice.on ? "🎙 Listening: say \"TV …\"" : "🎙 Voice (Mac mic)", tint: voice.on ? gold : nil) { voice.toggle() }
            }
            if !tv.isHome { HStack(spacing: 6) {
                Key(label: "▶ Reel") { tv.send("/api/reel") }
                Key(label: "Idle") { tv.send("/api/showcase") }
                Key(label: tv.music ? "♪ On" : "♪ Off") { tv.send("/api/music?on=\(tv.music ? 0 : 1)") }
            } }
            HStack(spacing: 8) {
                Key(label: "−") { tv.send("/api/vol?d=down") }.frame(width: 40)
                GeometryReader { g in
                    ZStack(alignment: .leading) {
                        Capsule().fill(Color.white.opacity(0.1))
                        Capsule().fill(tv.muted ? Color.gray : gold)
                            .frame(width: g.size.width * CGFloat(tv.volume) / CGFloat(tv.maxVolume))
                    }
                }.frame(height: 7)
                Key(label: "+") { tv.send("/api/vol?d=up") }.frame(width: 40)
                Key(label: tv.muted ? "🔇" : "🔈") { tv.send("/api/vol?d=mute") }.frame(width: 40)
            }
            if !tv.note.isEmpty { Text(tv.note).font(.system(size: 11)).foregroundColor(.white.opacity(0.65)).fixedSize(horizontal: false, vertical: true) }
        }
        .padding(14).frame(width: 240)
        .background(RoundedRectangle(cornerRadius: 22).fill(panelBG))
        .overlay(RoundedRectangle(cornerRadius: 22).stroke(Color.white.opacity(0.12)))
    }
}

struct DotView: View {
    @ObservedObject var tv: TV
    @ObservedObject var voice: Voice
    var body: some View {
        ZStack {
            Circle().fill(panelBG)
            // gold ring: Voice is listening, even folded up
            Circle().stroke(tv.online == false ? Color.red : voice.on ? gold : teal, lineWidth: 3).padding(2)
            Text(String(brand.prefix(1))).font(.system(size: 20, weight: .semibold, design: .serif)).foregroundColor(gold)
        }.frame(width: 48, height: 48)
    }
}

// MARK: - Floating window: circle <-> remote

final class Dock: NSObject, NSWindowDelegate {
    let settings = Settings()
    lazy var tv = TV(settings: settings)
    lazy var voice = Voice(tv: tv)
    var panel: NSPanel!
    var expanded = false
    var idleTimer: Timer?
    var outsideMonitor: Any?

    func start() {
        panel = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 48, height: 48),
                        styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        panel.isFloatingPanel = true; panel.level = .floating
        panel.backgroundColor = .clear; panel.isOpaque = false; panel.hasShadow = true
        panel.isMovableByWindowBackground = true
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        if let s = NSScreen.main?.visibleFrame {
            let x = UserDefaults.standard.object(forKey: "x") as? Double ?? Double(s.maxX - 72)
            let y = UserDefaults.standard.object(forKey: "y") as? Double ?? Double(s.minY + 120)
            panel.setFrameOrigin(NSPoint(x: x, y: y))
        }
        panel.delegate = self
        showDot()
        panel.orderFrontRegardless()
        if !settings.configured { askSettings() }
        else if voice.on { voice.start() }         // Voice was on when the remote last quit
    }

    func windowDidMove(_ n: Notification) {
        // remember where the circle sits (top-left corner of the remote follows it)
        if !expanded { UserDefaults.standard.set(panel.frame.minX, forKey: "x"); UserDefaults.standard.set(panel.frame.minY, forKey: "y") }
    }

    func showDot() {
        let v = NSHostingView(rootView: DotView(tv: tv, voice: voice).onTapGesture { [weak self] in self?.expand() }
            .contextMenu {
                Button("Set TV address and key…") { [weak self] in self?.askSettings() }
                Button("Quit \(brand) Remote") { NSApp.terminate(nil) }
            })
        resize(to: NSSize(width: 48, height: 48), anchorTop: false)
        panel.contentView = v
    }

    func expand() {
        expanded = true
        let v = NSHostingView(rootView: RemoteView(tv: tv, voice: voice,
                                                   typeText: { [weak self] in self?.askText() })
            .onHover { [weak self] _ in self?.poke() })
        let size = v.fittingSize
        resize(to: size, anchorTop: false)
        panel.contentView = v
        tv.refresh()                         // one status check when opened; nothing runs in the background
        poke()
        outsideMonitor = NSEvent.addGlobalMonitorForEvents(matching: [.leftMouseDown, .rightMouseDown]) { [weak self] _ in self?.collapse() }   // a click anywhere else folds it up, Computer mode or not
    }

    /// Collapse after 60 s without use, so the remote goes back to sleep.
    func poke() {
        idleTimer?.invalidate()
        if tv.computerOn { return }   // the remote stays open while Computer mode runs
        idleTimer = Timer.scheduledTimer(withTimeInterval: 60, repeats: false) { [weak self] _ in self?.collapse() }
    }

    func collapse() {
        guard expanded else { return }
        expanded = false
        idleTimer?.invalidate(); idleTimer = nil
        if let m = outsideMonitor { NSEvent.removeMonitor(m); outsideMonitor = nil }
        tv.adb.stop()
        showDot()
    }

    /// Grow/shrink keeping the circle's corner nearest the screen edge in place.
    func resize(to size: NSSize, anchorTop: Bool) {
        let x = UserDefaults.standard.object(forKey: "x") as? Double ?? Double(panel.frame.minX)
        let y = UserDefaults.standard.object(forKey: "y") as? Double ?? Double(panel.frame.minY)
        var origin = NSPoint(x: x, y: y)
        if let s = (panel.screen ?? NSScreen.main)?.visibleFrame {
            if origin.x + size.width > s.maxX { origin.x = x + 48 - size.width }      // open leftwards near the right edge
            if origin.y + size.height > s.maxY { origin.y = y + 48 - size.height }    // open downwards near the top
            origin.x = min(max(origin.x, s.minX), s.maxX - size.width)
            origin.y = min(max(origin.y, s.minY), s.maxY - size.height)
        }
        panel.setFrame(NSRect(origin: origin, size: size), display: true)
    }

    /// Type on the TV: a text box on the Mac; the text goes into the field that has focus on the TV.
    func askText() {
        poke()
        let a = NSAlert()
        a.messageText = "Type on the TV"
        a.informativeText = "Select the text field on the TV first (search, sign-in), then type here. Return types it and presses Enter."
        let field = NSTextField(frame: NSRect(x: 0, y: 0, width: 260, height: 24)); field.placeholderString = "Text for the TV"
        a.accessoryView = field
        a.addButton(withTitle: "Type + Enter"); a.addButton(withTitle: "Type"); a.addButton(withTitle: "Cancel")
        a.window.initialFirstResponder = field
        NSApp.activate(ignoringOtherApps: true)
        let r = a.runModal()
        if r == .alertFirstButtonReturn || r == .alertSecondButtonReturn { tv.type(field.stringValue, enter: r == .alertFirstButtonReturn) }
    }

    func askSettings() {
        let a = NSAlert()
        a.messageText = "\(brand) Remote"
        a.informativeText = "The pairing code shown in Chill TV on the TV. The TV address is optional: the remote finds the TV by itself on the same Wi-Fi."
        let host = NSTextField(frame: NSRect(x: 0, y: 30, width: 260, height: 24)); host.placeholderString = "TV address (optional)"; host.stringValue = settings.host
        let key = NSSecureTextField(frame: NSRect(x: 0, y: 0, width: 260, height: 24)); key.placeholderString = "Pairing code"; key.stringValue = settings.key
        let box = NSView(frame: NSRect(x: 0, y: 0, width: 260, height: 54)); box.addSubview(host); box.addSubview(key)
        a.accessoryView = box
        a.addButton(withTitle: "Save"); a.addButton(withTitle: "Cancel")
        NSApp.activate(ignoringOtherApps: true)
        if a.runModal() == .alertFirstButtonReturn {
            settings.host = host.stringValue.trimmingCharacters(in: .whitespaces)
            settings.key = key.stringValue.trimmingCharacters(in: .whitespaces)
        }
    }
}

@main
struct ChillRemoteApp {
    static let dock = Dock()
    static func main() {
        let app = NSApplication.shared
        app.setActivationPolicy(.accessory)      // no Dock icon or menu bar: just the floating circle
        signal(SIGPIPE, SIG_IGN)                 // a dropped adb shell must not end the remote (AdbKeys reconnects)
        dock.start()
        app.run()
    }
}
