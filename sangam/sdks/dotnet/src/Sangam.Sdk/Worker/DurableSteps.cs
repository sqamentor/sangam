// SPDX-License-Identifier: Apache-2.0
using System.Text.Json;
using Sangam.Sdk.Errors;
using Sangam.Sdk.Models;
using static Sangam.Sdk.Logging.SangamLoggers;
using GenWorkApi = Sangam.Sdk.Generated.WorkApi;
using WorkStep = Sangam.Sdk.Generated.Models.WorkStep;
using WorkStepRequest = Sangam.Sdk.Generated.Models.WorkStepRequest;

namespace Sangam.Sdk.Worker;

/// <summary>
/// The algorithm of <see cref="TaskContext.StepAsync{T}"/> (M-025, R37) on the step journal routes
/// <c>GET/PUT /work/{id}/step?stepName=</c>, fenced by the context's lease:
/// <list type="number">
/// <item>read the step: <c>SUCCEEDED</c> replays the recorded result without calling again;</item>
/// <item><c>INTENT</c> left by an earlier attempt (the crash window): ask the lookup if any (a value is recorded
/// <c>SUCCEEDED</c> and returned; no effect means run again), else run again only for an idempotent call, else throw
/// <see cref="OutcomeUnknownException"/>;</item>
/// <item>record <c>INTENT</c>, run the call, record <c>SUCCEEDED</c> with the result, or <c>FAILED</c> on
/// <see cref="StepNoEffectException"/>; any other exception leaves <c>INTENT</c> (uncertain) and propagates.</item>
/// </list>
/// A 409 STALE_LEASE or 410 from the journal marks the lease lost and throws <see cref="OperationCanceledException"/>
/// (this SDK's "task cancelled": the worker unlocks nothing and discards the result).
/// NOT exactly-once for remote APIs: a crash between the remote call and the <c>SUCCEEDED</c> record leaves
/// <c>INTENT</c>, which step 2 resolves without a blind re-issue.
/// </summary>
internal static class DurableSteps
{
    internal const string Intent = "INTENT";
    internal const string Succeeded = "SUCCEEDED";
    internal const string Failed = "FAILED";

    internal static async Task<T> RunAsync<T>(TaskContext ctx, string name, StepCall<T> call, StepOptions<T>? options,
        CancellationToken cancellationToken)
    {
        RequireName(name);
        ArgumentNullException.ThrowIfNull(call);
        var opts = options ?? StepOptions<T>.Defaults;
        var ct = cancellationToken.CanBeCanceled ? cancellationToken : ctx.CancellationToken;
        var api = ctx.Client.Api.Work;
        var recorded = await ReadAsync(ctx, api, name, ct).ConfigureAwait(false);
        var previous = recorded?.State;
        var step = new Step(name, ctx.OperationId, previous);
        if (previous == Succeeded)
        {
            ctx.Logger.Info("step.replayed", "step already succeeded; replaying its recorded result", F(("step", name)));
            return Decode<T>(ctx, recorded!.Result);
        }
        if (previous == Intent)
        {
            if (opts.Lookup is { } lookup)
            {
                var found = await lookup(step, ct).ConfigureAwait(false);
                if (found.HasValue)
                {
                    await WriteAsync(ctx, api, name, Succeeded, found.Value, step.ProviderRef, null, ct).ConfigureAwait(false);
                    ctx.Logger.Info("step.looked-up", "earlier attempt took effect; recorded from lookup", F(("step", name)));
                    return found.Value;
                }
            }
            else if (!opts.Idempotent)
            {
                throw new OutcomeUnknownException("step " + name + " of operation " + ctx.OperationId
                        + " was started by an earlier attempt and its outcome is unknown (R37)",
                    "step=" + name,
                    new Dictionary<string, object?> { ["step"] = name, ["idempotencyKey"] = step.IdempotencyKey });
            }
        }
        await WriteAsync(ctx, api, name, Intent, null, null, null, ct).ConfigureAwait(false);
        T value;
        try
        {
            value = await call(step, ct).ConfigureAwait(false);
        }
        catch (StepNoEffectException e)
        {
            await WriteAsync(ctx, api, name, Failed, null, step.ProviderRef, e.Message, ct).ConfigureAwait(false);
            throw;
        }
        await WriteAsync(ctx, api, name, Succeeded, value, step.ProviderRef, null, ct).ConfigureAwait(false);
        return value;
    }

    /// <summary>1-128 printable ASCII characters (0x20-0x7E), else <see cref="ArgumentException"/>.</summary>
    internal static void RequireName(string name)
    {
        if (string.IsNullOrEmpty(name) || name.Length > 128)
            throw new ArgumentException("step name must be 1-128 characters", nameof(name));
        foreach (var ch in name)
        {
            if (ch < 0x20 || ch > 0x7e) throw new ArgumentException("step name must be printable ASCII", nameof(name));
        }
    }

    private static async Task<WorkStep?> ReadAsync(TaskContext ctx, GenWorkApi api, string name, CancellationToken ct)
    {
        try
        {
            return await api.GetWorkStepAsync(ctx.WorkId, name, ctx.WorkerId, ctx.LockVersion, ctx.AttemptId!, null, ct)
                .ConfigureAwait(false);
        }
        catch (Exception e) when (e is StaleLeaseException or GoneException)
        {
            throw LeaseLost(ctx, name, e);
        }
        catch (SangamApiException e) when (e.Status == 404)
        {
            return null; // never recorded: run it
        }
    }

    private static async Task WriteAsync(TaskContext ctx, GenWorkApi api, string name, string state, object? result,
        string? providerRef, string? error, CancellationToken ct)
    {
        try
        {
            await api.RecordWorkStepAsync(ctx.WorkId, new WorkStepRequest
            {
                WorkerId = ctx.WorkerId,
                LockVersion = ctx.LockVersion,
                AttemptId = ctx.AttemptId,
                State = state,
                Result = result,
                ProviderRef = providerRef,
                Error = error,
            }, name, null, ct).ConfigureAwait(false);
        }
        catch (Exception e) when (e is StaleLeaseException or GoneException)
        {
            throw LeaseLost(ctx, name, e);
        }
    }

    private static OperationCanceledException LeaseLost(TaskContext ctx, string name, Exception cause)
    {
        ctx.Logger.Warn("work.lease.lost", "lease lost while recording step " + name, F(("step", name)), cause);
        ctx.MarkLeaseLost();
        return new OperationCanceledException("lease lost while recording step " + name, cause, ctx.CancellationToken);
    }

    /// <summary>The recorded result as <typeparamref name="T"/>; R33 typed values are unwrapped like variables.</summary>
    private static T Decode<T>(TaskContext ctx, object? raw)
    {
        if (raw is null) return default!;
        if (raw is T t && raw is not JsonElement) return t;
        var json = ctx.Client.Transport.Json;
        var je = raw as JsonElement? ?? JsonSerializer.SerializeToElement(raw, json);
        if (je.ValueKind is JsonValueKind.Null or JsonValueKind.Undefined) return default!;
        return TypedValue.Unwrap<T>(je, json)!;
    }
}
