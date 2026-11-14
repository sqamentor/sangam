// Compile-check stand-in for xunit (not cached locally): only the members the new tests use. Never run.
namespace Xunit
{
    [System.AttributeUsage(System.AttributeTargets.Method)] public sealed class FactAttribute : System.Attribute { }
    public static class Assert
    {
        public static void Equal<T>(T a, T b) { }
        public static void NotEqual<T>(T a, T b) { }
        public static void True(bool c, string? m = null) { }
        public static void False(bool c, string? m = null) { }
        public static void Null(object? o) { }
        public static void NotNull(object? o) { }
        public static T Single<T>(System.Collections.Generic.IEnumerable<T> c) => default!;
        public static void Single(System.Collections.IEnumerable c) { }
        public static void Empty(System.Collections.IEnumerable c) { }
        public static void Contains<T>(System.Collections.Generic.IEnumerable<T> c, System.Predicate<T> p) { }
        public static System.Threading.Tasks.Task<T> ThrowsAsync<T>(System.Func<System.Threading.Tasks.Task> f) where T : System.Exception => System.Threading.Tasks.Task.FromResult<T>(null!);
    }
}
namespace Xunit.Sdk { public class XunitException : System.Exception { public XunitException(string m) : base(m) { } } }
