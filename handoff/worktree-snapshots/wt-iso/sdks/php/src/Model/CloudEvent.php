<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Model;

use Sangam\Sdk\Http\HttpTransport;

/**
 * CloudEvents 1.0 envelope for POST /events/{channel} (R16). Required: id, source, type, specversion.
 * $extensions are CloudEvents extension attributes, sent as the "extensions" member.
 */
final class CloudEvent implements \JsonSerializable
{
    /** @param array<string, mixed>|null $extensions */
    public function __construct(
        public readonly string $id,
        public readonly string $source,
        public readonly string $type,
        public readonly mixed $data = null,
        public readonly ?string $subject = null,
        public readonly ?string $time = null,
        public readonly ?string $datacontenttype = 'application/json',
        public readonly ?string $dataschema = null,
        public readonly ?array $extensions = null,
        public readonly string $specversion = '1.0',
    ) {
    }

    /**
     * New event with a random id and the current time. Use the business event id as $id (via the constructor)
     * when the producer may resend, so the server reports DUPLICATE instead of starting twice.
     *
     * @param array<string, mixed>|null $extensions
     */
    public static function create(string $type, string $source, mixed $data = null, ?string $subject = null,
                                  ?array $extensions = null): self
    {
        return new self(HttpTransport::uuid4(), $source, $type, $data, $subject,
            Time::iso(microtime(true)), 'application/json', null, $extensions);
    }

    /** @return array<string, mixed> */
    public function jsonSerialize(): array
    {
        $out = ['id' => $this->id, 'source' => $this->source, 'type' => $this->type, 'specversion' => $this->specversion];
        foreach (['subject', 'time', 'datacontenttype', 'dataschema'] as $k) {
            if ($this->{$k} !== null) {
                $out[$k] = $this->{$k};
            }
        }
        if ($this->data !== null) {
            $out['data'] = $this->data;
        }
        if ($this->extensions !== null && $this->extensions !== []) {
            $out['extensions'] = (object) $this->extensions;
        }
        return $out;
    }
}
