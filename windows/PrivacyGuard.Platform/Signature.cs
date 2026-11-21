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

    // ---- catalog signatures: most of Windows' own files are signed through a catalog (.cat), not inside the file ----
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct CatalogInfo { public uint Size; [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 260)] public string File; }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct WtCatalogInfo
    {
        public uint Size; public uint Version; public string CatalogPath; public string MemberTag; public string MemberPath;
        public IntPtr MemberFile; public IntPtr Hash; public uint HashSize; public IntPtr CatalogContext; public IntPtr CatAdmin;
    }

    [DllImport("wintrust.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern bool CryptCATAdminAcquireContext2(out IntPtr admin, IntPtr subsystem, string hashAlg, IntPtr policy, uint flags);
    [DllImport("wintrust.dll", SetLastError = true)]
    static extern bool CryptCATAdminCalcHashFromFileHandle2(IntPtr admin, IntPtr file, ref uint size, byte[]? hash, uint flags);
    [DllImport("wintrust.dll", SetLastError = true)]
    static extern IntPtr CryptCATAdminEnumCatalogFromHash(IntPtr admin, byte[] hash, uint size, uint flags, IntPtr prev);
    [DllImport("wintrust.dll", SetLastError = true)]
    static extern bool CryptCATCatalogInfoFromContext(IntPtr cat, ref CatalogInfo info, uint flags);
    [DllImport("wintrust.dll")] static extern bool CryptCATAdminReleaseCatalogContext(IntPtr admin, IntPtr cat, uint flags);
    [DllImport("wintrust.dll")] static extern bool CryptCATAdminReleaseContext(IntPtr admin, uint flags);

    static bool CatalogSigned(string path)
    {
        foreach (var alg in new[] { "SHA256", "SHA1" })
        {
            if (!CryptCATAdminAcquireContext2(out var admin, IntPtr.Zero, alg, IntPtr.Zero, 0)) continue;
            try
            {
                using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
                var h = fs.SafeFileHandle.DangerousGetHandle();
                uint size = 0;
                CryptCATAdminCalcHashFromFileHandle2(admin, h, ref size, null, 0);
                if (size == 0 || size > 256) continue;
                var hash = new byte[size];
                if (!CryptCATAdminCalcHashFromFileHandle2(admin, h, ref size, hash, 0)) continue;
                var cat = CryptCATAdminEnumCatalogFromHash(admin, hash, size, 0, IntPtr.Zero);
                if (cat == IntPtr.Zero) continue;
                try
                {
                    var ci = new CatalogInfo { Size = (uint)Marshal.SizeOf<CatalogInfo>() };
                    if (!CryptCATCatalogInfoFromContext(cat, ref ci, 0)) continue;
                    var pHash = Marshal.AllocHGlobal((int)size);
                    var wc = new WtCatalogInfo
                    {
                        Size = (uint)Marshal.SizeOf<WtCatalogInfo>(), CatalogPath = ci.File, MemberTag = Convert.ToHexString(hash),
                        MemberPath = path, MemberFile = h, Hash = pHash, HashSize = size, CatAdmin = admin,
                    };
                    var pCat = Marshal.AllocHGlobal(Marshal.SizeOf<WtCatalogInfo>());
                    try
                    {
                        Marshal.Copy(hash, 0, pHash, (int)size);
                        Marshal.StructureToPtr(wc, pCat, false);
                        var td = new TrustData
                        {
                            Size = (uint)Marshal.SizeOf<TrustData>(), UiChoice = 2, Union = 2 /* catalog */, File = pCat, ProvFlags = 0x1000,
                        };
                        if (WinVerifyTrust(new IntPtr(-1), ref GenericVerifyV2, ref td) == 0) return true;
                    }
                    finally { Marshal.DestroyStructure<WtCatalogInfo>(pCat); Marshal.FreeHGlobal(pCat); Marshal.FreeHGlobal(pHash); }
                }
                finally { CryptCATAdminReleaseCatalogContext(admin, cat, 0); }
            }
            catch { }
            finally { CryptCATAdminReleaseContext(admin, 0); }
        }
        return false;
    }

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
            if (res == 0) return (true, signer);
            // no signature inside the file: Windows' own files are signed through a catalog instead
            if (signer == null && CatalogSigned(path)) return (true, "Microsoft Windows (catalog)");
            return (false, signer);
        }
        catch { return (null, null); }
        finally { Marshal.FreeHGlobal(pFile); }
    }
}
