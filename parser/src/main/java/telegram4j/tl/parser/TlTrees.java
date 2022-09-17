package telegram4j.tl.parser;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.immutables.value.Value;

import java.util.List;

@Value.Enclosing
public class TlTrees {

    @Value.Immutable(lazyhash = true)
    @JsonSerialize(as = ImmutableTlTrees.Scheme.class)
    @JsonDeserialize(as = ImmutableTlTrees.Scheme.class)
    public static abstract class Scheme {

        public abstract List<Type> constructors();

        public abstract List<Type> methods();
    }

    @Value.Immutable
    @JsonDeserialize(using = TypeDeserializer.class)
    public static abstract class Type {

        public abstract Kind kind();

        public abstract String id();

        public abstract String name();

        public abstract List<Parameter> parameters();

        public abstract String type();

        public enum Kind {
            CONSTRUCTOR,
            METHOD
        }
    }

    @Value.Immutable
    @JsonSerialize(as = ImmutableTlTrees.Parameter.class)
    @JsonDeserialize(as = ImmutableTlTrees.Parameter.class)
    public static abstract class Parameter {

        public abstract String name();

        public abstract String type();
    }
}
