// SPDX-License-Identifier: Apache-2.0
using System.Text.Json;
using Sangam.Sdk.Http;
using Sangam.Sdk.Models;
using Sangam.Sdk.Worker;
using Xunit;

namespace Sangam.Sdk.Tests;

/// <summary>SL-16 stored-outcome contract (same table as the Java, Python, TypeScript and PHP SDKs).</summary>
public sealed class StoredOutcomeTests
{
    private static WorkOperation Op(string json) => JsonSerializer.Deserialize<WorkOperation>(json, HttpTransport.DefaultJson)!;

    [Fact]
    public void SucceededAndNotDispatchableCompletesFromOutputAndProviderRef()
    {
        var r = StoredOutcome.Completion(Op("{\"state\":\"SUCCEEDED\",\"attempts\":2,\"mayDispatch\":false,\"storedOutcome\":" +
            "{\"output\":{\"paymentRef\":\"PAY-77\",\"amount\":{\"type\":\"Decimal\",\"value\":\"12.50\"}},\"providerRef\":\"PAY-77\",\"duplicate\":false}}"));
        Assert.NotNull(r);
        Assert.Equal("PAY-77", r!.Reference);
        Assert.Equal("{\"paymentRef\":\"PAY-77\",\"amount\":{\"type\":\"Decimal\",\"value\":\"12.50\"}}",
            JsonSerializer.Serialize(r.Variables, HttpTransport.DefaultJson));
    }

    [Fact]
    public void MissingOrNonObjectOutputGivesEmptyVariablesAndNoReference()
    {
        var none = StoredOutcome.Completion(Op("{\"state\":\"SUCCEEDED\",\"attempts\":1,\"mayDispatch\":false}"));
        Assert.NotNull(none);
        Assert.Empty(none!.Variables);
        Assert.Null(none.Reference);
        var list = StoredOutcome.Completion(Op("{\"state\":\"SUCCEEDED\",\"attempts\":1,\"mayDispatch\":false,\"storedOutcome\":{\"output\":[1,2],\"providerRef\":null}}"));
        Assert.Empty(list!.Variables);
        Assert.Null(list.Reference);
    }

    [Fact]
    public void ConstructedOperationsWork()
    {
        var r = StoredOutcome.Completion(new WorkOperation("SUCCEEDED", 1, null, false,
            new Dictionary<string, object?> { ["output"] = new Dictionary<string, object?> { ["x"] = 1 }, ["providerRef"] = "R" }));
        Assert.Equal("R", r!.Reference);
        Assert.Equal(1, r.Variables["x"]);
    }

    [Theory]
    [InlineData("{\"state\":\"INTENT\",\"attempts\":2,\"mayDispatch\":true}")]
    [InlineData("{\"state\":\"UNKNOWN_OUTCOME\",\"attempts\":1,\"mayDispatch\":false}")]
    [InlineData("{\"state\":\"FAILED\",\"attempts\":1,\"mayDispatch\":false}")]
    public void EveryOtherStateRunsTheHandler(string json)
    {
        Assert.Null(StoredOutcome.Completion(Op(json)));
    }

    [Fact]
    public void NoOperationRunsTheHandler()
    {
        Assert.Null(StoredOutcome.Completion(null));
    }
}
