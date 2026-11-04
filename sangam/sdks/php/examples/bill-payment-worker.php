<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

/*
 * P1 bill/payment example worker (IX.4 P1, AC-01, AC-03) for the "payment" topic of the bill model
 * (wf1_payment, "Payment instruction").
 *
 * It posts a payment instruction to the treasury/payment gateway with Idempotency-Key = operationId (R36), so a
 * retried attempt can never pay twice. A connect failure is safe to retry (nothing was sent); a timeout after
 * sending is reported as outcome-unknown (AC-03: payment success with lost response -> UNKNOWN_OUTCOME ->
 * reconciliation). A gateway rejection is the BPMN error PAYMENT_REJECTED.
 *
 * Environment: SANGAM_URL (default http://localhost:8080), either SANGAM_TOKEN or SANGAM_OIDC_TOKEN_URL +
 * SANGAM_CLIENT_ID + SANGAM_CLIENT_SECRET (+ SANGAM_SCOPE), optional SANGAM_TOPIC (default payment),
 * SANGAM_WORKER_ID, and TREASURY_URL (unset = built-in simulator).
 *
 * Run: composer install && php examples/bill-payment-worker.php
 */

use Sangam\Sdk\Auth\ClientCredentialsTokenProvider;
use Sangam\Sdk\Auth\NoAuth;
use Sangam\Sdk\Auth\StaticTokenProvider;
use Sangam\Sdk\Auth\TokenProvider;
use Sangam\Sdk\Model\TypedValue;
use Sangam\Sdk\SangamClient;
use Sangam\Sdk\Worker\BpmnError;
use Sangam\Sdk\Worker\OutcomeUnknownException;
use Sangam\Sdk\Worker\SangamWorker;
use Sangam\Sdk\Worker\TaskContext;
use Sangam\Sdk\Worker\TaskFailedException;
use Sangam\Sdk\Worker\TopicSubscription;

$autoload = __DIR__ . '/../vendor/autoload.php';
if (is_file($autoload)) {
    require $autoload;
} else {
    // no composer install: minimal PSR-4 loader for this package
    spl_autoload_register(static function (string $class): void {
        $prefix = 'Sangam\\Sdk\\';
        if (str_starts_with($class, $prefix)) {
            $file = __DIR__ . '/../src/' . str_replace('\\', '/', substr($class, strlen($prefix))) . '.php';
            if (is_file($file)) {
                require $file;
            }
        }
    });
}

function env(string $name, ?string $default = null): ?string
{
    $v = getenv($name);
    return ($v === false || trim($v) === '') ? $default : $v;
}

function tokenProvider(): TokenProvider
{
    $tokenUrl = env('SANGAM_OIDC_TOKEN_URL');
    if ($tokenUrl !== null) {
        return new ClientCredentialsTokenProvider($tokenUrl, (string) env('SANGAM_CLIENT_ID'),
            (string) env('SANGAM_CLIENT_SECRET'), env('SANGAM_SCOPE'));
    }
    $token = env('SANGAM_TOKEN');
    return $token !== null ? new StaticTokenProvider($token) : new NoAuth();
}

/** @return array<string, mixed> */
function pay(TaskContext $ctx, ?string $treasuryUrl): array
{
    // R33: the amount arrives as a TypedValue Decimal ({"type":"Decimal","value":"125000.00"}) or a plain
    // number/string; decimal() returns it as an exact numeric string, never a float
    $amountStr = $ctx->decimal('amount');
    $beneficiary = $ctx->variable('beneficiaryAccount');
    $billNo = $ctx->businessKey();
    if ($amountStr === null || !is_numeric($amountStr) || (float) $amountStr <= 0) {
        throw new BpmnError('PAYMENT_REJECTED', "bill {$billNo} has no positive amount");
    }
    if (!is_string($beneficiary) || trim($beneficiary) === '') {
        throw new BpmnError('PAYMENT_REJECTED', "bill {$billNo} has no beneficiary account");
    }
    $ctx->throwIfCancelled();
    $ctx->log()->info('p1.payment.instruct', 'sending payment instruction', ['amount' => $amountStr]);

    if ($treasuryUrl === null) {
        // simulator: deterministic reference derived from the operationId (same op -> same reference)
        return ['paymentStatus' => 'ACKNOWLEDGED', 'paymentReference' => 'SIM-' . substr($ctx->operationId(), 0, 8),
            'paidAmount' => TypedValue::decimal($amountStr)];
    }

    $body = json_encode(['billNumber' => $billNo, 'amount' => $amountStr, 'beneficiaryAccount' => $beneficiary,
        'operationId' => $ctx->operationId()], JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES);
    $ch = curl_init(rtrim($treasuryUrl, '/') . '/payment-instructions');
    curl_setopt_array($ch, [
        CURLOPT_POST => true,
        CURLOPT_POSTFIELDS => $body,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_HTTPHEADER => ['Content-Type: application/json', 'Idempotency-Key: ' . $ctx->idempotencyKey()],
        CURLOPT_CONNECTTIMEOUT_MS => 5000,
        CURLOPT_TIMEOUT_MS => 20000,
    ]);
    $raw = curl_exec($ch);
    if ($raw === false) {
        $errno = curl_errno($ch);
        $err = curl_error($ch);
        curl_close($ch);
        if ($errno === CURLE_COULDNT_CONNECT || $errno === CURLE_COULDNT_RESOLVE_HOST) {
            throw new TaskFailedException('treasury unreachable (nothing sent): ' . $err);
        }
        // timeout or reset after the request may have been received
        throw new OutcomeUnknownException('treasury did not answer; payment may have been made',
            'POST ' . $treasuryUrl . '/payment-instructions: ' . $err, ['billNumber' => $billNo]);
    }
    $status = (int) curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
    curl_close($ch);
    if ($status === 200 || $status === 201) {
        $ack = json_decode((string) $raw, true);
        return ['paymentStatus' => 'ACKNOWLEDGED',
            'paymentReference' => is_array($ack) ? (string) ($ack['reference'] ?? '') : '',
            'paidAmount' => TypedValue::decimal($amountStr)];
    }
    if ($status === 400 || $status === 422) {
        throw new BpmnError('PAYMENT_REJECTED', 'treasury rejected the instruction: ' . $raw,
            ['paymentStatus' => 'REJECTED']);
    }
    if ($status >= 500) {
        throw new OutcomeUnknownException("treasury answered {$status}; outcome unknown", (string) $raw);
    }
    throw new TaskFailedException("unexpected treasury status {$status}");
}

$client = new SangamClient((string) env('SANGAM_URL', 'http://localhost:8080'), tokenProvider());
$treasury = env('TREASURY_URL');

$worker = new SangamWorker($client, workerId: env('SANGAM_WORKER_ID'), maxConcurrency: 4);
$worker->subscribe(new TopicSubscription(
    (string) env('SANGAM_TOPIC', 'payment'),
    static fn(TaskContext $ctx): array => pay($ctx, $treasury),
    lockDurationMs: 120_000,
    retries: 3,
    // a payment is irreversible: unexpected errors become outcome-unknown, never a blind retry
    irreversible: true,
));
$worker->run();
