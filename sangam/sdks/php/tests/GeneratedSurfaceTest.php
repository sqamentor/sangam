<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Tests;

use PHPUnit\Framework\TestCase;
use Sangam\Sdk\Generated\Model\ExternalWork;
use Sangam\Sdk\Generated\Model\Problem;
use Sangam\Sdk\Generated\SangamApis;
use Sangam\Sdk\Http\HttpTransport;
use Sangam\Sdk\Http\Json;
use Sangam\Sdk\Log\Log;
use Sangam\Sdk\Log\NullLogger;
use Sangam\Sdk\Tests\Support\FakeSender;

/**
 * M-020 coverage guard: every operation of sangam-api.yaml has a generated method (named by the manifest that
 * sdks/codegen/generate_sdk_surface.py writes), every schema a class, and SangamApis exposes one property per tag.
 *
 * The one exception is explicit in the contract: an operation marked "x-sangam-sdk: false" is called by an outside
 * system, never by an SDK user (the CloudEvents webhook handshake that Azure Event Grid sends as OPTIONS), and gets no
 * SDK method. The generator (sdks/codegen/contract_ir.py) leaves out exactly the operations so marked and lists them in
 * the manifest under notForSdk; this test expects the same split, so an operation can never be missing from the SDKs
 * without the marker.
 */
final class GeneratedSurfaceTest extends TestCase
{
    private const YAML = __DIR__ . '/../../../sangam-rest/api/sangam-api.yaml';
    private const MANIFEST = __DIR__ . '/../../codegen/surface-manifest.json';

    /** @return array<string, mixed> */
    private static function manifest(): array
    {
        return json_decode((string) file_get_contents(self::MANIFEST), true, 512, JSON_THROW_ON_ERROR);
    }

    /**
     * The operationIds of the contract, each list sorted: [0] those an SDK method is generated for, [1] those marked
     * x-sangam-sdk: false.
     *
     * @param list<string> $lines
     * @return array{0: list<string>, 1: list<string>}
     */
    private static function contractOperations(array $lines): array
    {
        $forSdk = [];
        $notForSdk = [];
        $current = null;
        foreach ($lines as $raw) {
            $line = rtrim($raw, "\r\n");
            if (preg_match('/^ {6}operationId: (\S+)$/', $line, $m) === 1) {
                $current = $m[1];
                $forSdk[$current] = true;
            } elseif (preg_match('/^ {0,4}\S/', $line) === 1) {
                // a key of a path item or above: the operation block of the last operationId has ended
                $current = null;
            } elseif (preg_match('/^ {6}x-sangam-sdk:(.*)$/', $line, $m) === 1) {
                self::assertNotNull($current, 'x-sangam-sdk outside an operation (after its operationId): ' . $line);
                self::assertSame('false', trim($m[1]), "only 'x-sangam-sdk: false' is written: " . $line);
                unset($forSdk[$current]);
                $notForSdk[$current] = true;
            }
        }
        $a = array_map('strval', array_keys($forSdk));
        $b = array_map('strval', array_keys($notForSdk));
        sort($a, SORT_STRING);
        sort($b, SORT_STRING);
        return [$a, $b];
    }

    /** @return list<string> */
    private static function operationIds(string $manifestKey): array
    {
        $ids = array_map(static fn (array $r): string => $r['operationId'], self::manifest()[$manifestKey]);
        sort($ids, SORT_STRING);
        return $ids;
    }

    public function testManifestCoversEveryContractOperation(): void
    {
        $lines = file(self::YAML);
        self::assertIsArray($lines, 'contract not readable at ' . self::YAML);
        [$forSdk, $notForSdk] = self::contractOperations($lines);
        $generated = self::operationIds('surface');
        $left = self::operationIds('notForSdk');
        self::assertGreaterThan(1000, count($forSdk), 'contract layout changed?');
        self::assertSame($forSdk, $generated, 'the generated surface is stale: run sdks/codegen/generate_sdk_surface.py');
        self::assertSame($notForSdk, $left,
            'the operations without an SDK method are exactly those the contract marks x-sangam-sdk: false');
        self::assertSame(['eventGridHookHandshake'], $left, 'an operation leaves the SDKs only by a reviewed marker: '
            . 'add it here with the reason (who calls it instead of an SDK user)');
    }

