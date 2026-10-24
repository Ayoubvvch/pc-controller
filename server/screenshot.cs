using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Windows.Forms;

class ScreenCapture {
    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr OpenInputDesktop(uint dwFlags, bool fInherit, uint dwDesiredAccess);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool SetThreadDesktop(IntPtr hDesktop);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool CloseDesktop(IntPtr hDesktop);

    [DllImport("user32.dll")]
    private static extern bool SetProcessDPIAware();

    static void Main(string[] args) {
        IntPtr hInput = IntPtr.Zero;
        try {
            try { SetProcessDPIAware(); } catch {}

            try {
                hInput = OpenInputDesktop(0, false, 0x01FF);
                if (hInput != IntPtr.Zero) {
                    SetThreadDesktop(hInput);
                }
            } catch {}

            string outputPath = args.Length > 0 ? args[0] : "screenshot.jpg";
            string targetScreen = args.Length > 1 ? args[1].ToLower().Trim() : "all";

            Rectangle bounds;

            if (targetScreen == "1" || targetScreen == "primary" || targetScreen == "p") {
                bounds = Screen.PrimaryScreen.Bounds;
            } else if (targetScreen == "2" || targetScreen == "secondary" || targetScreen == "s") {
                Screen[] all = Screen.AllScreens;
                if (all.Length > 1) {
                    bounds = all[0].Primary ? all[1].Bounds : all[0].Bounds;
                } else {
                    bounds = Screen.PrimaryScreen.Bounds;
                }
            } else {
                // Default: capture all monitors combined
                bounds = SystemInformation.VirtualScreen;
            }

            using (Bitmap bitmap = new Bitmap(bounds.Width, bounds.Height, PixelFormat.Format24bppRgb)) {
                using (Graphics g = Graphics.FromImage(bitmap)) {
                    g.CopyFromScreen(bounds.Location, Point.Empty, bounds.Size, CopyPixelOperation.SourceCopy);
                }

                ImageCodecInfo jpegCodec = null;
                foreach (var codec in ImageCodecInfo.GetImageEncoders()) {
                    if (codec.MimeType == "image/jpeg") {
                        jpegCodec = codec;
                        break;
                    }
                }

                if (jpegCodec != null) {
                    EncoderParameters encoderParams = new EncoderParameters(1);
                    encoderParams.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, 80L);
                    bitmap.Save(outputPath, jpegCodec, encoderParams);
                } else {
                    bitmap.Save(outputPath, ImageFormat.Jpeg);
                }
            }
            Console.WriteLine("OK:" + outputPath);
        } catch (Exception ex) {
            Console.Error.WriteLine("ERROR:" + ex.Message);
            Environment.Exit(1);
        } finally {
            if (hInput != IntPtr.Zero) {
                try { CloseDesktop(hInput); } catch {}
            }
        }
    }
}
