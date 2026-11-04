// SPDX-License-Identifier: Apache-2.0
namespace Sangam.Sdk.Logging;

/// <summary>Log levels of the structured hook.</summary>
public enum SangamLogLevel { Debug, Info, Warn, Error }

/// <summary>
/// Structured logging hook. Every line is a stable <c>event</c> name (for example <c>work.completed</c>) plus
/// key/value <c>fields</c> (workerId, workId, topic, operationId, attemptId, lockVersion, businessKey, ...).
/// Bridge it to Microsoft.Extensions.Logging, Serilog, JSON logs or metrics in the application.
/// </summary>
public interface ISangamLogger
{
    void Log(SangamLogLevel level, string @event, string message, IReadOnlyDictionary<string, object?>? fields, Exception? error);
}

/// <summary>Convenience overloads and built-in loggers.</summary>
public static class SangamLoggers
{
    /// <summary>Discards everything.</summary>
    public static ISangamLogger None { get; } = new DelegateLogger((_, _, _, _, _) => { });

    /// <summary>Writes <c>key=value</c> lines to stderr for Info and above.</summary>
    public static ISangamLogger Console(SangamLogLevel min = SangamLogLevel.Info) => new DelegateLogger((l, e, m, f, x) =>
    {
        if (l < min) return;
        var kv = f is null || f.Count == 0 ? "" : " " + string.Join(" ", f.Select(p => p.Key + "=" + p.Value));
        System.Console.Error.WriteLine($"{DateTimeOffset.UtcNow:O} {l.ToString().ToUpperInvariant()} event={e} msg=\"{m}\"{kv}{(x is null ? "" : " error=\"" + x.Message + "\"")}");
    });

    /// <summary>Wraps a delegate.</summary>
    public static ISangamLogger From(Action<SangamLogLevel, string, string, IReadOnlyDictionary<string, object?>?, Exception?> action) => new DelegateLogger(action);

    public static void Debug(this ISangamLogger l, string ev, string msg, IReadOnlyDictionary<string, object?>? f = null) => l.Log(SangamLogLevel.Debug, ev, msg, f, null);
    public static void Info(this ISangamLogger l, string ev, string msg, IReadOnlyDictionary<string, object?>? f = null) => l.Log(SangamLogLevel.Info, ev, msg, f, null);
    public static void Warn(this ISangamLogger l, string ev, string msg, IReadOnlyDictionary<string, object?>? f = null, Exception? e = null) => l.Log(SangamLogLevel.Warn, ev, msg, f, e);
    public static void Error(this ISangamLogger l, string ev, string msg, IReadOnlyDictionary<string, object?>? f = null, Exception? e = null) => l.Log(SangamLogLevel.Error, ev, msg, f, e);

    internal static IReadOnlyDictionary<string, object?> F(params (string Key, object? Value)[] pairs)
    {
        var d = new Dictionary<string, object?>(pairs.Length);
        foreach (var (k, v) in pairs) d[k] = v;
        return d;
    }

    private sealed class DelegateLogger : ISangamLogger
    {
        private readonly Action<SangamLogLevel, string, string, IReadOnlyDictionary<string, object?>?, Exception?> _a;
        public DelegateLogger(Action<SangamLogLevel, string, string, IReadOnlyDictionary<string, object?>?, Exception?> a) => _a = a;
        public void Log(SangamLogLevel level, string @event, string message, IReadOnlyDictionary<string, object?>? fields, Exception? error)
            => _a(level, @event, message, fields, error);
    }
}
