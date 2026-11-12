// SPDX-License-Identifier: Apache-2.0
using System.Reflection;
using System.Text.Json;
using System.Text.RegularExpressions;
using Sangam.Sdk.Generated;
using Xunit;
using M = Sangam.Sdk.Generated.Models;

namespace Sangam.Sdk.Tests;

/// <summary>
/// M-020 coverage guard: every operation of sangam-api.yaml has a generated method (named by the manifest that
/// sdks/codegen/generate_sdk_surface.py writes), every schema a record, and <c>SangamClient.Api</c> exposes the surface.
/// </summary>
public sealed class GeneratedSurfaceTests
{
    private static string Find(params string[] rel)
    {
        foreach (var root in new[] { AppContext.BaseDirectory, Directory.GetCurrentDirectory() })
        {
            for (var dir = new DirectoryInfo(root); dir is not null; dir = dir.Parent)
            {
                var p = Path.Combine(new[] { dir.FullName }.Concat(rel).ToArray());
                if (File.Exists(p)) return p;
            }
        }
        throw new FileNotFoundException(string.Join("/", rel) + " not found above " + AppContext.BaseDirectory);
    }

    private static JsonElement Manifest() =>
        JsonDocument.Parse(File.ReadAllText(Find("sdks", "codegen", "surface-manifest.json"))).RootElement;

    [Fact]
    public void ManifestCoversEveryContractOperation()
    {
        var yaml = File.ReadAllText(Find("sangam-rest", "api", "sangam-api.yaml"));
        var contract = Regex.Matches(yaml, @"^ {6}operationId: (\S+)\r?$", RegexOptions.Multiline)
            .Select(m => m.Groups[1].Value).ToHashSet();
        var generated = Manifest().GetProperty("surface").EnumerateArray()
            .Select(r => r.GetProperty("operationId").GetString()!).ToHashSet();
        Assert.True(contract.Count > 1000, "contract layout changed?");
        Assert.True(contract.SetEquals(generated), "the generated surface is stale: run sdks/codegen/generate_sdk_surface.py");
    }

    [Fact]
    public void EveryOperationHasAGeneratedMethod()
    {
        var asm = typeof(SangamApis).Assembly;
        var missing = new List<string>();
        foreach (var r in Manifest().GetProperty("surface").EnumerateArray())
        {
            var target = r.GetProperty("dotnet").GetString()!.Split('#');
            var type = asm.GetType("Sangam.Sdk.Generated." + target[0]);
            if (type?.GetMethod(target[1], BindingFlags.Public | BindingFlags.Instance) is null)
                missing.Add(r.GetProperty("operationId").GetString() + " -> " + string.Join("#", target));
        }
        Assert.Empty(missing);
    }

    [Fact]
    public void EverySchemaIsARecordAndEveryTagAProperty()
    {
        var asm = typeof(SangamApis).Assembly;
        foreach (var n in Manifest().GetProperty("models").EnumerateArray())
            Assert.NotNull(asm.GetType("Sangam.Sdk.Generated.Models." + n.GetString()));
        using var client = new SangamClient("http://127.0.0.1:1");
        Assert.NotNull(client.Api);
        foreach (var p in typeof(SangamApis).GetProperties())
            Assert.NotNull(p.GetValue(client.Api));
    }

    [Fact]
    public void RecordsReadTheWireNamesAndKeepUndeclaredMembers()
    {
        var w = JsonSerializer.Deserialize<M.ExternalWork>(
            "{\"id\":\"wk-1\",\"lockVersion\":3,\"operation\":{\"state\":\"INTENT\",\"mayDispatch\":true}}",
            Http.HttpTransport.DefaultJson)!;
        Assert.Equal("wk-1", w.Id);
        Assert.Equal(3L, w.LockVersion);
        Assert.Equal("INTENT", w.Operation!.State);

        var p = JsonSerializer.Deserialize<M.Problem>(
            "{\"type\":\"https://sangam.dev/problems/stale-lease\",\"title\":\"Stale lease\",\"status\":409,"
            + "\"code\":\"STALE_LEASE\",\"workId\":\"wk-1\"}", Http.HttpTransport.DefaultJson)!;
        Assert.Equal("STALE_LEASE", p.Code);
        Assert.Equal("wk-1", p.AdditionalProperties!["workId"].GetString());
        var back = JsonDocument.Parse(JsonSerializer.Serialize(p, Http.HttpTransport.DefaultJson)).RootElement;
        Assert.Equal("wk-1", back.GetProperty("workId").GetString());
    }
}
