package telegram4j.tl.generator;

import telegram4j.tl.api.TlObject;

import java.lang.annotation.*;

@Target(ElementType.PACKAGE)
@Retention(RetentionPolicy.SOURCE)
public @interface GenerateSchema {

    @interface Configuration {

        String name();

        String packagePrefix() default "";

        Class<?> superType() default TlObject.class;
    }

    Configuration[] value();
}
