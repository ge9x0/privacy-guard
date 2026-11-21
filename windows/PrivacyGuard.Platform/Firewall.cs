namespace PrivacyGuard.Platform;

/// <summary>Cuts a program's internet with a Windows Firewall rule (outbound, all profiles). Needs administrator rights.</summary>
public static class Firewall
{
    public const string Prefix = "Privacy Guard: block ";

    static dynamic Policy() => Activator.CreateInstance(Type.GetTypeFromProgID("HNetCfg.FwPolicy2")!)!;

    public static string RuleName(string exe) => Prefix + exe;

    public static void Block(string exe)
    {
        if (Blocked().Contains(exe, StringComparer.OrdinalIgnoreCase)) return;
        var p = Policy();
        foreach (var dir in new[] { 2, 1 })   // outbound, then inbound
        {
            dynamic r = Activator.CreateInstance(Type.GetTypeFromProgID("HNetCfg.FWRule")!)!;
            r.Name = RuleName(exe);
            r.Description = "Added by Privacy Guard. Remove it in Privacy Guard → Blocking.";
            r.ApplicationName = exe;
            r.Action = 0;          // block
            r.Direction = dir;
            r.Enabled = true;
            r.Profiles = 0x7FFFFFFF;
            p.Rules.Add(r);
        }
    }

    public static void Unblock(string exe)
    {
        var p = Policy();
        var name = RuleName(exe);
        // Remove() deletes one rule with that name per call
        for (int i = 0; i < 4 && Exists(p, name); i++) p.Rules.Remove(name);
    }

    static bool Exists(dynamic p, string name)
    {
        foreach (dynamic r in p.Rules) if ((string)r.Name == name) return true;
        return false;
    }

    public static List<string> Blocked()
    {
        var l = new List<string>();
        foreach (dynamic r in Policy().Rules)
        {
            string n = r.Name;
            if (n.StartsWith(Prefix) && (int)r.Direction == 2) l.Add(n[Prefix.Length..]);
        }
        return l;
    }

    /// <summary>Whether the firewall is on for the current network (otherwise rules have no effect).</summary>
    public static bool On()
    {
        try
        {
            var p = Policy();
            int cur = p.CurrentProfileTypes;
            foreach (var bit in new[] { 1, 2, 4 }) if ((cur & bit) != 0 && !(bool)p.FirewallEnabled[bit]) return false;
            return true;
        }
        catch { return false; }
    }
}
