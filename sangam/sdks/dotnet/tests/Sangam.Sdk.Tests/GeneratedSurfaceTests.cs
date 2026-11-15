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
/// <para>The one exception is explicit in the contract: an operation marked <c>x-sangam-sdk: false</c> is called by an
/// outside system, never by an SDK user (the CloudEvents webhook handshake that Azure Event Grid sends as OPTIONS), and
/// gets no SDK method. The generator (sdks/codegen/contract_ir.py) leaves out exactly the operations so marked and lists
/// them in the manifest under <c>notForSdk</c>; this test expects the same split, so an operation can never be missing
/// from the SDKs without the marker.</para>
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

    private static readonly Regex OperationId = new(@"^ {6}operationId: (\S+)$");
    /// <summary>A key of a path item or above: the operation block of the last operationId has ended.</summary>
    private static readonly Regex OperationEnd = new(@"^ {0,4}\S");
    private static readonly Regex SdkMarker = new(@"^ {6}x-sangam-sdk:(.*)$");

    /// <summary>The operationIds of the contract: those an SDK method is generated for, and those marked x-sangam-sdk: false.</summary>
    private static (SortedSet<string> ForSdk, SortedSet<string> NotForSdk) ContractOperations(IEnumerable<string> lines)
    {
        var forSdk = new SortedSet<string>(StringComparer.Ordinal);
        var notForSdk = new SortedSet<string>(StringComparer.Ordinal);
        string? current = null;
        foreach (var raw in lines)
        {
            var line = raw.TrimEnd('\r');
            var id = OperationId.Match(line);
            var marker = SdkMarker.Match(line);
            if (id.Success)
            {
                current = id.Groups[1].Value;
                forSdk.Add(current);
            }
            else if (OperationEnd.IsMatch(line))
            {
                current = null;
            }
            else if (marker.Success)
            {
                Assert.True(current is not null, "x-sangam-sdk outside an operation (after its operationId): " + line);
                Assert.True(marker.Groups[1].Value.Trim() == "false", "only 'x-sangam-sdk: false' is written: " + line);
                forSdk.Remove(current!);
                notForSdk.Add(current!);
            }
        }
        return (forSdk, notForSdk);
    }

    private static SortedSet<string> OperationIds(string manifestKey) => new(
        Manifest().GetProperty(manifestKey).EnumerateArray().Select(r => r.GetProperty("operationId").GetString()!),
        StringComparer.Ordinal);

    [Fact]
    public void ManifestCoversEveryContractOperation()
    {
        var (forSdk, notForSdk) = ContractOperations(File.ReadAllLines(Find("sangam-rest", "api", "sangam-api.yaml")));
        var generated = OperationIds("surface");
        var left = OperationIds("notForSdk");
        Assert.True(forSdk.Count > 1000, "contract layout changed? only " + forSdk.Count + " operationIds read");
        Assert.True(forSdk.SetEquals(generated), "the generated surface is stale: run sdks/codegen/generate_sdk_surface.py; "
            + "contract only: [" + string.Join(", ", forSdk.Except(generated)) + "], manifest only: ["
            + string.Join(", ", generated.Except(forSdk)) + "]");
        Assert.True(notForSdk.SetEquals(left), "the operations without an SDK method are exactly those the contract marks "
            + "x-sangam-sdk: false; contract: [" + string.Join(", ", notForSdk) + "], manifest: [" + string.Join(", ", left) + "]");
        Assert.True(left.SetEquals(new[] { "eventGridHookHandshake" }), "an operation leaves the SDKs only by a reviewed "
            + "marker: add it here with the reason (who calls it instead of an SDK user)");
    }

    [Fact]
    public void TheMarkerIsReadPerOperation()
    {
        var (forSdk, notForSdk) = ContractOperations(new[]
        {
            "paths:", "  /hooks/x:", "    options:", "      operationId: handshake", "      responses:",
            "        '200':", "          description: Success", "      x-sangam-sdk: false", "    post:",
            "      operationId: deliver", "  /cases:", "    get:", "      operationId: listCases",
        });
        Assert.Equal(new[] { "deliver", "listCases" }, forSdk);
        Assert.Equal(new[] { "handshake" }, notForSdk);
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
