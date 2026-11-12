// SPDX-License-Identifier: Apache-2.0
using System.Text.Json;
using Sangam.Sdk.Http;
using Sangam.Sdk.Models;
using Xunit;

namespace Sangam.Sdk.Tests;

/// <summary>M-062: ExternalWork.traceContext (sangam-api.yaml TraceContext) is read into the work model.</summary>
public sealed class TraceContextTests
{
    private const string Tp = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    [Fact]
    public void WorkItemCarriesTheEngineTraceContext()
    {
        var w = JsonSerializer.Deserialize<ExternalWork>(
            "{\"id\":\"wk-1\",\"lockVersion\":1,\"traceContext\":{\"traceparent\":\"" + Tp + "\",\"tracestate\":\"congo=t61rcWkgMzE\"}}",
            HttpTransport.DefaultJson)!;
        Assert.Equal(Tp, w.TraceContext!.Traceparent);
        Assert.Equal("congo=t61rcWkgMzE", w.TraceContext.Tracestate);
    }

    [Fact]
    public void TraceContextIsAbsentWhenNoneWasStored()
    {
        var w = JsonSerializer.Deserialize<ExternalWork>("{\"id\":\"wk-1\",\"lockVersion\":1}", HttpTransport.DefaultJson)!;
        Assert.Null(w.TraceContext);
    }
}
