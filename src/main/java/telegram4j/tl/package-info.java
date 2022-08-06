/** Telegram API objects generated as immutable objects. */
@GenerateSchema({
        @Configuration(name = "api"),
        @Configuration(name = "mtproto", packagePrefix = "mtproto", superType = MTProtoObject.class),
})
@Value.Style(
        depluralize = true,
        jdkOnly = true,
        allMandatoryParameters = true,
        defaultAsDefault = true,
        allowedClasspathAnnotations = {Override.class}
)
@NonNullApi
@TlEncodingsEnabled
package telegram4j.tl;

import org.immutables.value.Value;
import reactor.util.annotation.NonNullApi;
import telegram4j.tl.api.MTProtoObject;
import telegram4j.tl.encoding.TlEncodingsEnabled;
import telegram4j.tl.generator.GenerateSchema;
import telegram4j.tl.generator.GenerateSchema.Configuration;
