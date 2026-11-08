<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Tests;

use PHPUnit\Framework\TestCase;
use Sangam\Sdk\Model\TypedValue;

/** R33 / T18: typed values on the wire; money never travels as a binary float. */
final class TypedValueTest extends TestCase
{
    public function testDecimalIsAStringOnTheWire(): void
    {
        self::assertSame('{"type":"Decimal","value":"12.50"}', json_encode(TypedValue::decimal('12.50')));
    }

    public function testDecimalRejectsGarbage(): void
    {
        $this->expectException(\InvalidArgumentException::class);
        TypedValue::decimal('12,50');
    }

    public function testUnknownTypeRejected(): void
    {
        $this->expectException(\InvalidArgumentException::class);
        new TypedValue('Float', 1.0);
    }

    public function testDateIsUtcIso(): void
    {
        $d = new \DateTimeImmutable('2026-01-02T08:34:05.000+05:30');
        self::assertSame('2026-01-02T03:04:05.000Z', TypedValue::date($d)->value);
        self::assertSame('2026-01-02', TypedValue::localDate($d)->value);
    }

    public function testUnwrapRoundTrip(): void
    {
        self::assertSame('99.99', TypedValue::unwrap(TypedValue::decimal('99.99')->jsonSerialize()));
        self::assertSame(42, TypedValue::unwrap(['type' => 'Long', 'value' => '42']));
        self::assertSame('abc', TypedValue::unwrap(TypedValue::bytes('abc')->jsonSerialize()));
        self::assertTrue(TypedValue::unwrap(['type' => 'Boolean', 'value' => 'true']));
        self::assertNull(TypedValue::unwrap(['type' => 'Decimal', 'value' => null]));
        self::assertSame(['k' => 1], TypedValue::unwrap(['k' => 1]));
    }
}
