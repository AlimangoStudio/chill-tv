// Chill TV Remote: a floating dock for the Chill TV Showcase launcher.
// A 48 px circle that opens into a remote laid out like the Mi Box remote. It talks to the Chill TV Showcase launcher's
// remote server (port 8765) on your network; works with both Chill TV Home and Showcase. Collapsed it does nothing at all: no timers, no network.
// Build on the Mac: ./build-mac.sh   (needs only the Xcode Command Line Tools: xcode-select --install)
import SwiftUI
import AppKit

// MARK: - Settings (TV address and key), kept in UserDefaults

final class Settings: ObservableObject {
    @Published var host: String { didSet { UserDefaults.standard.set(host, forKey: "host") } }
    @Published var key: String { didSet { UserDefaults.standard.set(key, forKey: "key") } }
    init() {
        host = UserDefaults.standard.string(forKey: "host") ?? ""
        key = UserDefaults.standard.string(forKey: "key") ?? ""
    }
    var configured: Bool { !host.isEmpty && !key.isEmpty }
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
    init(settings: Settings) { self.settings = settings }

    func send(_ path: String) {
        guard settings.configured else { note = "Right-click the circle → Set TV address"; return }
        let sep = path.contains("?") ? "&" : "?"
        let k = settings.key.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
        guard let url = URL(string: "http://\(settings.host):8765\(path)\(sep)k=\(k)") else { return }
        var req = URLRequest(url: url); req.timeoutInterval = 6; req.cachePolicy = .reloadIgnoringLocalCacheData
        URLSession.shared.dataTask(with: req) { data, resp, err in
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
            let st = data.flatMap { try? JSONDecoder().decode(TVStatus.self, from: $0) }
            DispatchQueue.main.async {
                if err != nil || code == 0 { self.online = false; self.note = "TV not reachable (is it on?)"; return }
                if code == 403 { self.online = false; self.note = "Wrong key"; return }
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

    /// Computer mode: the TV in a window on the Mac (scrcpy), controlled by the Mac keyboard and mouse.
    /// Closing that window ends it; nothing keeps running afterwards.
    @Published var computerOn = false
    private var computerProc: Process?
    /// Stops Computer mode from the remote (the TV window closes; the remote stays).
    func stopComputerMode() { computerProc?.terminate(); computerProc = nil }
    func computerMode() {
        if computerOn { stopComputerMode(); return }
        guard settings.configured else { return }
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
    let collapse: () -> Void
    var body: some View {
        VStack(spacing: 10) {
            HStack(spacing: 8) {
                Circle().fill(tv.online == true ? teal : tv.online == false ? .red : .gray).frame(width: 9, height: 9)
                Text("CHILL TV").font(.system(size: 12, weight: .bold)).kerning(1.5)
                Spacer()
                Button(action: collapse) { Image(systemName: "minus").frame(width: 22, height: 22) }.buttonStyle(.plain)
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
            Key(label: tv.computerOn ? "⏹ Stop computer mode" : "🖥 Computer mode (keyboard & mouse)", tint: tv.computerOn ? gold : nil) { tv.computerMode() }
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
    var body: some View {
        ZStack {
            Circle().fill(panelBG)
            Circle().stroke(tv.online == false ? Color.red : teal, lineWidth: 3).padding(2)
            Text("C").font(.system(size: 20, weight: .semibold, design: .serif)).foregroundColor(gold)
        }.frame(width: 48, height: 48)
    }
}

// MARK: - Floating window: circle <-> remote

final class Dock: NSObject, NSWindowDelegate {
    let settings = Settings()
    lazy var tv = TV(settings: settings)
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
    }

    func windowDidMove(_ n: Notification) {
        // remember where the circle sits (top-left corner of the remote follows it)
        if !expanded { UserDefaults.standard.set(panel.frame.minX, forKey: "x"); UserDefaults.standard.set(panel.frame.minY, forKey: "y") }
    }

    func showDot() {
        let v = NSHostingView(rootView: DotView(tv: tv).onTapGesture { [weak self] in self?.expand() }
            .contextMenu {
                Button("Set TV address and key…") { [weak self] in self?.askSettings() }
                Button("Quit Chill TV Remote") { NSApp.terminate(nil) }
            })
        resize(to: NSSize(width: 48, height: 48), anchorTop: false)
        panel.contentView = v
    }

    func expand() {
        expanded = true
        let v = NSHostingView(rootView: RemoteView(tv: tv, collapse: { [weak self] in self?.collapse() })
            .onHover { [weak self] _ in self?.poke() })
        let size = v.fittingSize
        resize(to: size, anchorTop: false)
        panel.contentView = v
        tv.refresh()                         // one status check when opened; nothing runs in the background
        poke()
        outsideMonitor = NSEvent.addGlobalMonitorForEvents(matching: [.leftMouseDown, .rightMouseDown]) { [weak self] _ in if self?.tv.computerOn != true { self?.collapse() } }
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

    func askSettings() {
        let a = NSAlert()
        a.messageText = "Chill TV Remote"
        a.informativeText = "TV address on your network (for example 192.168.1.50) and the remote key from showcase/remote-key.txt."
        let host = NSTextField(frame: NSRect(x: 0, y: 30, width: 260, height: 24)); host.placeholderString = "TV address"; host.stringValue = settings.host
        let key = NSSecureTextField(frame: NSRect(x: 0, y: 0, width: 260, height: 24)); key.placeholderString = "Remote key"; key.stringValue = settings.key
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
        dock.start()
        app.run()
    }
}
