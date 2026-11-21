using Microsoft.Win32;
using PrivacyGuard.Core;

namespace PrivacyGuard.Platform;

/// <summary>Reads and changes Windows' privacy settings (PrivacySettings in Core). Machine-wide ones need administrator rights.</summary>
public static class SettingsIO
{
    public static int? Read(PrivacySettings.Value v)
    {
        using var k = (v.Machine ? Registry.LocalMachine : Registry.CurrentUser).OpenSubKey(v.Path);
        return k?.GetValue(v.Name) is int i ? i : null;
    }

    public static bool IsPrivate(PrivacySettings.Setting s) => PrivacySettings.IsPrivate(s, Read);

    public static void Set(PrivacySettings.Setting s, bool makePrivate)
    {
        foreach (var v in s.Values)
        {
            var hive = v.Machine ? Registry.LocalMachine : Registry.CurrentUser;
            if (makePrivate || v.Default != null)
            {
                using var k = hive.CreateSubKey(v.Path);
                k.SetValue(v.Name, makePrivate ? v.Private : v.Default!.Value, RegistryValueKind.DWord);
            }
            else
            {
                using var k = hive.OpenSubKey(v.Path, true);
                k?.DeleteValue(v.Name, false);
            }
        }
    }
}
