// Chill TV Remote for Windows: the same floating remote as the Mac one. A 48 px circle that stays on top and
// opens into a remote laid out like the TV's own remote. It talks to the Chill TV launcher's (Home or Showcase) remote
// server (port 8765) on your network. Collapsed it does nothing at all (no timers, no network) unless Voice is on.
// Voice: the PC's microphone and Windows' own offline speech recognizer. Say "TV" then a command ("TV, open
// Netflix", "TV, pause", "TV, volume up"); speech never leaves this PC, only the command goes to the TV.
// Settings (TV address and key) are kept for this Windows user only, the key encrypted by Windows (DPAPI).
// The address is optional: when the TV cannot be reached (another Wi-Fi, a new address from the router) the remote
// finds it again on the local network (Finder below) and remembers the new address.
// Build: build.cmd (uses the C# compiler that ships with Windows; nothing to install).
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Globalization;
using System.Security.Cryptography;
using System.Speech.Recognition;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows.Forms;

namespace ChillTV
{
    static class Settings
    {
        static readonly string Dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "ChillTV");
        static readonly string FilePath = Path.Combine(Dir, "remote.dat");
        public static string Host = "";
        public static string Key = "";
        public static int X = -1, Y = -1;
        public static bool Voice;
        public static bool Configured { get { return Key.Length > 0; } }   // no address: Finder looks for the TV

        public static void Load()
        {
            try
            {
                if (!File.Exists(FilePath)) return;
                byte[] raw = ProtectedData.Unprotect(File.ReadAllBytes(FilePath), null, DataProtectionScope.CurrentUser);
                string[] p = Encoding.UTF8.GetString(raw).Split('\n');
                Host = p[0].Trim();
                Key = p.Length > 1 ? p[1].Trim() : "";
                if (p.Length > 3) { int.TryParse(p[2], out X); int.TryParse(p[3], out Y); }
                Voice = p.Length > 4 && p[4].Trim() == "voice";
            }
            catch { Host = ""; Key = ""; }
        }

