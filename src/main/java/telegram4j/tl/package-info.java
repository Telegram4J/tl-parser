@GenerateSchema({
        @Configuration(name = "api"),
        @Configuration(name = "mtproto", packagePrefix = "mtproto", superType = MTProtoObject.class),
})
@NonNullApi
package telegram4j.tl;

import reactor.util.annotation.NonNullApi;
import telegram4j.tl.api.MTProtoObject;
import telegram4j.tl.generator.GenerateSchema;
import telegram4j.tl.generator.GenerateSchema.Configuration;
