<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Api;

use Sangam\Sdk\Http\ApiRequest;
use Sangam\Sdk\Http\Json;

/**
 * DMN 1.6 decisions: POST /decisions/{key}/evaluate. purpose ROUTING or AUTHORITY together with a businessKey
 * writes an audit event (class B); OTHER is side-effect free.
 */
final class DecisionsApi extends ApiSupport
{
    public const PURPOSE_ROUTING = 'ROUTING';
    public const PURPOSE_AUTHORITY = 'AUTHORITY';
    public const PURPOSE_OTHER = 'OTHER';

    /**
     * @param array<string, mixed> $variables VariableMap
     * @return array<string, mixed> DecisionEvaluateResult {decisionDefinitionId, decisionKey, version,
     *     result: list<VariableMap>, outputDigest, auditEventId}
     */
    public function evaluate(string $decisionKey, array $variables, ?int $version = null, ?string $purpose = null,
                             ?string $businessKey = null): array
    {
        return (array) $this->call(ApiRequest::post('/decisions/' . self::seg($decisionKey) . '/evaluate')
            ->body(['variables' => Json::obj($variables), 'version' => $version, 'purpose' => $purpose,
                'businessKey' => $businessKey]));
    }
}
