<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Api;

use Sangam\Sdk\Http\ApiRequest;
use Sangam\Sdk\Http\HttpTransport;
use Sangam\Sdk\Model\CloudEvent;
use Sangam\Sdk\Model\Page;

/** Deployments, event registry and forms (/deployments, /events/**, /forms/{key}). */
final class RepositoryApi extends ApiSupport
{
    /**
     * POST /deployments as multipart/form-data: fields name, source, duplicateFiltering and one part named "files"
     * per resource (filename = array key, for example "bill.bpmn"). Idempotency-Key required by the government
     * profile (generated when null).
     *
     * @param array<string, string> $resources file name => contents
     * @return array<string, mixed> Deployment {id, name, source, tenantId, deploymentTime, resources, definitions}
     */
    public function deploy(string $name, array $resources, ?string $source = null, ?bool $duplicateFiltering = null,
                           ?string $idempotencyKey = null): array
    {
        $boundary = '----sangam' . str_replace('-', '', HttpTransport::uuid4());
        $body = self::textPart($boundary, 'name', $name);
        if ($source !== null) {
            $body .= self::textPart($boundary, 'source', $source);
        }
        if ($duplicateFiltering !== null) {
            $body .= self::textPart($boundary, 'duplicateFiltering', $duplicateFiltering ? 'true' : 'false');
        }
        foreach ($resources as $fileName => $content) {
            $fn = str_replace(['"', "\r", "\n"], '', (string) $fileName);
            $body .= '--' . $boundary . "\r\n"
                . 'Content-Disposition: form-data; name="files"; filename="' . $fn . "\"\r\n"
                . "Content-Type: application/octet-stream\r\n\r\n"
                . $content . "\r\n";
        }
        $body .= '--' . $boundary . "--\r\n";
        return (array) $this->call(ApiRequest::post('/deployments')
            ->rawBody($body, 'multipart/form-data; boundary=' . $boundary)
            ->idempotencyKey($idempotencyKey));
    }

    public function deployments(?int $page = null, ?int $size = null): Page
    {
        return $this->readPage(ApiRequest::get('/deployments')->query('page', $page)->query('size', $size));
    }

    /** @return list<mixed> */
    public function eventChannels(): array
    {
        return $this->readPage(ApiRequest::get('/events/channels'))->items;
    }

    /** @return list<mixed> */
    public function eventDefinitions(): array
    {
        return $this->readPage(ApiRequest::get('/events/definitions'))->items;
    }

    /**
     * POST /events/{channel} with a CloudEvents 1.0 envelope (R16); answered 202.
     *
     * @param CloudEvent|array<string, mixed> $event
     * @return array<string, mixed> PublishResult {eventId, status (ACCEPTED|DUPLICATE|BUFFERED), eventDefinitionKey,
     *     startedInstanceIds, correlatedInstanceIds}
     */
    public function publishEvent(string $channel, CloudEvent|array $event, ?string $idempotencyKey = null): array
    {
        return (array) $this->call(ApiRequest::post('/events/' . self::seg($channel))->body($event)
            ->idempotencyKey($idempotencyKey));
    }

    /** @return array<string, mixed> GET /forms/{key}: the form JSON schema used by the task UI. */
    public function form(string $formKey): array
    {
        return (array) $this->call(ApiRequest::get('/forms/' . self::seg($formKey)));
    }

    private static function textPart(string $boundary, string $name, string $value): string
    {
        return '--' . $boundary . "\r\nContent-Disposition: form-data; name=\"" . $name . "\"\r\n\r\n" . $value . "\r\n";
    }
}
