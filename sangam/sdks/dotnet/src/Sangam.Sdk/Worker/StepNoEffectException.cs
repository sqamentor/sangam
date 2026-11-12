// SPDX-License-Identifier: Apache-2.0
namespace Sangam.Sdk.Worker;

/// <summary>
/// Thrown by a <see cref="StepCall{T}"/> when the remote call certainly had no effect (it was refused before it was
/// sent, or the remote system rejected it as a business failure). The step is recorded <c>FAILED</c> with the message,
/// so a later attempt may run it again; the exception then propagates to the handler (M-025, R37).
/// </summary>
public class StepNoEffectException : Exception
{
    public StepNoEffectException(string message) : base(message) { }

    public StepNoEffectException(string message, Exception? inner) : base(message, inner) { }
}