    public function testTheMarkerIsReadPerOperation(): void
    {
        [$forSdk, $notForSdk] = self::contractOperations([
            'paths:', '  /hooks/x:', '    options:', '      operationId: handshake', '      responses:',
            "        '200':", '          description: Success', '      x-sangam-sdk: false', '    post:',
            '      operationId: deliver', '  /cases:', '    get:', '      operationId: listCases',
        ]);
        self::assertSame(['deliver', 'listCases'], $forSdk);
        self::assertSame(['handshake'], $notForSdk);
    }

    public function testEveryOperationHasAGeneratedMethod(): void
    {
        $missing = [];
        foreach (self::manifest()['surface'] as $r) {
            [$class, $method] = explode('#', $r['php']);
            if (!method_exists('Sangam\\Sdk\\Generated\\Api\\' . $class, $method)) {
                $missing[] = $r['operationId'] . ' -> ' . $r['php'];
            }
        }
        self::assertSame([], $missing);
    }

    public function testEverySchemaIsAClassAndEveryTagAProperty(): void
    {
        foreach (self::manifest()['models'] as $name) {
            self::assertTrue(class_exists('Sangam\\Sdk\\Generated\\Model\\' . $name), $name);
        }
        $transport = new HttpTransport('http://127.0.0.1:1', null, null, 1000, 1000, new Log(new NullLogger()), null,
            null, null, new FakeSender());
        $apis = new SangamApis($transport);
        foreach ((new \ReflectionClass(SangamApis::class))->getProperties() as $p) {
            self::assertIsObject($p->getValue($apis), $p->getName());
        }
    }

    public function testAWireNameThatIsNoPhpVariableKeepsItsJsonKey(): void
    {
        // "$ref" (SCIM) cannot follow a "$" in PHP source: the property is ref, the JSON key stays "$ref"
        $g = \Sangam\Sdk\Generated\Model\ScimGroupRef::fromArray(['value' => 'g-1', '$ref' => 'https://x.example/Groups/g-1']);
        self::assertSame('https://x.example/Groups/g-1', $g->ref);
        self::assertSame(['value' => 'g-1', '$ref' => 'https://x.example/Groups/g-1'], json_decode(Json::encode($g), true));
        $u = \Sangam\Sdk\Generated\Model\ScimUser::fromArray(
            ['urn:sangam:params:scim:schemas:extension:2.0:User' => []]);
        self::assertNotNull($u->urnSangamParamsScimSchemasExtension20User);
        self::assertArrayHasKey('urn:sangam:params:scim:schemas:extension:2.0:User', json_decode(Json::encode($u), true));
    }

    public function testModelsReadTheWireNamesAndKeepUndeclaredMembers(): void
    {
        $w = ExternalWork::fromArray(['id' => 'wk-1', 'lockVersion' => 3,
            'operation' => ['state' => 'INTENT', 'mayDispatch' => true]]);
        self::assertSame('wk-1', $w->id);
        self::assertSame(3, $w->lockVersion);
        self::assertSame('INTENT', $w->operation?->state);

        $p = Problem::fromArray(['type' => 'https://sangam.dev/problems/stale-lease', 'title' => 'Stale lease',
            'status' => 409, 'code' => 'STALE_LEASE', 'workId' => 'wk-1']);
        self::assertSame(['workId' => 'wk-1'], $p->additionalProperties);
        $back = json_decode(Json::encode($p), true);
        self::assertSame('wk-1', $back['workId']);
        self::assertSame(409, $back['status']);
    }
}