        public static void Save()
        {
            try
            {
                Directory.CreateDirectory(Dir);
                byte[] raw = Encoding.UTF8.GetBytes(Host + "\n" + Key + "\n" + X + "\n" + Y + "\n" + (Voice ? "voice" : ""));
                File.WriteAllBytes(FilePath, ProtectedData.Protect(raw, null, DataProtectionScope.CurrentUser));
            }
            catch { }
        }
    }

    // Finds the TV on the local network: broadcast "CHILLTV-FIND <id>" to UDP 8766; only the TV with this pairing code
    // answers. <id> is a one-way digest of the code (the TV's TvFinder.findId), so the code never goes over the network.
    static class Finder
    {
        public static string FindId(string code)
        {
            string norm = code.Trim().Replace("-", "").Replace(" ", "").ToUpperInvariant();
            using (SHA256 sha = SHA256.Create())
            {
                byte[] d = sha.ComputeHash(Encoding.UTF8.GetBytes("chilltv-find:" + norm));
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < 8; i++) b.Append(d[i].ToString("x2"));
                return b.ToString();
            }
        }

        // The TV's address, or null when no TV answered within about two seconds.
        public static string Find(string code)
        {
            string id = FindId(code);
            byte[] ask = Encoding.ASCII.GetBytes("CHILLTV-FIND " + id);
            try
            {
                using (UdpClient u = new UdpClient(0))
                {
                    u.EnableBroadcast = true;
                    u.Client.ReceiveTimeout = 700;
                    for (int round = 0; round < 3; round++)
                    {
                        foreach (IPAddress b in Broadcasts()) { try { u.Send(ask, ask.Length, new IPEndPoint(b, 8766)); } catch { } }
                        DateTime until = DateTime.UtcNow.AddMilliseconds(700);
                        while (DateTime.UtcNow < until)
                        {
                            IPEndPoint from = new IPEndPoint(IPAddress.Any, 0);
                            byte[] got;
                            try { got = u.Receive(ref from); } catch (SocketException) { break; }
                            if (Encoding.ASCII.GetString(got).StartsWith("CHILLTV-HERE " + id)) return from.Address.ToString();
                        }
                    }
                }
            }
            catch { }
            return null;
        }

        // 255.255.255.255 plus each network's own broadcast address (some routers only pass the second).
        static List<IPAddress> Broadcasts()
        {
            List<IPAddress> list = new List<IPAddress> { IPAddress.Broadcast };
            foreach (NetworkInterface n in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (n.OperationalStatus != OperationalStatus.Up || n.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
                foreach (UnicastIPAddressInformation a in n.GetIPProperties().UnicastAddresses)
                {
                    if (a.Address.AddressFamily != AddressFamily.InterNetwork || a.IPv4Mask == null) continue;
                    byte[] ip = a.Address.GetAddressBytes(), mask = a.IPv4Mask.GetAddressBytes();
                    for (int i = 0; i < 4; i++) ip[i] = (byte)(ip[i] | ~mask[i]);
                    IPAddress b = new IPAddress(ip);
                    if (!list.Contains(b)) list.Add(b);
                }
            }
            return list;
        }
    }

    class Key
    {
        public RectangleF R;
        public string Label;
        public Color? Tint;
        public bool Round;
        public Action Do;
        public Key(RectangleF r, string label, Color? tint, Action act) { R = r; Label = label; Tint = tint; Do = act; }
    }

    class RemoteForm : Form
    {
        static readonly Color Teal = Color.FromArgb(64, 194, 181);
        static readonly Color Gold = Color.FromArgb(232, 201, 120);
        static readonly Color PanelBG = Color.FromArgb(20, 33, 36);
        const int Dot = 48, PanelW = 260;

        bool expanded;
        bool? online;
        string note = "";
        int volume = 0, maxVolume = 15;
        bool muted, music = true;
        bool homeKind;   // connected to Chill TV Home
        Process computer;
        readonly List<Key> keys = new List<Key>();
        Key hot;
        Point down; bool dragged;
        RectangleF volBar;
        int panelH = 560;
        SpeechRecognitionEngine ear;   // set only while Voice is on

        // ---------- voice: "TV" + one of these, mapped to the same calls the keys make ----------
        static readonly string[] Wake = { "tv", "chill tv", "hey tv" };
        static Dictionary<string, string> VoiceCommands()
        {
            Dictionary<string, string> c = new Dictionary<string, string>();
            Action<string, string[]> add = delegate (string path, string[] phrases) { foreach (string p in phrases) c[p] = path; };
            add("/api/open?app=nf", new[] { "open netflix", "netflix" });
            add("/api/open?app=prime", new[] { "open prime video", "open prime", "prime video" });
            add("/api/open?app=hbo", new[] { "open hbo max", "open hbo", "open max", "hbo max" });
            add("/api/open?app=dis", new[] { "open disney plus", "open disney", "disney plus" });
            add("/api/open?app=play", new[] { "open google play", "open play store", "open the play store" });
            add("/api/home", new[] { "home", "go home" });
            add("/api/key?code=back", new[] { "back", "go back" });
            add("/api/apps", new[] { "apps", "all apps", "show apps" });
            add("/api/media?c=playpause", new[] { "pause", "play", "resume" });
            add("/api/media?c=ff", new[] { "fast forward", "forward", "skip ahead" });
            add("/api/media?c=rew", new[] { "rewind", "go back ten seconds" });
            add("/api/media?c=next", new[] { "next", "next episode" });
            add("/api/media?c=prev", new[] { "previous" });
            add("/api/vol?d=up", new[] { "volume up", "louder", "turn it up" });
            add("/api/vol?d=down", new[] { "volume down", "quieter", "softer", "turn it down" });
            add("/api/vol?d=mute", new[] { "mute", "unmute" });
            add("/api/key?code=up", new[] { "up" });
            add("/api/key?code=down", new[] { "down" });
            add("/api/key?code=left", new[] { "left" });
            add("/api/key?code=right", new[] { "right" });
            add("/api/key?code=ok", new[] { "ok", "okay", "select", "enter" });
            add("/api/reel", new[] { "reel", "show reel" });
            add("/api/showcase", new[] { "idle", "showcase" });
            add("/api/music?on=1", new[] { "music on" });
            add("/api/music?on=0", new[] { "music off" });
            add("voice-off", new[] { "stop listening" });
            return c;
        }
        readonly Dictionary<string, string> commands = VoiceCommands();

        void ToggleVoice()
        {
            if (ear != null) { StopVoice(); Settings.Voice = false; Settings.Save(); note = "Voice off."; Relayout(); return; }
            if (!Settings.Configured) { AskSettings(); return; }
            string err = StartVoice();
            Settings.Voice = err == null; Settings.Save();
            note = err ?? "Listening. Say \"TV\" and a command: \"TV, open Netflix\", \"TV, pause\", \"TV, volume up\".";
            Relayout();
        }

        // The recognizer listens only for "TV" followed by a known command, so other talk in the room is ignored.
        static SpeechRecognitionEngine NewEar(IEnumerable<string> phrases)
        {
            RecognizerInfo info = null;
            foreach (RecognizerInfo r in SpeechRecognitionEngine.InstalledRecognizers())
                if (info == null || r.Culture.Name == "en-US") info = r;
            if (info == null) return null;
            SpeechRecognitionEngine e = new SpeechRecognitionEngine(info);
            GrammarBuilder g = new GrammarBuilder { Culture = info.Culture };
            g.Append(new Choices(Wake));
            g.Append(new Choices(new List<string>(phrases).ToArray()));
            e.LoadGrammar(new Grammar(g));
            return e;
        }

        // What a recognized phrase does: the TV call, "voice-off", or null when it is not a command.
        static string PathFor(Dictionary<string, string> commands, string text)
        {
            text = text.ToLowerInvariant();
            foreach (string w in Wake) if (text.StartsWith(w + " ")) { text = text.Substring(w.Length + 1); break; }
            string path;
            return commands.TryGetValue(text, out path) ? path : null;
        }

        string StartVoice()
        {
            try
            {
                SpeechRecognitionEngine e = NewEar(commands.Keys);
                if (e == null) return "No speech recognizer on this PC: add English speech in Windows Settings > Time & language > Speech.";
                e.SetInputToDefaultAudioDevice();
                e.SpeechRecognized += delegate (object s, SpeechRecognizedEventArgs a)
                {
                    if (a.Result.Confidence < MinConfidence) return;
                    string text = a.Result.Text;
                    try { BeginInvoke((MethodInvoker)delegate { Heard(text); }); } catch { }
                };
                e.RecognizeAsync(RecognizeMode.Multiple);
                ear = e;
                Invalidate();
                return null;
            }
            catch (InvalidOperationException) { return "No microphone found: plug one in or allow the microphone in Windows privacy settings."; }
            catch (Exception ex) { return "Voice could not start: " + ex.Message; }
        }

        void StopVoice()
        {
            SpeechRecognitionEngine e = ear; ear = null;
            if (e != null) { try { e.RecognizeAsyncCancel(); e.Dispose(); } catch { } }
            Invalidate();
        }

        const float MinConfidence = 0.6f;

        void Heard(string text)
        {
            string path = PathFor(commands, text);
            if (path == null) return;
            if (path == "voice-off") { ToggleVoice(); return; }
            note = "Heard: \"" + text + "\"";
            Send(path);
        }

        // ---------- --voice-test in.wav out.txt: recognize a recording, write what it would send, exit ----------
        static void VoiceTest(string wav, string outFile)
        {
            Dictionary<string, string> c = VoiceCommands();
            StringBuilder sb = new StringBuilder();
            using (SpeechRecognitionEngine e = NewEar(c.Keys))
            {
                if (e == null) { File.WriteAllText(outFile, "no recognizer\n"); return; }
                e.SetInputToWaveFile(wav);
                ManualResetEvent done = new ManualResetEvent(false);
                // the same continuous mode the live remote uses, so a sentence that is not a command doesn't end it
                e.SpeechRecognized += delegate (object s, SpeechRecognizedEventArgs a)
                {
                    RecognitionResult r = a.Result;
                    lock (sb) sb.AppendLine(r.Text + "\t" + r.Confidence.ToString("0.00", CultureInfo.InvariantCulture) + "\t"
                        + (r.Confidence < MinConfidence ? "(ignored: low confidence)" : PathFor(c, r.Text) ?? "(not a command)"));
                };
                e.RecognizeCompleted += delegate { done.Set(); };
                e.RecognizeAsync(RecognizeMode.Multiple);
                done.WaitOne(TimeSpan.FromMinutes(2));
            }
            File.WriteAllText(outFile, sb.ToString());
        }

        public RemoteForm()
        {
            Text = "Chill TV Remote";
            FormBorderStyle = FormBorderStyle.None;
            TopMost = true;
            ShowInTaskbar = false;
            StartPosition = FormStartPosition.Manual;
            BackColor = PanelBG;
            DoubleBuffered = true;
            Font = new Font("Segoe UI", 9.5f, FontStyle.Bold);
            Rectangle wa = Screen.PrimaryScreen.WorkingArea;
            Location = Settings.X >= 0 ? new Point(Settings.X, Settings.Y) : new Point(wa.Right - 72, wa.Bottom - 168);
            Collapse();
            Deactivate += delegate { if (expanded && SnapshotPath == null) Collapse(); };
            if (SnapshotPath != null) Shown += delegate { SaveSnapshot(); };
            else Shown += delegate
            {
                if (!Settings.Configured) AskSettings();
                else if (Settings.Voice) { string err = StartVoice(); if (err != null) note = err; }
            };
            FormClosed += delegate { StopVoice(); };
        }

        // ---------- circle <-> remote ----------
        void Collapse()
        {
            expanded = false; keys.Clear(); hot = null;
            Point anchor = Location;
            if (Width > Dot) anchor = new Point(Right - Dot, Top);   // the circle goes back where the remote's top-right was
            Size = new Size(Dot, Dot);
            Location = Fit(anchor, Size);
            using (GraphicsPath p = new GraphicsPath()) { p.AddEllipse(0, 0, Dot, Dot); Region = new Region(p); }
            Invalidate();
        }

        void Expand()
        {
            expanded = true;
            Layout_();
            Point at = new Point(Right - PanelW, Top);
            Size = new Size(PanelW, panelH);
            Location = Fit(at, Size);
            using (GraphicsPath p = Rounded(new RectangleF(0, 0, PanelW, panelH), 22)) Region = new Region(p);
            Invalidate();
            Activate();
            if (Settings.Configured) Send("/api/status");
        }

        static Point Fit(Point p, Size s)
        {
            Rectangle wa = Screen.FromPoint(p).WorkingArea;
            return new Point(Math.Max(wa.Left, Math.Min(p.X, wa.Right - s.Width)), Math.Max(wa.Top, Math.Min(p.Y, wa.Bottom - s.Height)));
        }

        // ---------- layout: the Mac remote, in pixels ----------
        void Layout_()
        {
            keys.Clear();
            float x = 14, w = PanelW - 28, y = 14;
            keys.Add(new Key(new RectangleF(PanelW - 14 - 24, y - 2, 24, 24), "–", null, Collapse) { Round = true });
            y += 30;
            // direction pad: a 150 px circle with arrows and a round OK
            float cx = PanelW / 2f, cy = y + 75;
            keys.Add(new Key(new RectangleF(cx - 18, cy - 70, 36, 30), "▲", null, delegate { Send("/api/key?code=up"); }) { Round = true });
            keys.Add(new Key(new RectangleF(cx - 18, cy + 40, 36, 30), "▼", null, delegate { Send("/api/key?code=down"); }) { Round = true });
            keys.Add(new Key(new RectangleF(cx - 70, cy - 15, 36, 30), "◀", null, delegate { Send("/api/key?code=left"); }) { Round = true });
            keys.Add(new Key(new RectangleF(cx + 34, cy - 15, 36, 30), "▶", null, delegate { Send("/api/key?code=right"); }) { Round = true });
            keys.Add(new Key(new RectangleF(cx - 25, cy - 25, 50, 50), "OK", null, delegate { Send("/api/key?code=ok"); }) { Round = true });
            y += 160;
            Row(ref y, x, w, new string[] { "Apps", "◀ Back", "⌂ Home" }, new string[] { "/api/apps", "/api/key?code=back", "/api/home" });
            Row(ref y, x, w, new string[] { "◀◀", "▶ ❚❚", "▶▶" }, new string[] { "/api/media?c=rew", "/api/media?c=playpause", "/api/media?c=ff" });
            float half = (w - 6) / 2;
            keys.Add(new Key(new RectangleF(x, y, half, 32), "NETFLIX", Color.FromArgb(229, 26, 36), delegate { Send("/api/open?app=nf"); }));
            keys.Add(new Key(new RectangleF(x + half + 6, y, half, 32), "prime video", Color.FromArgb(0, 168, 224), delegate { Send("/api/open?app=prime"); }));
            y += 42;
            Row(ref y, x, w, new string[] { "HBO Max", "Disney+", "Play" }, new string[] { "/api/open?app=hbo", "/api/open?app=dis", "/api/open?app=play" });
            bool on = computer != null && !computer.HasExited;
            keys.Add(new Key(new RectangleF(x, y, w, 32), on ? "■  Stop computer mode" : "Computer mode (keyboard & mouse)", on ? (Color?)Gold : null, ComputerMode));
            y += 42;
            keys.Add(new Key(new RectangleF(x, y, w, 32), ear != null ? "◉  Listening: say \"TV …\"" : "◉  Voice (laptop mic)", ear != null ? (Color?)Gold : null, ToggleVoice));
            y += 42;
            float third = (w - 12) / 3;
            if (!homeKind)   // reel, idle and music exist on Chill TV Showcase only
            {
                keys.Add(new Key(new RectangleF(x, y, third, 32), "▶ Reel", null, delegate { Send("/api/reel"); }));
                keys.Add(new Key(new RectangleF(x + third + 6, y, third, 32), "Idle", null, delegate { Send("/api/showcase"); }));
                keys.Add(new Key(new RectangleF(x + 2 * (third + 6), y, third, 32), music ? "♪ On" : "♪ Off", null, delegate { Send("/api/music?on=" + (music ? "0" : "1")); }));
                y += 42;
            }
            keys.Add(new Key(new RectangleF(x, y, 40, 32), "−", null, delegate { Send("/api/vol?d=down"); }));
            volBar = new RectangleF(x + 48, y + 12.5f, w - 48 - 104, 7);
            keys.Add(new Key(new RectangleF(x + w - 96, y, 40, 32), "+", null, delegate { Send("/api/vol?d=up"); }));
            keys.Add(new Key(new RectangleF(x + w - 50, y, 50, 32), muted ? "Unmute" : "Mute", null, delegate { Send("/api/vol?d=mute"); }));
            y += 42;
            panelH = (int)(y + (note.Length > 0 ? 40 : 4));
        }

        void Row(ref float y, float x, float w, string[] labels, string[] paths)
        {
            float bw = (w - 6 * (labels.Length - 1)) / labels.Length;
            for (int i = 0; i < labels.Length; i++)
            {
                string path = paths[i];
                keys.Add(new Key(new RectangleF(x + i * (bw + 6), y, bw, 32), labels[i], null, delegate { Send(path); }));
            }
            y += 42;
        }

        // ---------- drawing ----------
        static GraphicsPath Rounded(RectangleF r, float rad)
        {
            GraphicsPath p = new GraphicsPath(); float d = rad * 2;
            p.AddArc(r.X, r.Y, d, d, 180, 90); p.AddArc(r.Right - d, r.Y, d, d, 270, 90);
            p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90); p.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
            p.CloseFigure(); return p;
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            Graphics g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias; g.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;
            StringFormat mid = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
            g.Clear(PanelBG);
            if (!expanded)
            {
                // gold ring: Voice is listening, even folded up
                using (Pen ring = new Pen(online == false ? Color.FromArgb(224, 87, 79) : ear != null ? Gold : Teal, 3)) g.DrawEllipse(ring, 3.5f, 3.5f, Dot - 7, Dot - 7);
                using (Font f = new Font("Georgia", 15f, FontStyle.Bold)) using (Brush b = new SolidBrush(Gold)) g.DrawString("C", f, b, new RectangleF(0, 1, Dot, Dot), mid);
                return;
            }
            using (GraphicsPath edge = Rounded(new RectangleF(0.5f, 0.5f, PanelW - 1, panelH - 1), 22)) using (Pen p = new Pen(Color.FromArgb(31, 255, 255, 255))) g.DrawPath(p, edge);
            // header
            using (Brush b = new SolidBrush(online == true ? Teal : online == false ? Color.FromArgb(224, 87, 79) : Color.Gray)) g.FillEllipse(b, 14, 21.5f, 9, 9);
            using (Font f = new Font("Segoe UI", 9f, FontStyle.Bold)) g.DrawString("C H I L L   T V", f, Brushes.White, 28, 16);
            // pad circle
            float cx = PanelW / 2f, cy = 44 + 75;
            using (Brush b = new SolidBrush(Color.FromArgb(18, 255, 255, 255))) g.FillEllipse(b, cx - 75, cy - 75, 150, 150);
            using (Pen p = new Pen(Color.FromArgb(36, 255, 255, 255))) g.DrawEllipse(p, cx - 75, cy - 75, 150, 150);
            foreach (Key k in keys)
            {
                bool isHot = k == hot;
                if (k.Round)
                {
                    if (k.Label == "OK") using (Brush b = new SolidBrush(Color.FromArgb(isHot ? 60 : 36, 255, 255, 255))) g.FillEllipse(b, k.R);
                    else if (isHot) using (Brush b = new SolidBrush(Color.FromArgb(30, 255, 255, 255))) g.FillEllipse(b, k.R);
                    g.DrawString(k.Label, Font, Brushes.White, k.R, mid);
                    continue;
                }
                using (GraphicsPath path = Rounded(k.R, 10))
                {
                    Color fill = k.Tint ?? Color.FromArgb(isHot ? 36 : 20, 255, 255, 255);
                    if (k.Tint.HasValue && isHot) fill = ControlPaint.Light(fill, 0.2f);
                    using (Brush b = new SolidBrush(fill)) g.FillPath(b, path);
                    using (Pen p = new Pen(Color.FromArgb(31, 255, 255, 255))) g.DrawPath(p, path);
                }
                using (Brush tb = new SolidBrush(k.Tint.HasValue ? Color.Black : Color.White))
                using (Font f = new Font("Segoe UI", k.Label.Length > 20 ? 8.5f : 9.5f, FontStyle.Bold))
                    g.DrawString(k.Label, f, tb, k.R, mid);
            }
            // volume bar
            using (GraphicsPath track = Rounded(volBar, 3.5f)) using (Brush b = new SolidBrush(Color.FromArgb(26, 255, 255, 255))) g.FillPath(b, track);
            float fw = volBar.Width * volume / Math.Max(1, maxVolume);
            if (fw >= 7) using (GraphicsPath fill = Rounded(new RectangleF(volBar.X, volBar.Y, fw, volBar.Height), 3.5f)) using (Brush b = new SolidBrush(muted ? Color.Gray : Gold)) g.FillPath(b, fill);
            if (note.Length > 0)
                using (Font f = new Font("Segoe UI", 8.5f)) using (Brush b = new SolidBrush(Color.FromArgb(166, 255, 255, 255)))
                    g.DrawString(note, f, b, new RectangleF(14, panelH - 40, PanelW - 28, 36));
        }

        // ---------- mouse: drag anywhere, click a key, click the circle to open ----------
        protected override void OnMouseDown(MouseEventArgs e) { down = e.Location; dragged = false; if (e.Button == MouseButtons.Right && !expanded) Menu_(e.Location); }
        protected override void OnMouseMove(MouseEventArgs e)
        {
            if (e.Button == MouseButtons.Left && (Math.Abs(e.X - down.X) > 3 || Math.Abs(e.Y - down.Y) > 3))
            {
                dragged = true; Location = new Point(Left + e.X - down.X, Top + e.Y - down.Y); return;
            }
            Key k = null;
            foreach (Key c in keys) if (c.R.Contains(e.Location)) k = c;
            if (k != hot) { hot = k; Cursor = k != null ? Cursors.Hand : Cursors.Default; Invalidate(); }
        }
        protected override void OnMouseUp(MouseEventArgs e)
        {
            if (e.Button != MouseButtons.Left) return;
            if (dragged) { if (!expanded) { Settings.X = Left; Settings.Y = Top; Settings.Save(); } return; }
            if (!expanded) { Expand(); return; }
            foreach (Key k in keys) if (k.R.Contains(e.Location)) { k.Do(); return; }
        }
        protected override void OnMouseLeave(EventArgs e) { hot = null; Invalidate(); }

        void Menu_(Point p)
        {
            ContextMenuStrip m = new ContextMenuStrip();
            m.Items.Add("Pair with a TV (address + code)…", null, delegate { AskSettings(); });
            m.Items.Add("Quit Chill TV Remote", null, delegate { Close(); });
            m.Show(this, p);
        }

        // ---------- talking to the TV ----------
        void Send(string path)
        {
            if (!Settings.Configured) { AskSettings(); return; }
            string host = Settings.Host, key = Settings.Key;
            ThreadPool.QueueUserWorkItem(delegate
            {
                string body = null, err = null;
                int code = host.Length > 0 ? Get(host, key, path, out body, out err) : 0;
                if (code == 0)
                {
                    // Not reachable at the saved address (or none yet): the TV may have a new one. Look for it once.
                    string found = Finder.Find(key);
                    if (found != null && found != host)
                    {
                        host = found;
                        try { BeginInvoke((MethodInvoker)delegate { Settings.Host = found; Settings.Save(); }); } catch { }
                        code = Get(host, key, path, out body, out err);
                    }
                    else if (found == null)
                        err = "TV not found. Is it on, and is this computer on the same Wi-Fi?";
                }
                try { BeginInvoke((MethodInvoker)delegate { Apply(body, err); }); } catch { }
            });
        }

        // One request to the TV. Returns the HTTP status, or 0 when the TV did not answer at all.
        static int Get(string host, string key, string path, out string body, out string err)
        {
            body = null; err = null;
            string url = "http://" + host + ":8765" + path + (path.Contains("?") ? "&" : "?") + "k=" + Uri.EscapeDataString(key);
            try
            {
                HttpWebRequest req = (HttpWebRequest)WebRequest.Create(url);
                req.Timeout = 4000;
                using (HttpWebResponse r = (HttpWebResponse)req.GetResponse())
                using (StreamReader rd = new StreamReader(r.GetResponseStream())) body = rd.ReadToEnd();
                return 200;
            }
            catch (WebException we)
            {
                HttpWebResponse r = we.Response as HttpWebResponse;
                int code = r != null ? (int)r.StatusCode : 0;
                err = code == 403 ? "Wrong key" : code == 0 ? "TV not reachable (is it on?)" : "Error " + code;
                return code;
            }
            catch (Exception ex) { err = ex.Message; return -1; }
        }

        void Apply(string body, string err)
        {
            if (err != null) { online = false; note = err; }
            else
            {
                online = true; note = "";
                Match v = Regex.Match(body ?? "", "\"volume\":(\\d+)"); if (v.Success) { volume = int.Parse(v.Groups[1].Value); homeKind = body.Contains("\"kind\":\"home\""); }
                Match mx = Regex.Match(body ?? "", "\"max\":(\\d+)"); if (mx.Success) maxVolume = Math.Max(1, int.Parse(mx.Groups[1].Value));
                Match m = Regex.Match(body ?? "", "\"muted\":(true|false)"); if (m.Success) muted = m.Groups[1].Value == "true";
                Match mu = Regex.Match(body ?? "", "\"music\":(true|false)"); if (mu.Success) music = mu.Groups[1].Value == "true";
                Match n = Regex.Match(body ?? "", "\"note\":\"([^\"]*)\""); if (n.Success) note = n.Groups[1].Value;
            }
            Relayout();
        }

        void Relayout()
        {
            if (!expanded) { Invalidate(); return; }
            Layout_();
            Height = panelH;
            using (GraphicsPath p = Rounded(new RectangleF(0, 0, PanelW, panelH), 22)) Region = new Region(p);
            Invalidate();
        }

        // Computer mode: the TV in a window on this PC (scrcpy), driven by the PC keyboard and mouse.
        // Put scrcpy in a "scrcpy" folder next to this program, or on the PATH.
        void ComputerMode()
        {
            if (computer != null && !computer.HasExited) { try { computer.Kill(); } catch { } return; }
            if (!Settings.Configured) { AskSettings(); return; }
            if (Settings.Host.Length == 0) { note = "Press any remote button first so the remote finds the TV."; Relayout(); return; }
            string exe = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "scrcpy", "scrcpy.exe");
            if (!File.Exists(exe)) exe = "scrcpy.exe";
            ProcessStartInfo si = new ProcessStartInfo(exe,
                "--tcpip=" + Settings.Host + ":5555 --keyboard=uhid --mouse=sdk --no-audio --max-size 1280 --max-fps 30 --window-title \"Chill TV\"");
            si.UseShellExecute = false; si.CreateNoWindow = true;
            try
            {
                computer = Process.Start(si);
                computer.EnableRaisingEvents = true;
                computer.Exited += delegate { try { BeginInvoke((MethodInvoker)delegate { note = ""; Relayout(); }); } catch { } };
                note = "Computer mode on. Netflix and other video apps show black: use their Sign in with code.";
            }
            catch { note = "scrcpy not found: put it in a scrcpy folder next to this app."; }
            Relayout();
        }

        void AskSettings()
        {
            using (Form f = new Form())
            {
                f.Text = "Chill TV Remote"; f.FormBorderStyle = FormBorderStyle.FixedDialog; f.MaximizeBox = false; f.MinimizeBox = false;
                f.StartPosition = FormStartPosition.CenterScreen; f.ClientSize = new Size(360, 190); f.TopMost = true; f.Font = new Font("Segoe UI", 9.5f);
                Label l = new Label(); l.Text = "On the TV open Chill TV: Settings > Remote (Home) or the phone icon (Showcase). Type the pairing code; the TV address is optional (found by itself on the same Wi-Fi)."; l.Bounds = new Rectangle(14, 10, 332, 40); f.Controls.Add(l);
                TextBox host = new TextBox(); host.Bounds = new Rectangle(14, 56, 332, 24); host.Text = Settings.Host; f.Controls.Add(host);
                TextBox key = new TextBox(); key.Bounds = new Rectangle(14, 96, 332, 24); key.Text = Settings.Key; key.CharacterCasing = CharacterCasing.Upper; f.Controls.Add(key);
                Button ok = new Button(); ok.Text = "Save"; ok.Bounds = new Rectangle(190, 144, 75, 30); ok.DialogResult = DialogResult.OK; f.Controls.Add(ok);
                Button cancel = new Button(); cancel.Text = "Cancel"; cancel.Bounds = new Rectangle(271, 144, 75, 30); cancel.DialogResult = DialogResult.Cancel; f.Controls.Add(cancel);
                f.AcceptButton = ok; f.CancelButton = cancel;
                if (f.ShowDialog() == DialogResult.OK)
                {
                    Settings.Host = host.Text.Trim(); Settings.Key = key.Text.Trim(); Settings.Save();
                    if (expanded) Send("/api/status");
                }
            }
        }

        // ---------- --snapshot file.png: open, show sample status, save a picture (for the README), exit ----------
        static string SnapshotPath;
        static bool SnapshotHome;   // --snapshot file.png home: as connected to Chill TV Home
        void SaveSnapshot()
        {
            Expand();
            online = true; volume = 9; maxVolume = 15; note = ""; homeKind = SnapshotHome; Relayout();
            System.Windows.Forms.Timer t = new System.Windows.Forms.Timer(); t.Interval = 800;
            t.Tick += delegate
            {
                t.Stop();
                using (Bitmap bmp = new Bitmap(Width, Height))
                {
                    using (Graphics g = Graphics.FromImage(bmp)) g.CopyFromScreen(Location, Point.Empty, Size);
                    bmp.Save(SnapshotPath, System.Drawing.Imaging.ImageFormat.Png);
                }
                Close();
            };
            t.Start();
        }

        [STAThread]
        static void Main(string[] args)
        {
            Application.EnableVisualStyles();
            if (args.Length >= 3 && args[0] == "--voice-test") { VoiceTest(args[1], args[2]); return; }
            Settings.Load();
            if (args.Length >= 2 && args[0] == "--snapshot") { SnapshotPath = args[1]; SnapshotHome = args.Length > 2 && args[2] == "home"; }
            Application.Run(new RemoteForm());
        }
    }
}
