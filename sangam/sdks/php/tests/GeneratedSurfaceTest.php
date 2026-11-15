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

    public function testManifestCoversEveryContractOperation(): void
    {
        preg_match_all('/^ {6}operationId: (\S+)\r?$/m', (string) file_get_contents(self::YAML), $m);
        $contract = $m[1];
        sort($contract);
        $generated = array_map(static fn (array $r): string => $r['operationId'], self::manifest()['surface']);
        sort($generated);
        self::assertGreaterThan(1000, count($contract), 'contract layout changed?');
        self::assertSame($contract, $generated, 'the generated surface is stale: run sdks/codegen/generate_sdk_surface.py');
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
