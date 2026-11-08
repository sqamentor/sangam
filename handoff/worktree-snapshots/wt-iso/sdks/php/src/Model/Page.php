<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Model;

/**
 * One page of a list (ARCHITECTURE section 4): X-Total-Count, and X-Total-Count-Capped: true when the count was
 * capped (A-16), in which case $totalCount is a lower bound ("more than N").
 *
 * @template T
 */
final class Page implements \IteratorAggregate, \Countable
{
    /** @param list<T> $items */
    public function __construct(
        public readonly array $items,
        public readonly int $page,
        public readonly int $size,
        public readonly ?int $totalCount,
        public readonly bool $totalCountCapped,
    ) {
    }

    public function getIterator(): \ArrayIterator
    {
        return new \ArrayIterator($this->items);
    }

    public function count(): int
    {
        return count($this->items);
    }
}
