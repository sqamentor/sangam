<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Api;

use Sangam\Sdk\Http\ApiRequest;
use Sangam\Sdk\Http\HttpTransport;
use Sangam\Sdk\Http\Json;
use Sangam\Sdk\Model\Page;

/** Generic CRUD over one admin/ops collection (for example /admin/seats). Bodies are JSON objects. */
final class ResourceApi extends ApiSupport
{
    public function __construct(HttpTransport $http, private readonly string $path)
    {
        parent::__construct($http);
    }

    public function path(): string
    {
        return $this->path;
    }

    /** @param array<string, mixed> $filters */
    public function list(array $filters = [], ?int $page = null, ?int $size = null): Page
    {
        return $this->readPage(ApiRequest::get($this->path)->queryAll($filters)->query('page', $page)->query('size', $size));
    }

    /** @return array<string, mixed> */
    public function get(string $id): array
    {
        return (array) $this->call(ApiRequest::get($this->path . '/' . self::seg($id)));
    }

    /**
     * @param array<string, mixed> $body
     * @return array<string, mixed>
     */
    public function create(array $body, ?string $idempotencyKey = null): array
    {
        return (array) $this->call(ApiRequest::post($this->path)->body(Json::obj($body))->idempotencyKey($idempotencyKey));
    }

    /**
     * @param array<string, mixed> $body
     * @return array<string, mixed>
     */
    public function update(string $id, array $body, ?string $idempotencyKey = null): array
    {
        return (array) $this->call(ApiRequest::put($this->path . '/' . self::seg($id))->body(Json::obj($body))
            ->idempotencyKey($idempotencyKey));
    }

    public function delete(string $id, ?string $idempotencyKey = null): void
    {
        $this->call(ApiRequest::delete($this->path . '/' . self::seg($id))->idempotencyKey($idempotencyKey));
    }

    /**
     * POST <path>/<id>/<action>, for example /ops/jobs/{id}/retry.
     *
     * @param array<string, mixed>|null $body
     * @return array<string, mixed>
     */
    public function action(string $id, string $action, ?array $body = null, ?string $idempotencyKey = null): array
    {
        return (array) $this->call(ApiRequest::post($this->path . '/' . self::seg($id) . '/' . self::seg($action))
            ->body(Json::obj($body))->idempotencyKey($idempotencyKey));
    }
}
