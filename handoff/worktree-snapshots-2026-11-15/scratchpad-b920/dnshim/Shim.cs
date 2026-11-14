// local-only stand-in for xUnit (no NuGet download possible): same attribute/Assert surface the tests use
using System.Collections;
using System.Reflection;
namespace Xunit
{
    [AttributeUsage(AttributeTargets.Method)] public class FactAttribute : Attribute { }
    [AttributeUsage(AttributeTargets.Method)] public sealed class TheoryAttribute : FactAttribute { }
    [AttributeUsage(AttributeTargets.Method, AllowMultiple = true)] public sealed class InlineDataAttribute : Attribute { public object?[] Data; public InlineDataAttribute(params object?[] data) { Data = data; } }
    [AttributeUsage(AttributeTargets.Method)] public sealed class MemberDataAttribute : Attribute { public string Name; public MemberDataAttribute(string name) { Name = name; } }
    public sealed class AssertFail : Exception { public AssertFail(string m) : base(m) { } }
    public static class Assert
    {
        static bool Same(object? a, object? b)
        {
            if (a is IEnumerable ea && b is IEnumerable eb && a is not string && b is not string)
                return ea.Cast<object?>().SequenceEqual(eb.Cast<object?>());
            return Equals(a, b);
        }
        public static void Equal<T>(T expected, T actual) { if (!Same(expected, actual)) throw new AssertFail($"Expected <{expected}> Actual <{actual}>"); }
        public static void True(bool c, string m) { if (!c) throw new AssertFail(m); } public static void True(bool c) { if (!c) throw new AssertFail("expected true"); }
        public static void False(bool c) { if (c) throw new AssertFail("expected false"); }
        public static void Null(object? o) { if (o is not null) throw new AssertFail($"expected null, got {o}"); }
        public static void NotNull(object? o) { if (o is null) throw new AssertFail("expected not null"); }
        public static void Empty(IEnumerable e) { if (e.Cast<object>().Any()) throw new AssertFail("expected empty"); }
        public static T Single<T>(IEnumerable<T> e) { var l = e.ToList(); if (l.Count != 1) throw new AssertFail($"expected single, got {l.Count}"); return l[0]; }
        public static void StartsWith(string p, string? s) { if (s is null || !s.StartsWith(p, StringComparison.Ordinal)) throw new AssertFail($"'{s}' does not start with '{p}'"); }
        public static void DoesNotContain<T>(IEnumerable<T> e, Predicate<T> p) { if (e.Any(x => p(x))) throw new AssertFail("unexpected item"); }
        public static T Throws<T>(Func<object?> f) where T : Exception { try { f(); } catch (Exception e) when (e.GetType() == typeof(T)) { return (T)e; } throw new AssertFail("no " + typeof(T).Name); }
        public static async Task<T> ThrowsAnyAsync<T>(Func<Task> f) where T : Exception { try { await f(); } catch (T e) { return e; } throw new AssertFail("no " + typeof(T).Name); }
    }
}
public static class Program
{
    public static async Task<int> Main()
    {
        int pass = 0, fail = 0;
        foreach (var t in typeof(Program).Assembly.GetTypes().Where(t => t.Namespace == "Sangam.Sdk.Tests"))
        foreach (var m in t.GetMethods().Where(m => m.GetCustomAttribute<Xunit.FactAttribute>() is not null))
        {
            var rows = m.GetCustomAttributes<Xunit.InlineDataAttribute>().Select(a => a.Data).ToList();
            var md = m.GetCustomAttribute<Xunit.MemberDataAttribute>();
            if (md is not null) rows.AddRange(((IEnumerable<object[]>)t.GetMethod(md.Name)!.Invoke(null, null)!).Select(r => (object?[])r));
            if (rows.Count == 0) rows.Add(Array.Empty<object?>());
            foreach (var row in rows)
            {
                var name = $"{t.Name}.{m.Name}({string.Join(",", row)})";
                try
                {
                    var r = m.Invoke(Activator.CreateInstance(t), row);
                    if (r is Task task) await task;
                    pass++; Console.WriteLine("PASS " + name);
                }
                catch (Exception e) { fail++; Console.WriteLine("FAIL " + name + " :: " + (e is TargetInvocationException ti ? ti.InnerException : e)); }
            }
        }
        Console.WriteLine($"passed={pass} failed={fail}");
        return fail == 0 ? 0 : 1;
    }
}

