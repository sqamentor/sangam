// compile-only stand-in for xunit (no package available offline); signatures follow xunit 2.9
namespace Xunit;
[AttributeUsage(AttributeTargets.Method)] public sealed class FactAttribute : Attribute { }
public static class Assert
{
    public static T Single<T>(IEnumerable<T>? c) => throw null!;
    public static void Equal<T>(T expected, T actual) { }
    public static void Equal<T>(IEnumerable<T>? expected, IEnumerable<T>? actual) { }
    public static void NotEqual<T>(T expected, T actual) { }
    public static void Null(object? o) { }
    public static void NotNull(object? o) { }
    public static void True(bool? c) { }
    public static void False(bool? c) { }
    public static void Same(object? e, object? a) { }
    public static T Throws<T>(Action a) where T : Exception => throw null!;
    public static T Throws<T>(Func<object?> a) where T : Exception => throw null!;
    public static Task<T> ThrowsAsync<T>(Func<Task> a) where T : Exception => throw null!;
    public static void Contains<T>(IEnumerable<T> c, Predicate<T> p) { }
    public static void Contains(string expected, string? actual) { }
    public static void DoesNotContain<T>(IEnumerable<T> c, Predicate<T> p) { }
    public static void Empty(System.Collections.IEnumerable c) { }
    public static T IsType<T>(object? o) => throw null!;
    public static T IsAssignableFrom<T>(object? o) => throw null!;
    public static void All<T>(IEnumerable<T> c, Action<T> a) { }
}
