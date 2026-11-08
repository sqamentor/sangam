<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Api;

use Sangam\Sdk\Http\ApiRequest;
use Sangam\Sdk\Http\HttpTransport;
use Sangam\Sdk\Model\Page;

/** Shared plumbing for the API groups. Response bodies are decoded JSON (associative arrays). */
abstract class ApiSupport
{
    public function __construct(protected readonly HttpTransport $http)
    {
    }

    protected function call(ApiRequest $req): mixed
    {
        return $this->http->execute($req)->body;
    }

    /**
     * Reads a list endpoint: a bare JSON array or an object with "items"; plus the A-16 count headers.
     *
     * @param (\Closure(array<string,mixed>): mixed)|null $map converts each item
     */
    protected function readPage(ApiRequest $req, ?\Closure $map = null): Page
    {
        $r = $this->http->execute($req);
        $body = $r->body;
        $arr = [];
        if (is_array($body)) {
            $arr = array_is_list($body) ? $body : (is_array($body['items'] ?? null) ? $body['items'] : []);
        }
        $items = [];
        foreach ($arr as $it) {
            $items[] = ($map !== null && is_array($it)) ? $map($it) : $it;
        }
        $total = $r->header('X-Total-Count');
        $totalCount = ($total !== null && is_numeric(trim($total))) ? (int) trim($total) : null;
        $isObj = is_array($body) && !array_is_list($body);
        if ($totalCount === null && $isObj && isset($body['totalCount']) && is_numeric($body['totalCount'])) {
            $totalCount = (int) $body['totalCount'];
        }
        $capped = strtolower((string) $r->header('X-Total-Count-Capped')) === 'true'
            || ($isObj && ($body['totalCountCapped'] ?? false) === true);
        $page = $isObj && isset($body['page']) ? (int) $body['page'] : (int) ($req->query['page'] ?? 0);
        $size = $isObj && isset($body['size']) ? (int) $body['size'] : (int) ($req->query['size'] ?? count($items));
        return new Page($items, $page, $size, $totalCount, $capped);
    }

    protected static function seg(string $s): string
    {
        return HttpTransport::seg($s);
    }
}
