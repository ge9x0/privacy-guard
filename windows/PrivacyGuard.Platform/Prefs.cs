using System.Text.Json;

namespace PrivacyGuard.Platform;

/// <summary>App settings in %LOCALAPPDATA%\PrivacyGuard\settings.json, plus the paths of the history and the block lists.</summary>
public sealed class Prefs
{
    public static string Dir { get; set; } = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "PrivacyGuard");
    public static string DbPath => Path.Combine(Dir, "history.db");
    public static string ListPath(string id) => Path.Combine(Dir, $"list-{id}.txt");
    static string FilePath => Path.Combine(Dir, "settings.json");

    public bool Monitor { get; set; } = true;
    public bool NotifySensors { get; set; } = true;
    public bool NotifyBrokers { get; set; } = true;
    public HashSet<string> Lists { get; set; } = new() { "pro", "tif" };
    public DateTime ListsUpdated { get; set; }
    /// <summary>Store apps removed with the debloater: name -> package family (to restore them).</summary>
    public Dictionary<string, string> Removed { get; set; } = new();
    /// <summary>Store apps denied a capability here: "package|capability".</summary>
    public HashSet<string> Denied { get; set; } = new();
    public HashSet<string> AllowedHosts { get; set; } = new();

    public static Prefs Load()
    {
        try { return JsonSerializer.Deserialize<Prefs>(File.ReadAllText(FilePath)) ?? new(); } catch { return new(); }
    }

    public void Save()
    {
        Directory.CreateDirectory(Dir);
        File.WriteAllText(FilePath, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
    }
}
