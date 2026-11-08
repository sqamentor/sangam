// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.channels;

import org.sangam.rest.config.SangamRestAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** REST exposure of sangam-channels. Active when sangam-server's {@code ChannelsWiring} created the services. */
@AutoConfiguration(after = SangamRestAutoConfiguration.class)
@ConditionalOnClass(name = "org.sangam.channels.conversation.ConversationService")
public class ChannelsRestConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(type = {"org.sangam.channels.conversation.ConversationService",
            "org.sangam.rest.modules.channels.ChannelStatusSource"})
    @Import({ChannelsController.class, ChannelHooksController.class, ChannelsProblemAdvice.class})
    static class Controllers {
    }
}
