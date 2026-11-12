// SPDX-License-Identifier: Apache-2.0
using Microsoft.Extensions.Logging;
using Sangam.Sdk.Logging;

namespace Sangam.Sdk.Hosting;

/// <summary>
/// Bridges the SDK's structured <see cref="ISangamLogger"/> to Microsoft.Extensions.Logging (M-027): the event name is the
/// <see cref="EventId"/> name, the fields become a logging scope and the message template carries <c>{SangamEvent}</c>.
/// </summary>
public sealed class MicrosoftSangamLogger : ISangamLogger
{
    private readonly ILogger _logger;

    public MicrosoftSangamLogger(ILogger logger) => _logger = logger ?? throw new ArgumentNullException(nameof(logger));

    public void Log(SangamLogLevel level, string @event, string message, IReadOnlyDictionary<string, object?>? fields, Exception? error)
    {
        var l = level switch
        {
            SangamLogLevel.Debug => LogLevel.Debug,
            SangamLogLevel.Info => LogLevel.Information,
            SangamLogLevel.Warn => LogLevel.Warning,
            _ => LogLevel.Error,
        };
        if (!_logger.IsEnabled(l)) return;
        using var scope = fields is null || fields.Count == 0 ? null : _logger.BeginScope(fields);
        _logger.Log(l, new EventId(0, @event), error, "{SangamEvent}: {Message}", @event, message);
    }
}
