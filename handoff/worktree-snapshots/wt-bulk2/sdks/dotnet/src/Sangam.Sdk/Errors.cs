// SPDX-License-Identifier: Apache-2.0
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Sangam.Sdk.Errors;

/// <summary>
/// RFC 9457 <c>application/problem+json</c> body. <c>Code</c> is the Sangam machine code (for example
/// <c>STALE_LEASE</c>, <c>GONE_INSTANCE_DELETED</c>); unknown members land in <see cref="Extensions"/>.
/// </summary>
public sealed class Problem
{
    public string? Type { get; set; }
    public string? Title { get; set; }
    public int? Status { get; set; }
    public string? Detail { get; set; }
    public string? Instance { get; set; }
    public string? Code { get; set; }
    public string? TraceId { get; set; }

    [JsonExtensionData]
    public Dictionary<string, JsonElement>? Extensions { get; set; }

    /// <summary>True when <c>code</c> or the last segment of <c>type</c> equals <paramref name="name"/> (case and -/_ insensitive).</summary>
    public bool Is(string name)
    {
        var n = Norm(name);
        if (Code is not null && Norm(Code) == n) return true;
        if (Type is null) return false;
        var t = Type;
        var slash = t.LastIndexOf('/');
        if (slash >= 0) t = t[(slash + 1)..];
        var hash = t.LastIndexOf('#');
        if (hash >= 0) t = t[(hash + 1)..];
        return Norm(t) == n;
    }

    private static string Norm(string s) => s.ToLowerInvariant().Replace('_', '-');

    public override string ToString() => $"Problem{{type={Type}, title={Title}, status={Status}, code={Code}, detail={Detail}}}";
}

/// <summary>Root of all SDK exceptions.</summary>
public class SangamException : Exception
{
    public SangamException(string message, Exception? inner = null) : base(message, inner) { }
}

/// <summary>A non-2xx answer, carrying the parsed problem+json body (synthetic when the server sent none).</summary>
public class SangamApiException : SangamException
{
    public int Status { get; }
    public Problem Problem { get; }

    public SangamApiException(int status, Problem problem, string message) : base(message)
    {
        Status = status;
        Problem = problem;
    }

    /// <summary>408, 425, 429, 500, 502, 503, 504: the same request (same Idempotency-Key) may be repeated.</summary>
    public virtual bool IsRetryable => Status is 408 or 425 or 429 or 500 or 502 or 503 or 504;
}

/// <summary>
/// 409 <c>stale-lease</c> (VI.3.2 rule 2, R20): our lockVersion is no longer current and the server changed nothing.
/// The worker abandons the work item.
/// </summary>
public sealed class StaleLeaseException : SangamApiException
{
    public StaleLeaseException(Problem p, string message) : base(409, p, message) { }
    public override bool IsRetryable => false;
}

/// <summary>410 <c>GONE_INSTANCE_DELETED</c> (VI.5, R23): the instance was deleted; abandon the work item.</summary>
public sealed class GoneException : SangamApiException
{
    public GoneException(Problem p, string message) : base(410, p, message) { }
    public override bool IsRetryable => false;
}

/// <summary>Network failure (connect, reset, timeout) after the configured retries were used up.</summary>
public sealed class TransportException : SangamException
{
    public TransportException(string message, Exception inner) : base(message, inner) { }
}
