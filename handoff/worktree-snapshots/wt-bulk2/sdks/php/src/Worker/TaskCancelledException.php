<?php
// SPDX-License-Identifier: Apache-2.0
declare(strict_types=1);

namespace Sangam\Sdk\Worker;

/**
 * Thrown by TaskContext::throwIfCancelled() (or the handler) to stop cooperatively. After a server cancel request
 * the worker acknowledges it (cancel-ack, VI.3.1); on lease loss the result is discarded; on shutdown it unlocks.
 */
class TaskCancelledException extends \RuntimeException
{
}
