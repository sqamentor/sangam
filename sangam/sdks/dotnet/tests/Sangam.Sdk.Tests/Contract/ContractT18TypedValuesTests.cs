// SPDX-License-Identifier: Apache-2.0
using System.Globalization;
using System.Numerics;
using System.Text.Json;
using Sangam.Sdk.Http;
using Sangam.Sdk.Models;
using Sangam.Sdk.Testing;
using Sangam.Sdk.Worker;
using Xunit;

namespace Sangam.Sdk.Tests.Contract;

/// <summary>
/// T18 (SANGAM.md II §16, R33): Decimal, large integer, date and null keep their business value through the SDK (decode,
/// encode) and through a worker round trip, using the vectors shared by all five SDKs (<c>sdks/testing/t18-vectors.json</c>).
/// </summary>
public sealed class ContractT18TypedValuesTests
{
    public sealed record Vector(string Name, JsonElement Wire, string? Value, string Kind);

    private static readonly JsonSerializerOptions Json = HttpTransport.DefaultJson;

    internal static IReadOnlyList<Vector> Vectors()
    {
        string? path = null;
        foreach (var root in new[] { AppContext.BaseDirectory, Directory.GetCurrentDirectory() })
        {
            for (var dir = new DirectoryInfo(root); dir is not null && path is null; dir = dir.Parent)
            {
                var p = Path.Combine(dir.FullName, "testing", "t18-vectors.json");
                if (File.Exists(p)) path = p;
            }
        }
        if (path is null) throw new FileNotFoundException("sdks/testing/t18-vectors.json not found above " + AppContext.BaseDirectory);
        using var doc = JsonDocument.Parse(File.ReadAllText(path));
        var list = new List<Vector>();
        foreach (var v in doc.RootElement.GetProperty("vectors").EnumerateArray())
        {
            var value = v.GetProperty("value");
            list.Add(new Vector(v.GetProperty("name").GetString()!, v.GetProperty("wire").Clone(),
                value.ValueKind == JsonValueKind.Null ? null : value.GetString(), v.GetProperty("kind").GetString()!));
        }
        Assert.True(list.Count >= 10);
        return list;
    }

    /// <summary>Decodes a VariableMap entry to the .NET type the SDK maps the kind to.</summary>
    internal static object? Decode(string kind, JsonElement e) => kind switch
    {
        "decimal" => TypedValue.Unwrap<decimal?>(e, Json),
        "biginteger" => TypedValue.Unwrap<BigInteger?>(e, Json),
        "long" => TypedValue.Unwrap<long?>(e, Json),
        "integer" => TypedValue.Unwrap<int?>(e, Json),
        "boolean" => TypedValue.Unwrap<bool?>(e, Json),
        "string" => TypedValue.Unwrap<string>(e, Json),
        "date" => TypedValue.Unwrap<DateTimeOffset?>(e, Json),
        "localdate" => TypedValue.Unwrap<DateOnly?>(e, Json),
        "null" => e.ValueKind == JsonValueKind.Null ? null : throw new Xunit.Sdk.XunitException("null expected"),
        _ => throw new ArgumentException(kind),
    };

    internal static object? Expected(Vector v) => v.Value is null ? null : v.Kind switch
    {
        "decimal" => decimal.Parse(v.Value, NumberStyles.Number, CultureInfo.InvariantCulture),
        "biginteger" => BigInteger.Parse(v.Value, CultureInfo.InvariantCulture),
        "long" => long.Parse(v.Value, CultureInfo.InvariantCulture),
        "integer" => int.Parse(v.Value, CultureInfo.InvariantCulture),
        "boolean" => bool.Parse(v.Value),
        "string" => v.Value,
        "date" => DateTimeOffset.Parse(v.Value, CultureInfo.InvariantCulture, DateTimeStyles.AdjustToUniversal),
        "localdate" => DateOnly.ParseExact(v.Value, "yyyy-MM-dd", CultureInfo.InvariantCulture),
        _ => throw new ArgumentException(v.Kind),
    };

    internal static TypedValue Encode(string kind, object value) => kind switch
    {
        "decimal" => TypedValue.Decimal((decimal)value),
        "biginteger" => TypedValue.BigInteger((BigInteger)value),
        "long" => TypedValue.Long((long)value),
        "integer" => TypedValue.Integer((int)value),
        "boolean" => TypedValue.Boolean((bool)value),
        "string" => TypedValue.String((string)value),
        "date" => TypedValue.Date((DateTimeOffset)value),
        "localdate" => TypedValue.LocalDate((DateOnly)value),
        _ => throw new ArgumentException(kind),
    };

    /// <summary>Decimal keeps its scale (1234.50 is not 1234.5); a Date is the same instant.</summary>
    internal static void AssertSame(string name, object? want, object? got)
    {
        if (want is null)
        {
            Assert.Null(got);
            return;
        }
        Assert.NotNull(got);
        switch (want)
        {
            case decimal d:
                Assert.Equal(d, (decimal)got!);
                Assert.Equal(d.ToString(CultureInfo.InvariantCulture), ((decimal)got!).ToString(CultureInfo.InvariantCulture));
                break;
            case DateTimeOffset t:
                Assert.Equal(t.UtcDateTime, ((DateTimeOffset)got!).UtcDateTime);
                break;
            default:
                Assert.Equal(want, got);
                break;
        }
    }

    [Fact]
    public void T18_DecodeGivesTheBusinessValue()
    {
        foreach (var v in Vectors()) AssertSame(v.Name, Expected(v), Decode(v.Kind, v.Wire));
    }

    [Fact]
    public void T18_EncodeThenDecodeKeepsTheBusinessValue()
    {
        foreach (var v in Vectors())
        {
            var value = Expected(v);
            if (value is null) continue;
            var wire = JsonSerializer.SerializeToElement(Encode(v.Kind, value), Json);
            AssertSame(v.Name, value, Decode(v.Kind, wire));
        }
    }

    [Fact]
    public async Task T18_ValuesSurviveAWorkerRoundTripThroughTheEngine()
    {
        var vectors = Vectors().Where(v => v.Wire.ValueKind != JsonValueKind.Null).ToList();
        var engine = new SangamTestEngine();
        var id = engine.AddWork("t18", vectors.ToDictionary(v => v.Name, v => (object?)v.Wire));
        await using var w = ContractSupport.Worker(engine, "w-t18").Subscribe(new TopicSubscription("t18", (ctx, _) =>
        {
            var out_ = new Dictionary<string, object?>();
            foreach (var v in vectors)
            {
                var raw = JsonSerializer.SerializeToElement(ctx.Variables[v.Name], Json);
                out_[v.Name] = Encode(v.Kind, Decode(v.Kind, raw)!);
            }
            return Task.FromResult<IDictionary<string, object?>?>(out_);
        }) { LockDuration = TimeSpan.FromMinutes(1) });
        w.Start();
        await engine.WaitForStateAsync(id, "COMPLETED", TimeSpan.FromSeconds(10));
        await w.StopAsync();
        var back = engine.CompletedVariables(id)!;
        foreach (var v in vectors)
        {
            var e = JsonSerializer.SerializeToElement(back[v.Name], Json);
            AssertSame(v.Name, Expected(v), Decode(v.Kind, e));
        }
    }
}
