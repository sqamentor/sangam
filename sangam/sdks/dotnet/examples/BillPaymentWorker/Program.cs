// SPDX-License-Identifier: Apache-2.0
// P1 bill/payment example worker (IX.4 P1, AC-01, AC-03) for the "payment" topic of the bill model (wf1_payment).
//
// It posts a payment instruction to the treasury/payment gateway with Idempotency-Key = operationId (R36), so a
// retried attempt can never pay twice. A connect failure is safe to retry (nothing was sent); a timeout after
// sending is reported as outcome-unknown (AC-03: success with lost response -> UNKNOWN_OUTCOME -> reconciliation).
// A rejection by the gateway is the BPMN error PAYMENT_REJECTED. The amount is read through the R33 TypedValue
// unwrapping ({"type":"Decimal","value":"125000.00"} or a plain number) and paidAmount is returned as a typed Decimal.
//
// Environment: SANGAM_URL (default http://localhost:8080), either SANGAM_TOKEN or SANGAM_OIDC_TOKEN_URL +
// SANGAM_CLIENT_ID + SANGAM_CLIENT_SECRET (+ SANGAM_SCOPE), optional SANGAM_TOPIC (default "payment"),
// SANGAM_WORKER_ID and TREASURY_URL (unset = built-in simulator).
using System.Net.Http.Json;
using System.Net.Sockets;
using System.Text.Json;
using Sangam.Sdk;
using Sangam.Sdk.Auth;
using Sangam.Sdk.Logging;
using Sangam.Sdk.Models;
using Sangam.Sdk.Worker;

static string? Env(string name) => Environment.GetEnvironmentVariable(name) is { Length: > 0 } v ? v : null;

ITokenProvider tokens = Env("SANGAM_OIDC_TOKEN_URL") is { } tokenUrl
    ? new ClientCredentialsTokenProvider
    {
        TokenEndpoint = new Uri(tokenUrl),
        ClientId = Env("SANGAM_CLIENT_ID") ?? throw new InvalidOperationException("SANGAM_CLIENT_ID missing"),
        ClientSecret = Env("SANGAM_CLIENT_SECRET") ?? throw new InvalidOperationException("SANGAM_CLIENT_SECRET missing"),
        Scope = Env("SANGAM_SCOPE"),
    }
    : Env("SANGAM_TOKEN") is { } token ? TokenProviders.Static(token) : TokenProviders.None;

using var client = new SangamClient(new SangamClientOptions
{
    BaseUrl = new Uri(Env("SANGAM_URL") ?? "http://localhost:8080"),
    TokenProvider = tokens,
});

var treasuryUrl = Env("TREASURY_URL");
using var treasury = new HttpClient { Timeout = TimeSpan.FromSeconds(20) };

await using var worker = new SangamWorker(client, new SangamWorkerOptions { WorkerId = Env("SANGAM_WORKER_ID"), MaxConcurrency = 4 });
worker.Subscribe(new TopicSubscription(Env("SANGAM_TOPIC") ?? "payment", (ctx, ct) => PayAsync(ctx, treasury, treasuryUrl, ct))
{
    LockDuration = TimeSpan.FromMinutes(2),
    Retries = 3,
    // a payment is irreversible: unexpected errors become outcome-unknown, never a blind retry
    Irreversible = true,
});

using var stop = new CancellationTokenSource();
Console.CancelKeyPress += (_, e) => { e.Cancel = true; stop.Cancel(); };
AppDomain.CurrentDomain.ProcessExit += (_, _) => stop.Cancel();
await worker.RunAsync(stop.Token);

static async Task<IDictionary<string, object?>?> PayAsync(TaskContext ctx, HttpClient http, string? treasuryUrl, CancellationToken ct)
{
    var amount = ctx.Variable<decimal?>("amount");
    var beneficiary = ctx.Variable<string>("beneficiaryAccount");
    var billNo = ctx.BusinessKey;
    if (amount is null or <= 0)
        throw new BpmnErrorException("PAYMENT_REJECTED", $"bill {billNo} has no positive amount");
    if (string.IsNullOrWhiteSpace(beneficiary))
        throw new BpmnErrorException("PAYMENT_REJECTED", $"bill {billNo} has no beneficiary account");
    ctx.ThrowIfCancelled();
    ctx.Logger.Info("p1.payment.instruct", "sending payment instruction",
        new Dictionary<string, object?> { ["amount"] = amount.Value.ToString(System.Globalization.CultureInfo.InvariantCulture) });

    if (treasuryUrl is null)
    {
        // simulator: deterministic reference derived from the operationId (same op -> same reference)
        return new Dictionary<string, object?>
        {
            ["paymentStatus"] = "ACKNOWLEDGED",
            ["paymentReference"] = "SIM-" + ctx.OperationId[..Math.Min(8, ctx.OperationId.Length)],
            ["paidAmount"] = TypedValue.Decimal(amount.Value),  // R33: money as a typed Decimal
        };
    }

    using var req = new HttpRequestMessage(HttpMethod.Post, treasuryUrl.TrimEnd('/') + "/payment-instructions")
    {
        Content = JsonContent.Create(new { billNumber = billNo, amount = amount.Value.ToString(System.Globalization.CultureInfo.InvariantCulture), beneficiaryAccount = beneficiary, operationId = ctx.OperationId }),
    };
    req.Headers.Add("Idempotency-Key", ctx.IdempotencyKey);
    HttpResponseMessage resp;
    try
    {
        resp = await http.SendAsync(req, ct);
    }
    catch (HttpRequestException e) when (e.InnerException is SocketException)
    {
        throw new TaskFailedException("treasury unreachable (nothing sent)", e);
    }
    catch (TaskCanceledException e) when (!ct.IsCancellationRequested)
    {
        throw new OutcomeUnknownException("treasury did not answer in time; payment may have been made",
            "POST " + treasuryUrl + "/payment-instructions", new Dictionary<string, object?> { ["billNumber"] = billNo }, e);
    }
    using (resp)
    {
        var status = (int)resp.StatusCode;
        var body = await resp.Content.ReadAsStringAsync(ct);
        if (status is 200 or 201)
        {
            using var doc = JsonDocument.Parse(string.IsNullOrEmpty(body) ? "{}" : body);
            var reference = doc.RootElement.TryGetProperty("reference", out var r) ? r.ToString() : "";
            return new Dictionary<string, object?>
            {
                ["paymentStatus"] = "ACKNOWLEDGED",
                ["paymentReference"] = reference,
                ["paidAmount"] = TypedValue.Decimal(amount.Value),  // R33: money as a typed Decimal
            };
        }
        if (status is 400 or 422)
            throw new BpmnErrorException("PAYMENT_REJECTED", "treasury rejected the instruction: " + body,
                new Dictionary<string, object?> { ["paymentStatus"] = "REJECTED" });
        if (status >= 500)
            throw new OutcomeUnknownException($"treasury answered {status}; outcome unknown", body);
        throw new TaskFailedException($"unexpected treasury status {status}");
    }
}
