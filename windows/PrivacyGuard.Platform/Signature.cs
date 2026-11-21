using System.Runtime.InteropServices;
using System.Security.Cryptography.X509Certificates;

namespace PrivacyGuard.Platform;

/// <summary>Authenticode check (WinVerifyTrust): is the file signed with a valid, trusted signature, and by whom.</summary>
public static class Signature
{
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct FileInfo { public uint Size; public string Path; public IntPtr Handle; public IntPtr Subject; }

    [StructLayout(LayoutKind.Sequential)]
    struct TrustData
    {
        public uint Size; public IntPtr Policy; public IntPtr Sip; public uint UiChoice; public uint Revocation; public uint Union;
        public IntPtr File; public uint StateAction; public IntPtr State; public IntPtr Url; public uint ProvFlags; public uint UiContext; public IntPtr Signature;
    }

    [DllImport("wintrust.dll", CharSet = CharSet.Unicode)]
    static extern int WinVerifyTrust(IntPtr hwnd, ref Guid action, ref TrustData data);
    static Guid GenericVerifyV2 = new("00AAC56B-CD44-11d0-8CC2-00C04FC295EE");

    static readonly Dictionary<string, (bool?, string?)> cache = new(StringComparer.OrdinalIgnoreCase);

    public static (bool? Signed, string? Signer) Check(string path)
    {
        lock (cache) if (cache.TryGetValue(path, out var c)) return c;
        var r = Verify(path);
        lock (cache) cache[path] = r;
        return r;
    }

    static (bool?, string?) Verify(string path)
    {
        var fi = new FileInfo { Size = (uint)Marshal.SizeOf<FileInfo>(), Path = path };
        var pFile = Marshal.AllocHGlobal(Marshal.SizeOf<FileInfo>());
        try
        {
            Marshal.StructureToPtr(fi, pFile, false);
            var td = new TrustData
            {
                Size = (uint)Marshal.SizeOf<TrustData>(), UiChoice = 2 /* none */, Revocation = 0, Union = 1 /* file */,
                File = pFile, StateAction = 0, ProvFlags = 0x1000 /* cache only URL retrieval: no network */,
            };
            int res = WinVerifyTrust(new IntPtr(-1), ref GenericVerifyV2, ref td);
            string? signer = null;
            try { signer = new X509Certificate2(X509Certificate.CreateFromSignedFile(path)).GetNameInfo(X509NameType.SimpleName, false); } catch { }
            // catalog-signed Windows files have no embedded signature but verify fine
            return (res == 0, signer ?? (res == 0 ? "Microsoft Windows (catalog)" : null));
        }
        catch { return (null, null); }
        finally { Marshal.FreeHGlobal(pFile); }
    }
}
