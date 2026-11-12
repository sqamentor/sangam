// SPDX-License-Identifier: Apache-2.0
using System.Reflection;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;
using Sangam.Sdk.Models;
using Xunit;

namespace Sangam.Sdk.Tests;

/// <summary>
/// Drift guard: the SDK models follow <c>sangam-rest/api/sangam-api.yaml</c> (the generated contract). For each covered
/// schema the wire names (camelCase, or <see cref="JsonPropertyNameAttribute"/>) must equal the contract's properties,
/// and the non-nullable members must equal its <c>required</c> list, so an optional member is never forced onto the
/// wire (null members are not written). The YAML is read with a small line reader for the generator's PyYAML block
/// layout; a layout change fails the guard loudly instead of passing silently.
/// </summary>
public sealed class ContractGuardTests
{
    public static readonly Dictionary<string, Type> Covered = new()
    {
        ["ExternalWork"] = typeof(ExternalWork),
        ["WorkOperation"] = typeof(WorkOperation),
        ["WorkOutcome"] = typeof(WorkOutcome),
        ["LockState"] = typeof(LockState),
        ["OutcomeUnknownResult"] = typeof(OutcomeUnknownResult),
        ["TopicRequest"] = typeof(FetchTopic),
        ["FetchAndLockRequest"] = typeof(FetchAndLockRequest),
        ["WorkCompleteRequest"] = typeof(CompleteWorkRequest),
        ["WorkFailRequest"] = typeof(FailWorkRequest),
        ["WorkBpmnErrorRequest"] = typeof(BpmnErrorRequest),
        ["WorkUnlockRequest"] = typeof(UnlockRequest),
        ["ExtendLockRequest"] = typeof(ExtendLockRequest),
        ["OutcomeUnknownRequest"] = typeof(OutcomeUnknownRequest),
        ["Task"] = typeof(TaskView),
        ["CompleteTaskRequest"] = typeof(CompleteTaskRequest),
        ["ReturnTaskRequest"] = typeof(ReturnTaskRequest),
        ["CompleteTaskResult"] = typeof(CompleteTaskResult),
        ["AuditEntry"] = typeof(AuditEntry),
    };

    private static readonly Lazy<string[]> Contract = new(() => File.ReadAllLines(ContractPath()));

    private static string ContractPath()
    {
        // the test output directory (bin/...) or the working directory lies inside the repository's sangam/ tree
        foreach (var root in new[] { AppContext.BaseDirectory, Directory.GetCurrentDirectory() })
        {
            for (var dir = new DirectoryInfo(root); dir is not null; dir = dir.Parent)
            {
                var p = Path.Combine(dir.FullName, "sangam-rest", "api", "sangam-api.yaml");
                if (File.Exists(p)) return p;
            }
        }
        throw new FileNotFoundException("sangam-rest/api/sangam-api.yaml not found above " + AppContext.BaseDirectory
            + " or " + Directory.GetCurrentDirectory());
    }

    /// <summary>(properties, required) of <c>components.schemas.&lt;name&gt;</c>.</summary>
    public static (SortedSet<string> Properties, SortedSet<string> Required) ReadSchema(string name)
    {
        var lines = Contract.Value;
        var start = Array.IndexOf(lines, "  schemas:");
        Assert.True(start >= 0, "components.schemas not found");
        var head = Array.IndexOf(lines, $"    {name}:", start);
        Assert.True(head >= 0, $"schema {name} not found");
        var props = new SortedSet<string>(StringComparer.Ordinal);
        var required = new SortedSet<string>(StringComparer.Ordinal);
        var section = "";
        foreach (var line in lines.Skip(head + 1))
        {
            var indent = line.Length - line.TrimStart(' ').Length;
            if (line.Trim().Length > 0 && indent < 6) break;
            var m = Regex.Match(line, @"^ {6}(\w+):");
            if (m.Success)
            {
                section = m.Groups[1].Value;
                continue;
            }
            if (section == "properties" && (m = Regex.Match(line, @"^ {8}([A-Za-z0-9_$]+):")).Success) props.Add(m.Groups[1].Value);
            else if (section == "required" && (m = Regex.Match(line, @"^ {6}- (\S+)$")).Success) required.Add(m.Groups[1].Value);
        }
        Assert.True(props.Count > 0, $"no properties read for {name}: the contract layout changed");
        return (props, required);
    }

    /// <summary>(wire names, non-nullable wire names) of an SDK model.</summary>
    public static (SortedSet<string> Properties, SortedSet<string> Required) ReadModel(Type t)
    {
        var nullability = new NullabilityInfoContext();
        var props = new SortedSet<string>(StringComparer.Ordinal);
        var required = new SortedSet<string>(StringComparer.Ordinal);
        foreach (var p in t.GetProperties(BindingFlags.Public | BindingFlags.Instance))
        {
            if (p.GetCustomAttribute<JsonIgnoreAttribute>() is not null) continue;
            var wire = p.GetCustomAttribute<JsonPropertyNameAttribute>()?.Name ?? JsonNamingPolicy.CamelCase.ConvertName(p.Name);
            props.Add(wire);
            if (nullability.Create(p).ReadState == NullabilityState.NotNull) required.Add(wire);
        }
        return (props, required);
    }

    public static IEnumerable<object[]> Schemas() => Covered.Keys.OrderBy(k => k, StringComparer.Ordinal).Select(k => new object[] { k });

    [Theory]
    [MemberData(nameof(Schemas))]
    public void ModelMatchesContract(string schema)
    {
        var want = ReadSchema(schema);
        var have = ReadModel(Covered[schema]);
        Assert.Equal(string.Join(",", want.Properties), string.Join(",", have.Properties));
        Assert.Equal(string.Join(",", want.Required), string.Join(",", have.Required));
    }

    [Fact]
    public void ReaderSeesRequiredMembers()
    {
        Assert.Equal("lockVersion,workerId", string.Join(",", ReadSchema("WorkFailRequest").Required));
    }

    [Fact]
    public void AbsentOptionalsAreNotWritten()
    {
        var json = JsonSerializer.Serialize(new FailWorkRequest("w", 1, null), Sangam.Sdk.Http.HttpTransport.DefaultJson);
        Assert.Equal("{\"workerId\":\"w\",\"lockVersion\":1}", json);
    }
}
